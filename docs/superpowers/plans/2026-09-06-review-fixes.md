# Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the five defects found by the 2026-09-06 review, in impact order — the pace projection that is 3.5x wrong and fires the gate on nearly every payment, money formatted for the wrong country, and three smaller UI-glue bugs.

**Architecture:** Recurring-payee detection lands as a pure function over transactions in `stats/`, so it is JVM-testable and usable from both the pure `GardenFolder` and the Room-backed gate path. `PaceProjector` and `GateEvaluator` gain a `fixedPaise` term; every caller must supply it, so no site can silently keep the old behaviour.

**Tech Stack:** Kotlin, Room, Compose, JUnit4.

**Source:** the review in conversation on 2026-09-06. Two design decisions confirmed by Rajdweep: the fix applies to **both** dashboard and gate, and fixed costs are identified by **recurring-payee detection**.

---

## Before you start

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Baseline is **270 JVM + 67 instrumented, 0 failures**. Every task must end there or higher.

The emulator currently holds 83 seeded review transactions (3 months, rent on day 1). That data is what makes these bugs visible — keep it until Task 6, which verifies against it.

---

## Task 1: Detect recurring payees

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/stats/RecurringPayees.kt`
- Test: `app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt`

A payee is **recurring** when it had exactly one LOGGED transaction in *each* complete look-back
month. Rent, BESCOM, Airtel, Netflix and the SIP qualify; Swiggy and the kirana (6–8 a month)
do not.

**The current month is never examined.** Including it would make a payee's status flip
mid-month — on day 2 Swiggy has one order and would look recurring — and the projection would
jump around as the month fills in. Complete months only means the answer is constant for the
whole month it is used in.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.stats

import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class RecurringPayeesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int) =
        LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun txn(payeeId: Long, y: Int, m: Int, d: Int) = TransactionEntity(
        uuid = "$payeeId-$y-$m-$d", amountPaise = 1000, payeeId = payeeId, categoryId = 1,
        source = TxnSource.MANUAL, status = TxnStatus.LOGGED, breachedAtLogging = false,
        note = null, occurredAt = at(y, m, d), createdAt = at(y, m, d), updatedAt = 1L,
    )

    private fun detect(txns: List<TransactionEntity>, current: YearMonth = YearMonth.of(2026, 9)) =
        RecurringPayees.detect(txns, current, zone)

    @Test fun `a payee billed once a month in every look-back month is recurring`() {
        assertEquals(setOf(1L), detect(listOf(txn(1, 2026, 7, 1), txn(1, 2026, 8, 1))))
    }

    @Test fun `a payee used many times a month is not recurring`() {
        val many = (1..6).map { txn(2, 2026, 7, it) } + (1..7).map { txn(2, 2026, 8, it) }
        assertEquals(emptySet<Long>(), detect(many))
    }

    @Test fun `a payee missing from one look-back month is not recurring`() {
        // Rent you skipped, or a subscription started last month. Not yet a reliable fixed cost.
        assertEquals(emptySet<Long>(), detect(listOf(txn(3, 2026, 8, 1))))
    }

    @Test fun `the current month is never examined`() {
        // THE stability rule. This payee looks like one-a-month only if September counts;
        // on the complete months alone it is a heavy user and must stay variable.
        val txns = (1..6).map { txn(4, 2026, 7, it) } + (1..6).map { txn(4, 2026, 8, it) } +
            listOf(txn(4, 2026, 9, 2))
        assertEquals(emptySet<Long>(), detect(txns))
    }

    @Test fun `a payee that appears twice in one look-back month is not recurring`() {
        assertEquals(
            emptySet<Long>(),
            detect(listOf(txn(5, 2026, 7, 1), txn(5, 2026, 8, 1), txn(5, 2026, 8, 20))),
        )
    }

    @Test fun `one complete month of history is enough`() {
        // A new install must not wait two months for the projection to become sane.
        assertEquals(setOf(6L), detect(listOf(txn(6, 2026, 8, 1))))
    }

    @Test fun `no complete months means nothing is recurring`() {
        // Falls back to plain linear projection, which is the pre-fix behaviour.
        assertEquals(emptySet<Long>(), detect(listOf(txn(7, 2026, 9, 1))))
    }

    @Test fun `only LOGGED transactions count`() {
        val pending = txn(8, 2026, 7, 1).copy(status = TxnStatus.PENDING_CONFIRM)
        assertEquals(emptySet<Long>(), detect(listOf(pending, txn(8, 2026, 8, 1))))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.stats.RecurringPayeesTest'
```

Expected: FAIL, `Unresolved reference 'RecurringPayees'`.

- [ ] **Step 3: Implement**

```kotlin
package com.expensegarden.app.stats

import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnStatus
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Which payees are fixed monthly costs — rent, utilities, subscriptions, the SIP.
 *
 *  This is the "recurring detection" the parent spec listed as a stats capability and which
 *  PaceProjector was built as though it already had. Without it, linear extrapolation
 *  multiplies a day-1 rent payment by the length of the month: measured on real data, a
 *  projected month-end of Rs 1,65,305 against an actual Rs 43-49k.
 *
 *  The rule is deliberately blunt — exactly one LOGGED transaction in each complete look-back
 *  month — because the alternative signals are worse. `isNecessity` is wrong in both
 *  directions: Groceries is a necessity bought weekly, and Netflix is not a necessity at all.
 *  Amount thresholds would classify a one-off impulse buy as fixed, which is precisely the
 *  spend the gate exists to notice.
 *
 *  COMPLETE MONTHS ONLY. Including the current month would let a payee's status flip
 *  mid-month — on day 2 a food-delivery payee has one order and looks recurring — and the
 *  projection would lurch as the month fills in. Judging on finished months alone keeps the
 *  answer constant for the whole month it is used in.
 *
 *  A false positive costs little: a genuinely one-off payment is added once rather than
 *  extrapolated, which is arguably the more honest treatment anyway. */
object RecurringPayees {

    /** How many complete months back to look. Two is enough to tell rent from lunch, and
     *  short enough that a cancelled subscription stops counting quickly. */
    const val LOOK_BACK_MONTHS = 2

    /** @param txns any superset of the look-back window; non-LOGGED rows are ignored.
     *  @return payee ids that behave like a fixed monthly cost. Empty when there is no
     *    complete month of history yet, which degrades to the old linear projection. */
    fun detect(
        txns: List<TransactionEntity>,
        currentMonth: YearMonth,
        zone: ZoneId,
    ): Set<Long> {
        val months = (1..LOOK_BACK_MONTHS).map { currentMonth.minusMonths(it.toLong()) }
        val logged = txns.filter { it.status == TxnStatus.LOGGED }
        val byMonth = logged.groupBy {
            YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone))
        }

        // Only months that actually have data count as look-back months, so a fresh install
        // with one month of history still gets detection rather than waiting for two.
        val present = months.filter { byMonth.containsKey(it) }
        if (present.isEmpty()) return emptySet()

        val perMonthCounts = present.map { month ->
            byMonth.getValue(month).groupingBy { it.payeeId }.eachCount()
        }
        return perMonthCounts
            .map { counts -> counts.filterValues { it == 1 }.keys }
            .reduce { acc, ids -> acc intersect ids }
    }

    /** This month's spend attributable to fixed costs. */
    fun fixedSpentPaise(monthTxns: List<TransactionEntity>, recurring: Set<Long>): Long =
        monthTxns.filter { it.status == TxnStatus.LOGGED && it.payeeId in recurring }
            .sumOf { it.amountPaise }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.stats.RecurringPayeesTest'
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/stats/RecurringPayees.kt \
        app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt
git commit -m "feat: detect recurring payees so fixed costs stop being extrapolated"
```

---

## Task 2: Take fixed costs out of the projection and the pace allowance

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/stats/PaceProjector.kt`
- Modify: `app/src/main/java/com/expensegarden/app/gate/GateEvaluator.kt`
- Test: `app/src/test/java/com/expensegarden/app/stats/PaceProjectorTest.kt`
- Test: `app/src/test/java/com/expensegarden/app/gate/GateEvaluatorTest.kt`

Both sides of the comparison lose their fixed component. Equivalently: variable spend is
judged against the variable half of the budget.

```
projected = fixedSpent + variableSpent * daysInMonth / day
allowance = fixedSpent + (budget - fixedSpent) * day/days * GRACE
```

- [ ] **Step 1: Write the failing tests**

Replace the body of `PaceProjectorTest` with:

```kotlin
package com.expensegarden.app.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class PaceProjectorTest {
    @Test fun `linear projection scales spend to month length`() =
        assertEquals(900_000L, PaceProjector.projectedMonthEndPaise(300_000L, 0L, 10, 30))

    @Test fun `projection on day one is spend times month length`() =
        assertEquals(3_000_000L, PaceProjector.projectedMonthEndPaise(100_000L, 0L, 1, 30))

    @Test fun `a fixed cost is added once, never extrapolated`() {
        // THE review finding, in one assertion. Rs 33,061 spent by day 6, of which Rs 26,174
        // is rent, utilities and the SIP. The old formula gave Rs 1,65,305 against an actual
        // month of Rs 43-49k.
        val projected = PaceProjector.projectedMonthEndPaise(3_306_100L, 2_617_400L, 6, 30)
        assertEquals(2_617_400L + (3_306_100L - 2_617_400L) * 30 / 6, projected)
        assertEquals(6_060_900L, projected)
    }

    @Test fun `an all-fixed month projects to exactly what has been spent`() =
        assertEquals(1_800_000L, PaceProjector.projectedMonthEndPaise(1_800_000L, 1_800_000L, 1, 30))

    @Test fun `fixed spend above total cannot drive the variable part negative`() =
        assertEquals(500_000L, PaceProjector.projectedMonthEndPaise(500_000L, 900_000L, 5, 30))

    @Test fun `per-day allowance divides remaining budget over remaining days incl today`() {
        assertEquals(28_571L, PaceProjector.perDayToStayUnderPaise(400_000L, 1_000_000L, 10, 30))
    }

    @Test fun `per-day allowance floors at zero once budget is gone`() =
        assertEquals(0L, PaceProjector.perDayToStayUnderPaise(1_200_000L, 1_000_000L, 10, 30))

    @Test fun `per-day allowance on the last day is the whole remainder`() =
        assertEquals(50_000L, PaceProjector.perDayToStayUnderPaise(950_000L, 1_000_000L, 30, 30))
}
```

Append to `GateEvaluatorTest`:

```kotlin
    // ---------- fixed costs (review fix, 2026-09-06) ----------

    @Test fun `rent paid on day one no longer trips the pace warning`() {
        // The review's core damage: with Rs 18,000 rent against a Rs 42,000 budget, the old
        // allowance was Rs 9,660 on day 6 while spend was Rs 33,061 — so the gate raised a
        // dialog on essentially every payment from day 1 to day 22. Pressure that fires
        // constantly stops being pressure.
        val budget = 4_200_000L
        val fixed = 1_800_000L
        val spent = fixed + 180_000L                 // rent plus Rs 1,800 of variable spend
        assertEquals(Severity.OK, GateEvaluator.evaluate(spent, budget, 0L, fixed, 6, 30))
    }

    @Test fun `genuine overspending on the variable half still warns`() {
        val budget = 4_200_000L
        val fixed = 1_800_000L
        val spent = fixed + 1_500_000L               // Rs 15,000 of variable by day 6
        assertEquals(Severity.PACE_WARNING, GateEvaluator.evaluate(spent, budget, 0L, fixed, 6, 30))
    }

    @Test fun `a breach is still a breach regardless of what is fixed`() {
        // Over the budget is over the budget — the fixed term only ever moves the PACE line.
        assertEquals(
            Severity.BREACH,
            GateEvaluator.evaluate(4_300_000L, 4_200_000L, 0L, 1_800_000L, 6, 30),
        )
    }

    @Test fun `zero fixed spend reproduces the old behaviour exactly`() {
        for (day in 1..30) {
            assertEquals(
                GateEvaluator.paceAllowancePaise(1_000_000L, day, 30),
                GateEvaluator.paceAllowancePaise(1_000_000L, 0L, day, 30),
            )
        }
    }

    @Test fun `fixed spend exceeding the budget leaves no variable allowance`() {
        // Rent alone blew the budget. Every further payment is a breach, not a pace nudge.
        assertEquals(
            Severity.BREACH,
            GateEvaluator.evaluate(4_500_000L, 4_200_000L, 0L, 4_500_000L, 6, 30),
        )
    }
```

- [ ] **Step 2: Run to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.stats.PaceProjectorTest' --tests 'com.expensegarden.app.gate.GateEvaluatorTest'
```

Expected: FAIL to compile — the new arity does not exist yet.

- [ ] **Step 3: Implement PaceProjector**

Replace the whole file:

```kotlin
package com.expensegarden.app.stats

/** Classical-stats month pace (spec §8.3). Money stays paise-Long; division truncates at
 *  display precision.
 *
 *  `fixedPaise` is this month's spend on recurring payees — see [RecurringPayees]. It is added
 *  back once instead of being extrapolated, because a linear projection over a day-1 rent
 *  payment multiplies it by the length of the month. Measured on real data before this fix: a
 *  headline of Rs 1,65,305 by month end, against an actual month of Rs 43-49k.
 *
 *  Fixed costs that have NOT yet landed are deliberately not predicted. Under-counting a rent
 *  payment still to come is a smaller and quieter error than over-counting one already made by
 *  5x, and it corrects itself the day the payment lands. Predicting it would mean guessing an
 *  amount, which is a second thing to be wrong about. */
object PaceProjector {
    fun projectedMonthEndPaise(
        spentPaise: Long,
        fixedPaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Long {
        val day = dayOfMonth.coerceAtLeast(1)
        // coerceAtLeast(0): a fixed total above the month total is arithmetically impossible,
        // but a caller passing mismatched windows must not produce a projection below spend.
        val variable = (spentPaise - fixedPaise).coerceAtLeast(0L)
        return fixedPaise.coerceAtMost(spentPaise) + variable * daysInMonth / day
    }

    /** Remaining budget spread over the remaining days, today included. 0 when over. */
    fun perDayToStayUnderPaise(spentPaise: Long, budgetPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long {
        val daysLeft = (daysInMonth - dayOfMonth + 1).coerceAtLeast(1)
        return ((budgetPaise - spentPaise) / daysLeft).coerceAtLeast(0L)
    }
}
```

- [ ] **Step 4: Implement GateEvaluator**

```kotlin
package com.expensegarden.app.gate

enum class Severity { OK, PACE_WARNING, BREACH }

object GateEvaluator {
    private const val PACE_GRACE = 1.15

    /** Day-proportional spend allowance incl. grace, floored to paise.
     *
     *  Kept for callers with no notion of fixed spend; equivalent to fixedPaise = 0. */
    fun paceAllowancePaise(budgetPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long =
        paceAllowancePaise(budgetPaise, 0L, dayOfMonth, daysInMonth)

    /** Allowance with fixed monthly costs excluded from BOTH sides of the comparison.
     *
     *  Only the variable half of the budget is paced; whatever has already gone on recurring
     *  payees is simply passed through. Without this, rent paid on day 1 sits above a
     *  day-proportional line for most of the month and the gate raises a dialog on nearly
     *  every payment — measured at days 1 to 22 on real data. A prompt that always fires is
     *  one people learn to tap through, which spends the exact attention it exists to buy. */
    fun paceAllowancePaise(
        budgetPaise: Long,
        fixedPaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Long {
        val variableBudget = (budgetPaise - fixedPaise).coerceAtLeast(0L)
        return fixedPaise + (variableBudget.toDouble() * dayOfMonth / daysInMonth * PACE_GRACE).toLong()
    }

    fun evaluate(
        spentThisMonthPaise: Long,
        monthBudgetPaise: Long?,
        candidatePaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Severity = evaluate(spentThisMonthPaise, monthBudgetPaise, candidatePaise, 0L, dayOfMonth, daysInMonth)

    fun evaluate(
        spentThisMonthPaise: Long,
        monthBudgetPaise: Long?,
        candidatePaise: Long,
        fixedPaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Severity {
        if (monthBudgetPaise == null || monthBudgetPaise <= 0) return Severity.OK
        val afterPayment = spentThisMonthPaise + candidatePaise
        // BREACH is untouched by the fixed term: over the budget is over the budget, however
        // the money was committed.
        if (afterPayment > monthBudgetPaise) return Severity.BREACH
        return if (afterPayment > paceAllowancePaise(monthBudgetPaise, fixedPaise, dayOfMonth, daysInMonth))
            Severity.PACE_WARNING
        else Severity.OK
    }
}
```

- [ ] **Step 5: Run the full JVM suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. The two-arg overloads keep every existing caller compiling, so
only the tests changed above should move. Count results rather than trusting the exit code.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/stats/PaceProjector.kt \
        app/src/main/java/com/expensegarden/app/gate/GateEvaluator.kt \
        app/src/test/java/com/expensegarden/app/stats/PaceProjectorTest.kt \
        app/src/test/java/com/expensegarden/app/gate/GateEvaluatorTest.kt
git commit -m "fix: exclude fixed monthly costs from the pace projection and the gate"
```

---

## Task 3: Feed the fixed total through every caller

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/stats/MonthStatsFolder.kt`
- Modify: `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt`
- Test: `app/src/test/java/com/expensegarden/app/stats/MonthStatsFolderTest.kt`

Three call sites compute severity or projection and all three must supply the fixed total, or
the fix is only half applied.

- [ ] **Step 1: Add the failing MonthStatsFolder test**

```kotlin
    @Test fun `the projection and the overall severity both exclude fixed costs`() {
        // One assertion covering the two numbers the dashboard shows and the strip echoes.
        val cats = listOf(CategoryEntity(1, "Food & Drinks", null, false, updatedAt = 1L))
        val budgets = listOf(BudgetEntity(categoryId = null, month = "2026-09", amountPaise = 4_200_000L, updatedAt = 1L))
        val stats = MonthStatsFolder.fold(
            categories = cats,
            leafSums = mapOf(1L to 3_306_100L),
            budgets = budgets,
            fixedPaise = 2_617_400L,
            dayOfMonth = 6,
            daysInMonth = 30,
        )
        assertEquals(6_060_900L, stats.projectedPaise)
        assertEquals(Severity.OK, stats.overallSeverity)
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.stats.MonthStatsFolderTest'
```

Expected: FAIL, `No value passed for parameter 'fixedPaise'` at the new call.

- [ ] **Step 3: Thread it through MonthStatsFolder**

Add `fixedPaise: Long` as a parameter of `fold`, **after `budgets` and before `dayOfMonth`**, with
no default value — a default is exactly how a call site would silently keep the old behaviour.
Then:

```kotlin
        fun stateSeverity(spent: Long, budget: Long?): Severity =
            GateEvaluator.evaluate(spent, budget, 0L, fixedPaise, dayOfMonth, daysInMonth)
```

and

```kotlin
            projectedPaise = PaceProjector.projectedMonthEndPaise(total, fixedPaise, dayOfMonth, daysInMonth),
```

Leave the per-category `rowFor` severities alone. `fixedPaise` is an overall-scope quantity;
applying it to a category row would need that category's own fixed share, which is a different
computation and not what the review found.

**Update every existing call in `MonthStatsFolderTest` to pass `fixedPaise = 0L`.** Those tests
assert the unchanged linear behaviour and must keep doing so.

- [ ] **Step 4: Supply it from DashboardViewModel**

The window needed is the two complete months before this one, plus this month. `stats` already
combines three flows; add a fourth.

```kotlin
    val stats: StateFlow<MonthStats?> =
        flow {
            val monthKey = ledger.currentMonthKey()
            val (from, to) = ledger.boundsOfMonth(monthKey)
            // Look-back window for recurring detection: the complete months before this one.
            val lookBackFrom = ledger.boundsOfMonth(
                YearMonth.parse(monthKey).minusMonths(RecurringPayees.LOOK_BACK_MONTHS.toLong()).toString()
            ).first
            emitAll(
                combine(
                    container.db.categoryDao().observeAll(),
                    container.db.transactionDao().observeLoggedSumsByCategory(from, to),
                    container.db.budgetDao().observeAllForMonth(monthKey),
                    container.db.transactionDao().observeLoggedBetween(lookBackFrom, to),
                ) { cats, sums, budgets, window ->
                    val (day, days) = ledger.today()
                    val recurring = RecurringPayees.detect(window, YearMonth.parse(monthKey), zone)
                    val fixed = RecurringPayees.fixedSpentPaise(window.filter { it.occurredAt in from..to }, recurring)
                    MonthStatsFolder.fold(
                        cats, sums.associate { it.categoryId to it.totalPaise }, budgets, fixed, day, days,
                    )
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
```

Add `private val zone: ZoneId = ZoneId.systemDefault()` to the class and import `YearMonth`,
`ZoneId` and `RecurringPayees`.

- [ ] **Step 5: Supply it from the gate**

In `LedgerRepository.evaluateGate`, compute the fixed total for the transaction's own month —
backdating already evaluates against `occurredAt`'s month, and the fixed total must follow it:

```kotlin
    /** This month's spend on recurring payees — rent, utilities, subscriptions, the SIP.
     *  Excluded from the pace line so a day-1 rent payment does not leave the gate firing on
     *  every purchase for three weeks (review, 2026-09-06). */
    suspend fun fixedSpentPaise(monthKey: String): Long {
        val ym = YearMonth.parse(monthKey)
        val lookBackFrom = boundsOfMonth(ym.minusMonths(RecurringPayees.LOOK_BACK_MONTHS.toLong()).toString()).first
        val (from, to) = boundsOfMonth(monthKey)
        val window = db.transactionDao().loggedBetween(lookBackFrom, to)
        val recurring = RecurringPayees.detect(window, ym, zone)
        return RecurringPayees.fixedSpentPaise(window.filter { it.occurredAt in from..to }, recurring)
    }

    suspend fun evaluateGate(categoryId: Long, amountPaise: Long, occurredAt: Long): GateVerdict {
        val (day, days) = dayAndLengthOf(occurredAt)
        return GateAggregator.aggregate(
            scopeInputs(categoryId, occurredAt), amountPaise,
            fixedSpentPaise(monthKeyOf(occurredAt)), day, days,
        )
    }
```

`GateAggregator.aggregate` gains a `fixedPaise: Long` parameter (no default) and passes it to
`GateEvaluator.evaluate`. **Only the overall scope (`depth == 0`) gets the fixed term** — a
category budget's pace is about that category, and rent is not inside Shopping:

```kotlin
    fun aggregate(
        scopes: List<ScopeInput>,
        candidatePaise: Long,
        fixedPaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): GateVerdict {
        val evaluated = scopes.map { scope ->
            // Fixed costs are an overall-scope quantity. Applying rent's exclusion to a
            // Shopping budget would hand that budget an allowance it has not earned.
            val fixedForScope = if (scope.categoryId == null) fixedPaise else 0L
            scope to GateEvaluator.evaluate(
                scope.spentPaise, scope.budgetPaise, candidatePaise, fixedForScope, dayOfMonth, daysInMonth,
            )
        }
        ...
    }
```

Update `GateAggregatorTest`'s existing calls to pass `fixedPaise = 0L`, and
`MainViewModel.homeHeader`'s `GateEvaluator.evaluate(...)` — the home strip must agree with the
dashboard, so give it the same fixed total via a new `ledger.fixedSpentPaise(monthKey)` read
inside its `flow { }`.

- [ ] **Step 6: Supply it from GardenFolder**

`foldAllTime` already holds every LOGGED transaction, so it needs no query:

```kotlin
        val recurring = RecurringPayees.detect(ordered, ym, zone)
        val fixedThisMonth = RecurringPayees.fixedSpentPaise(currentTxns, recurring)
        val severity = MonthStatsFolder.fold(
            categories, leafSums, currentBudgets, fixedThisMonth, today.dayOfMonth, daysInMonth,
        ).overallSeverity
```

Do the same in `fold` (the monthly path), passing the month's own transactions. The weather the
garden shows must match the severity the dashboard shows, or the app contradicts itself.

- [ ] **Step 7: Run everything**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest && ./gradlew connectedDebugAndroidTest
```

Expected: 0 failures on both. Instrumented `GateScopeTest` exercises the gate against a real
database and is the one that would catch a mis-threaded parameter.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/stats/MonthStatsFolder.kt \
        app/src/main/java/com/expensegarden/app/gate/GateAggregator.kt \
        app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt \
        app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt \
        app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt \
        app/src/main/java/com/expensegarden/app/game/GardenFolder.kt \
        app/src/test/java/com/expensegarden/app/
git commit -m "fix: thread the fixed-cost total through every pace call site"
```

---

## Task 4: Format money for India

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/core/Money.kt`
- Test: `app/src/test/java/com/expensegarden/app/core/MoneyTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
    // ---------- display formatting (review fix, 2026-09-06) ----------

    @Test fun `display uses Indian digit grouping`() {
        // Lakh grouping is 2-2-3, not thousands. Locale.US rendered this as 165305.00.
        assertEquals("₹1,65,305", Money.display(1_65_305_00L))
        assertEquals("₹33,061", Money.display(33_061_00L))
        assertEquals("₹9,000", Money.display(9_000_00L))
    }

    @Test fun `display drops paise when the amount is whole rupees`() =
        assertEquals("₹450", Money.display(45_000L))

    @Test fun `display keeps paise when they are non-zero`() =
        assertEquals("₹450.50", Money.display(45_050L))

    @Test fun `display handles zero and small amounts`() {
        assertEquals("₹0", Money.display(0L))
        assertEquals("₹0.05", Money.display(5L))
        assertEquals("₹29", Money.display(2_900L))
    }

    @Test fun `display handles a crore without losing a group`() =
        assertEquals("₹1,00,00,000", Money.display(1_00_00_000_00L))

    @Test fun `intentAmount is unchanged — NPCI wants two decimals and no grouping`() {
        // Load-bearing: this string goes into the upi:// URI. Grouping it would break payment.
        assertEquals("165305.00", Money.intentAmount(1_65_305_00L))
        assertEquals("450.50", Money.intentAmount(45_050L))
        assertEquals("0.05", Money.intentAmount(5L))
    }
```

- [ ] **Step 2: Run to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.core.MoneyTest'
```

Expected: FAIL — `expected:<₹1,65,305> but was:<₹165305.00>`.

- [ ] **Step 3: Implement**

```kotlin
package com.expensegarden.app.core

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Money {
    private val INDIA = Locale("en", "IN")

    /** Lakh grouping: #,##,##0 gives 1,65,305 rather than 165,305. Android's ICU has this
     *  built in for en-IN, but the pattern is written out so the behaviour cannot change
     *  under a platform update. */
    private val WHOLE = DecimalFormat("#,##,##0", DecimalFormatSymbols(INDIA))
    private val WITH_PAISE = DecimalFormat("#,##,##0.00", DecimalFormatSymbols(INDIA))

    /** "450.50" -> 45050 paise. Null on garbage, zero, negative, or sub-paise precision. */
    fun parseToPaise(input: String): Long? {
        val value = input.trim().toBigDecimalOrNull() ?: return null
        if (value <= BigDecimal.ZERO) return null
        return try {
            value.movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** For humans. Indian grouping, and paise only when there are any — every amount in this
     *  app used to read "₹33061.00", which is neither how the number is grouped here nor a
     *  precision anyone wants on a rent payment. */
    fun display(paise: Long): String {
        val rupees = paise / 100
        val remainder = paise % 100
        return if (remainder == 0L) "₹" + WHOLE.format(rupees)
        else "₹" + WITH_PAISE.format(paise / 100.0)
    }

    /** NPCI intent `am` param format: strictly two decimals, no grouping. Never route this
     *  through [display] — a grouped amount in a upi:// URI is a broken payment. */
    fun intentAmount(paise: Long): String =
        String.format(Locale.US, "%d.%02d", paise / 100, paise % 100)
}
```

- [ ] **Step 4: Run the full suite and commit**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
git add app/src/main/java/com/expensegarden/app/core/Money.kt \
        app/src/test/java/com/expensegarden/app/core/MoneyTest.kt
git commit -m "fix: format money with Indian digit grouping and no dead paise"
```

Watch for tests elsewhere that assert on formatted strings — `DigestWriterTest` and
`PromptFactsTest` render rupees into prompts. `PromptFacts.render` uses its own
`"₹${spentPaise / 100}"` and is deliberately untouched: prompt text is not UI, and changing it
would alter every digest the model has already been trained on this session.

---

## Task 5: The three UI-glue fixes

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt`

No tests: these are Compose-layer changes with no return value, verified by eye in Task 6. Do
not invent a screenshot harness for them.

- [ ] **Step 1: Keep the dashboard affordance visible**

In `GardenHomeScreen`, the trailing label currently reads `"dashboard →"` **only when no budget
is set**, so the one hint that the strip is tappable disappears for every established user.
Keep a persistent chevron:

```kotlin
                    Text(
                        (h.overallBudgetPaise?.let { "${Money.display(it)} · ${gardenHint(h.hint)}" }
                            ?: "set a budget") + streakSuffix + "  ›",
                        style = MaterialTheme.typography.labelMedium,
                    )
```

- [ ] **Step 2: Un-freeze the chip window**

`chipCategories` evaluates `System.currentTimeMillis()` once, at ViewModel construction — the
same staleness class already fixed twice in this file with `flow { }` wrappers, and missed
here. Wrap it the same way:

```kotlin
    val chipCategories: StateFlow<List<CategoryEntity>> =
        flow {
            // Re-derived on every (re)subscription, like homeHeader above. A window frozen at
            // property init drifts on a long-lived process — the defect the spec §5 fix
            // addressed for month bounds, which this property was missed by.
            val since = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            emitAll(
                combine(
                    container.db.categoryDao().observeAll(),
                    container.db.transactionDao().observeCategoryUsageSince(since),
                ) { cats, usage ->
                    ChipOrder.topChips(cats, usage.associate { it.categoryId to it.uses })
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

- [ ] **Step 3: Give the entry screen a way back, inline errors and focus**

Add a `← garden` row above the title, matching the other three screens — the comment there
about gesture-nav phones hiding the system affordance applies here too, and Entry is the only
screen without one. `EntryScreen` gains nothing new in its signature: `onDone` already pops.

```kotlin
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← garden") }
        }
```

Replace both validation Toasts with inline field state. A Toast appears at the far bottom of
the screen, vanishes, is invisible to TalkBack in the way an error field is not, and surfaces
only one problem per attempt:

```kotlin
    var amountError by remember { mutableStateOf(false) }
    var categoryError by remember { mutableStateOf(false) }
```

Set `isError = amountError` and a `supportingText` of `"Enter an amount"` on the amount field;
clear it in `onValueChange`. Show `"Pick a category"` under the chip row when `categoryError`.
In the button's `onClick`, set both flags in one pass so a user learns about both at once
rather than discovering the second after fixing the first.

Autofocus the amount field — it is the first input of the app's primary action:

```kotlin
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { amountFocus.requestFocus() }
```

with `.focusRequester(amountFocus)` on the amount `OutlinedTextField`.

- [ ] **Step 4: Build, then commit**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug && ./gradlew testDebugUnitTest
git add app/src/main/java/com/expensegarden/app/ui/
git commit -m "fix: keep the dashboard affordance, unfreeze the chip window, mend entry UX"
```

---

## Task 6: Verify against the seeded data

- [ ] **Step 1: Full suites**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

Expected: 0 failures. JVM count should be 270 + ~19 new.

- [ ] **Step 2: Install and look**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
~/Library/Android/sdk/platform-tools/adb shell pm list packages | grep expensegarden
~/Library/Android/sdk/platform-tools/adb shell am start -n com.expensegarden.app/.MainActivity
```

The seeded emulator data (83 transactions, rent on day 1) is the fixture. Confirm on the
dashboard:

- **Projected** reads roughly **₹60,000**, not ₹1,65,305.
- Amounts read **₹33,061** and **₹1,65,305**-style grouping, with no `.00` on whole rupees.
- The home strip carries a trailing `›`.

- [ ] **Step 3: Report and stop**

Do not push. Do not commit the spec or this plan.

---

## Deferred

- **Predicting fixed costs not yet paid.** A rent payment due on day 28 is absent from the
  projection until it lands. Under-counting quietly beats over-counting by 5x, and it needs an
  amount guess to fix.
- **Per-category fixed shares.** `fixedPaise` is overall-scope only; a Housing budget still
  paces its own rent. Correct for the review's finding, and a category budget for a purely
  fixed category is a different conversation.
- **Sprite memory** (57 MB decoded, main-thread). Measured, not addressed — gated on whether
  Play Store distribution becomes real.
- **`GardenCanvas.kt`** at 1,041 lines. Documented, deliberate, stable.
- **Collection album's 13 identical `???` rows.** Cosmetic; a per-tier count and silhouette
  grid is a design task, not a fix.
