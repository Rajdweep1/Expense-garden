# The Gate Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the pre-payment gate stop shaming necessities, preview what the purchase actually costs, and put the emphasis on backing out.

**Architecture:** All branching moves into `gate/GatePresentation.kt` — pure Kotlin, no Android imports — which maps a severity plus a necessity flag to a sealed `GateView` of `Weed` / `Streak` / `Neutral` / `None`. The dialog becomes a dumb renderer. The streak the pace case prints is made trustworthy first, by teaching `StreakMath` when the ledger actually began.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 1.3.0), Room, JUnit 4. **No new dependencies.**

**Spec:** `docs/superpowers/specs/2026-09-17-gate-redesign-design.md`

**Every Gradle command needs JDK 17.** Not Android Studio's bundled JBR — it auto-updated to
JDK 25 and Kotlin 2.0.20 throws `IllegalArgumentException: 25.0.3` before compiling anything.
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
```

---

## File Structure

| File | Responsibility |
|---|---|
| `game/StreakBaseline.kt` *(new)* | Pure: turns an install timestamp and the earliest ledger event into a day-of-month floor. |
| `game/StreakMath.kt` | Gains `firstObservedDay`; days before it never count. |
| `game/GardenFolder.kt` | Threads `firstObservedDay` into both folds. Defaulted, so every existing caller is untouched. |
| `data/GardenPrefs.kt` | Stores the device's first-run timestamp. |
| `data/GardenRepository.kt` | Computes the baseline, passes it to the folds, and exposes `currentUnderPaceStreak()`. |
| `gate/GatePresentation.kt` *(new)* | The sealed `GateView` and the single function that decides which one applies. |
| `data/LedgerRepository.kt` | `save` accepts a caller-supplied UUID so the gate can preview the exact weed. |
| `ui/MainViewModel.kt` | Mints the draft UUID; `prepareGateView()` assembles a `GateView`. |
| `ui/EntryScreen.kt` | A custom `Dialog` replacing `AlertDialog`. |

**Ordering rule:** every task below leaves the tree compiling. Task 7 deliberately adds
`prepareGateView()` *beside* the existing `prepareGate`, and Task 8 deletes the old one once its
last caller is gone. Deleting it in Task 7 would break the build.

---

## Task 1: StreakMath learns when the ledger began

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/StreakMath.kt`
- Test: `app/src/test/java/com/expensegarden/app/game/StreakMathTest.kt`

- [ ] **Step 1: Write the failing tests**

Append these four tests inside `StreakMathTest` (keep the four that are already there — they pin
the existing behaviour and must keep passing):

```kotlin
    @Test fun `days before the ledger began never count`() {
        // Installed on day 10 of a 30-day month, today is 13, nothing logged. Days 1..9 were never
        // observed and cannot count — but 10, 11 and 12 were, and were under pace, so 3 is earned.
        assertEquals(3, StreakMath.underPaceStreak(emptyMap(), budget, today = 13, daysInMonth = 30, firstObservedDay = 10))
    }

    @Test fun `installing today earns nothing yet`() {
        // The bug this parameter exists for: a fresh install on the 13th used to report 12 days,
        // which is enough to fire STREAK_7 and grow a rare the user never earned.
        assertEquals(0, StreakMath.underPaceStreak(emptyMap(), budget, today = 13, daysInMonth = 30, firstObservedDay = 13))
    }

    @Test fun `a genuine no-spend run from day one is still earned`() {
        // Installed on day 1, spent nothing through day 19. That streak was earned.
        assertEquals(19, StreakMath.underPaceStreak(emptyMap(), budget, today = 20, daysInMonth = 30, firstObservedDay = 1))
    }

    @Test fun `the baseline only trims the start, it does not rescue a breach`() {
        // Observed from day 2; day 3 blows the allowance, so only day 4 survives.
        val totals = mapOf(3 to 40_000L)
        assertEquals(1, StreakMath.underPaceStreak(totals, budget, today = 5, daysInMonth = 30, firstObservedDay = 2))
    }
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest --tests '*StreakMathTest*'
```

Expected: FAIL — `No value passed for parameter 'firstObservedDay'` at compile time.

- [ ] **Step 3: Add the parameter**

Replace `underPaceStreak` in `app/src/main/java/com/expensegarden/app/game/StreakMath.kt`:

```kotlin
    /** Consecutive days ending yesterday whose cumulative spend stayed ≤ that day's pace allowance.
     *
     *  @param firstObservedDay the first day of this month the ledger actually covers. Days before
     *   it are *unknown*, not *under pace* — without this, a fresh install on the 13th is handed a
     *   12-day streak it never earned, which is both a cosmetic lie on the home strip and enough
     *   to fire STREAK_7 and grow a rare. Defaults to 1, which is the whole month and therefore
     *   the previous behaviour exactly. */
    fun underPaceStreak(
        dayTotalsPaise: Map<Int, Long>,
        budgetPaise: Long?,
        today: Int,
        daysInMonth: Int,
        firstObservedDay: Int = 1,
    ): Int {
        if (budgetPaise == null || budgetPaise <= 0) return 0
        var cumulative = 0L
        val underByDay = (1 until today).map { day ->
            cumulative += dayTotalsPaise[day] ?: 0L
            // Cumulative spend still accrues across unobserved days — a restored ledger may hold
            // transactions there — but such a day can never itself extend the streak.
            day >= firstObservedDay && cumulative <= GateEvaluator.paceAllowancePaise(budgetPaise, day, daysInMonth)
        }
        return underByDay.takeLastWhile { it }.size
    }
```

- [ ] **Step 4: Run them and watch them pass**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest --tests '*StreakMathTest*'
```

Expected: `BUILD SUCCESSFUL`, 8 tests passed (4 existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/StreakMath.kt app/src/test/java/com/expensegarden/app/game/StreakMathTest.kt
git commit -m "fix: stop crediting a streak for days before the ledger existed"
```

---

## Task 2: Where the baseline comes from

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/game/StreakBaseline.kt`
- Test: `app/src/test/java/com/expensegarden/app/game/StreakBaselineTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/game/StreakBaselineTest.kt`:

```kotlin
package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class StreakBaselineTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val september = YearMonth.of(2026, 9)

    private fun millisOf(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `a fresh install mid-month starts at that day`() {
        val installed = millisOf(2026, 9, 10)
        assertEquals(10, StreakBaseline.firstObservedDay(installed, null, september, zone))
    }

    @Test
    fun `an install before this month covers the whole month`() {
        val installed = millisOf(2026, 8, 3)
        assertEquals(1, StreakBaseline.firstObservedDay(installed, null, september, zone))
    }

    @Test
    fun `a restored ledger older than this install wins`() {
        // New phone installed on the 25th, but the restored history reaches back to July.
        val installed = millisOf(2026, 9, 25)
        val earliestEvent = millisOf(2026, 7, 4)
        assertEquals(1, StreakBaseline.firstObservedDay(installed, earliestEvent, september, zone))
    }

    @Test
    fun `the earlier of the two always wins within the month`() {
        val installed = millisOf(2026, 9, 12)
        val earliestEvent = millisOf(2026, 9, 5)
        assertEquals(5, StreakBaseline.firstObservedDay(installed, earliestEvent, september, zone))
    }

    @Test
    fun `an unrecorded install falls back to the ledger alone`() {
        val earliestEvent = millisOf(2026, 9, 7)
        assertEquals(7, StreakBaseline.firstObservedDay(0L, earliestEvent, september, zone))
    }

    @Test
    fun `knowing nothing at all covers the whole month`() {
        // Neither recorded: assume the month is fully observed rather than inventing a floor.
        assertEquals(1, StreakBaseline.firstObservedDay(0L, null, september, zone))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest --tests '*StreakBaselineTest*'
```

Expected: FAIL — `Unresolved reference: StreakBaseline`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/expensegarden/app/game/StreakBaseline.kt`:

```kotlin
package com.expensegarden.app.game

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * The first day of a month the ledger actually covers.
 *
 * Neither source is sufficient alone, which is why this takes both:
 *
 * - The **device's first run** alone truncates a restored phone. Install on the 25th with a year
 *   of synced history and the streak would reset to nothing.
 * - The **earliest ledger event** alone destroys a real no-spend run. Install on the 1st, spend
 *   nothing until the 20th, and the first event is the 20th — erasing nineteen days that were
 *   genuinely earned.
 *
 * So it is the earlier of the two, clamped into the month being folded.
 */
object StreakBaseline {

    /**
     * @param installedAtMillis device first-run timestamp; 0 when never recorded.
     * @param earliestEventMillis createdAt of the oldest game_event; null when the ledger is empty.
     * @return a 1-based day of `month`. 1 means "the whole month is observed".
     */
    fun firstObservedDay(
        installedAtMillis: Long,
        earliestEventMillis: Long?,
        month: YearMonth,
        zone: ZoneId,
    ): Int {
        val candidates = listOfNotNull(
            installedAtMillis.takeIf { it > 0L },
            earliestEventMillis,
        )
        // Nothing recorded: treat the month as fully observed rather than inventing a floor that
        // would silently suppress a streak.
        val earliest = candidates.minOrNull() ?: return 1
        val date = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate()
        return when {
            YearMonth.from(date) < month -> 1          // began before this month — all of it counts
            YearMonth.from(date) > month -> 1          // clock skew; do not suppress a real streak
            else -> date.dayOfMonth
        }
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest --tests '*StreakBaselineTest*'
```

Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Record the device's first run**

In `app/src/main/java/com/expensegarden/app/data/GardenPrefs.kt`, add a second property inside the
class, after `lastSeenHouseLevel`:

```kotlin
    /** Device first-run wall clock, 0 until recorded. Half of the streak baseline (the other half
     *  is the oldest game_event) — see StreakBaseline for why neither alone is enough.
     *
     *  Wall clock is correct here despite the sync-cursor rule: this is not a cursor and never
     *  orders anything. It answers "roughly when did this device start watching", and is compared
     *  only against a day boundary. */
    var firstObservedAt: Long
        get() = prefs.getLong(KEY_FIRST_OBSERVED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_FIRST_OBSERVED_AT, value).apply()

    /** Stamps the first run exactly once; later calls are no-ops. */
    fun recordFirstRunIfAbsent(nowMillis: Long) {
        if (firstObservedAt == 0L) firstObservedAt = nowMillis
    }
```

and add the key to the companion:

```kotlin
        const val KEY_FIRST_OBSERVED_AT = "firstObservedAt"
```

- [ ] **Step 6: Build**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/StreakBaseline.kt app/src/test/java/com/expensegarden/app/game/StreakBaselineTest.kt app/src/main/java/com/expensegarden/app/data/GardenPrefs.kt
git commit -m "feat: derive when a device's ledger actually began"
```

---

## Task 3: Thread the baseline into the folds

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt:18-26, 69, 80-94, 170`
- Modify: `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt:38, 57, 163, 205`
- Modify: `app/src/main/java/com/expensegarden/app/GardenApp.kt`

**Why defaulted:** `fold` and `foldAllTime` have nine call sites, six of them in tests. A required
parameter would force all nine to change in one commit. `firstObservedDay: Int = 1` is the existing
behaviour exactly, so every untouched caller keeps passing.

- [ ] **Step 1: Add the parameter to both folds**

In `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt`, add to `fold`'s parameter list,
after `zone: ZoneId,`:

```kotlin
        /** First day of this month the ledger covers; 1 = all of it. See StreakBaseline. */
        firstObservedDay: Int = 1,
```

Add the identical parameter to `foldAllTime`, after `rareSignals: List<RareSignal> = emptyList(),`.

Then pass it at both streak call sites. Line 69 becomes:

```kotlin
            streakDays = StreakMath.underPaceStreak(dayTotals, overall, streakToday, daysInMonth, firstObservedDay),
```

Line 170 becomes:

```kotlin
            streakDays = StreakMath.underPaceStreak(dayTotals, overall, today.dayOfMonth, daysInMonth, firstObservedDay),
```

**Correction, found during execution.** An earlier draft of this plan said to leave `noSpendDays`
alone, reasoning that "a no-spend day is a claim about the ledger being empty, which is true
whether or not the device was watching". That is wrong: a day before the ledger existed is
*unobserved*, not *no-spend*. It is the same bug, and it is the one that actually breaks
`RareDeterminismTest` — those tests set no budget, so `underPaceStreak` returns 0 regardless and
the streak was never involved. `NO_SPEND_DAYS_FOR_EARN = 7` fires off a 16-day "run" over days
nobody was watching.

So both `noSpendDays` call sites take `firstObservedDay` too:

```kotlin
            noSpendDays = StreakMath.noSpendDays(dayTotals, streakToday, firstObservedDay),
```
```kotlin
            noSpendDays = StreakMath.noSpendDays(dayTotals, today.dayOfMonth, firstObservedDay),
```

and `StreakMath.noSpendDays` gains the same defaulted parameter:

```kotlin
    /** @param firstObservedDay see [underPaceStreak] — an unobserved day is not a no-spend day. */
    fun noSpendDays(dayTotalsPaise: Map<Int, Long>, today: Int, firstObservedDay: Int = 1): Int =
        (maxOf(1, firstObservedDay) until today).count { (dayTotalsPaise[it] ?: 0L) == 0L }
```

**And `GardenFolder.noSpendByMonth` — the one that actually earns the rare — takes it as well**,
applied to the current month only (a past month appears in that map only because it holds
transactions, so it needs no floor):

```kotlin
                val from = if (month == currentMonth) maxOf(1, firstObservedDay) else 1
                for (day in from..elapsed) {
```

with its call site becoming `noSpendByMonth(ordered, today, zone, firstObservedDay)`.

**Known remaining edge, deliberately not fixed:** if the install month later becomes a *past*
month, its pre-install days still count toward that month's no-spend run. Closing that needs a
date-aware floor per month rather than a single day-of-month, and the rare is earned once and
recorded, so it is not worth the signature churn here.

- [ ] **Step 2: Build and confirm nothing else broke**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, **336 tests** — 324 at the start of this plan, +4 from Task 1, +6 from Task 2, and +2
from the noSpend correction below (one in `StreakMathTest`, one in `RareFoldTest`). If the count differs, stop and report rather than adjusting the number.

- [ ] **Step 3: Compute the baseline in GardenRepository**

In `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt`, add a private helper next to
the other suspend helpers:

```kotlin
    /** The streak baseline for `month`, from the device's first run and the oldest ledger event. */
    private suspend fun firstObservedDayOf(month: YearMonth): Int =
        StreakBaseline.firstObservedDay(
            installedAtMillis = prefs.firstObservedAt,
            earliestEventMillis = db.gameEventDao().earliestCreatedAt(),
            month = month,
            zone = zone,
        )
```

Add the DAO query to `GameEventDao` in `app/src/main/java/com/expensegarden/app/data/Daos.kt`:

```kotlin
    @Query("SELECT MIN(createdAt) FROM game_event")
    suspend fun earliestCreatedAt(): Long?
```

**Two instrumented tests construct `GardenRepository` directly** and will stop compiling — the
constructor is not defaulted the way the fold parameters are. `RareDeterminismTest` and
`GardenRepositoryTest` both need a third argument; pass a `GardenPrefs(ctx)` with
`firstObservedAt` pinned to `System.currentTimeMillis()`, since their fixtures are all built at
"now" and a pinned baseline keeps them deterministic instead of inheriting whatever the device
recorded.

`GardenRepository` needs a `GardenPrefs` to read. Change its constructor
(`GardenRepository.kt:24`) to:

```kotlin
class GardenRepository(
    private val db: AppDatabase,
    private val ledger: LedgerRepository,
    private val prefs: GardenPrefs,
) {
```

**Then fix the declaration order in `AppContainer`, or this is null at runtime.**
`app/src/main/java/com/expensegarden/app/GardenApp.kt` currently declares
`val garden` at line 53 and `val prefs: GardenPrefs` at line 55. Kotlin initialises properties in
declaration order — the file's own comment above `syncPrefs` calls out exactly this hazard — so
`GardenRepository(db, ledger, prefs)` at line 53 would receive a null.

Move `val prefs: GardenPrefs = GardenPrefs(app)` **above** `val garden`, and add it to the existing
ordering comment's list. The call site then reads:

```kotlin
    val prefs: GardenPrefs = GardenPrefs(app)
    val garden: GardenRepository = GardenRepository(db, ledger, prefs)
```

- [ ] **Step 4: Pass it at all four sites**

`GardenRepository.kt:38` — inside the existing month-observing flow:

```kotlin
            GardenFolder.fold(monthKey, txns, cats, budgets, events, sips, LocalDate.now(zone), zone, firstObservedDayOf(YearMonth.parse(monthKey)))
```

`GardenRepository.kt:57` (`foldAllTime`) and `:163` (`fold`) take the same argument, using
`YearMonth.now(zone)` for the all-time fold since its streak is about the current month.

`GardenRepository.kt:205`, inside `runReconciler`:

```kotlin
        val streak = StreakMath.underPaceStreak(dayTotals, overall, today.dayOfMonth, nowMonth.lengthOfMonth(), firstObservedDayOf(nowMonth))
```

- [ ] **Step 5: Stamp the first run on launch**

In `app/src/main/java/com/expensegarden/app/GardenApp.kt`, inside `AppContainer`, add an `init`
block **after** the `prefs` declaration so the timestamp exists from the first launch onward:

```kotlin
    init {
        // Stamped once, on the first launch this build ever sees. For a device upgrading from an
        // older build this is "now", which is why StreakBaseline also consults the oldest
        // game_event - for an existing user that is the correct answer anyway.
        prefs.recordFirstRunIfAbsent(System.currentTimeMillis())
    }
```

- [ ] **Step 6: Build and test**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 336 tests, 0 failures.

- [ ] **Step 7: Confirm the flaky instrumented tests are fixed**

This is the real proof the baseline works — those two tests fail on any date from the 8th onward
*because* of the bug this task fixes.

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.RareDeterminismTest
```

Expected: 6/6 pass. Before this task, `two_dodges_grow_no_rare` and
`re_tagging_a_regret_repeatedly_still_grows_only_one_rare` fail whenever the device date is on or
after the 8th. **If they still fail, stop and report** — the baseline is not reaching the fold.

Then reinstall, since the instrumented run uninstalls both APKs:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew installDebug
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/GardenFolder.kt app/src/main/java/com/expensegarden/app/data/GardenRepository.kt app/src/main/java/com/expensegarden/app/data/Daos.kt app/src/main/java/com/expensegarden/app/GardenApp.kt
git commit -m "fix: fold the streak from the day the ledger actually began"
```

---

## Task 4: GatePresentation

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/gate/GatePresentation.kt`
- Test: `app/src/test/java/com/expensegarden/app/gate/GatePresentationTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/expensegarden/app/gate/GatePresentationTest.kt`:

```kotlin
package com.expensegarden.app.gate

import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.PlantMapper
import com.expensegarden.app.stats.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePresentationTest {

    private fun view(
        severity: Severity,
        isNecessity: Boolean,
        streakDays: Int = 9,
        uuid: String = "fixed-uuid-for-tests",
    ) = GatePresentation.of(
        severity = severity,
        isNecessity = isNecessity,
        streakDays = streakDays,
        txnUuid = uuid,
        quip = "Budget says no. You say...?",
        scopeLabel = "Shopping",
        spentPaise = 400_000L,
        budgetPaise = 500_000L,
        candidatePaise = 224_000L,
        allowancePaise = 12_000L,
    )

    @Test
    fun `an OK verdict never shows a dialog`() {
        assertEquals(GateView.None, view(Severity.OK, isNecessity = false))
        assertEquals(GateView.None, view(Severity.OK, isNecessity = true))
    }

    @Test
    fun `a discretionary breach previews the weed`() {
        val v = view(Severity.BREACH, isNecessity = false)
        assertTrue(v is GateView.Weed)
        v as GateView.Weed
        assertTrue(v.archetype == Archetype.THISTLE_WEED || v.archetype == Archetype.ODD_MUSHROOM)
        assertEquals(0, v.variant)
    }

    @Test
    fun `the previewed weed is the one PlantMapper would actually grow`() {
        // Calls PlantMapper for real rather than re-deriving abs(seed) % 2 here. A copy of the
        // rule would agree with itself forever; this fails the moment the two drift, which is the
        // only thing standing between "promises a thistle" and "grows a mushroom".
        val shopping = listOf(CategoryEntity(7, "Shopping", null, false, updatedAt = 1L))
        val tree = CategoryTree(shopping)
        for (n in 1..40) {
            val uuid = "txn-$n"
            val txn = TransactionEntity(
                uuid = uuid, amountPaise = 5_000, payeeId = 1, categoryId = 7,
                source = TxnSource.QR_GATE, status = TxnStatus.LOGGED,
                breachedAtLogging = true, note = null,
                occurredAt = 1_760_000_000_000L, createdAt = 1L, updatedAt = 1L,
            )
            val grown = PlantMapper.map(txn, tree)!!
            val previewed = view(Severity.BREACH, isNecessity = false, uuid = uuid) as GateView.Weed
            assertEquals("uuid $uuid", grown.archetype, previewed.archetype)
            assertEquals("uuid $uuid", grown.variant, previewed.variant)
        }
    }

    @Test
    fun `a discretionary pace warning previews the streak`() {
        val v = view(Severity.PACE_WARNING, isNecessity = false)
        assertTrue(v is GateView.Streak)
        assertEquals(9, (v as GateView.Streak).days)
    }

    @Test
    fun `a necessity is neutral at both severities`() {
        assertTrue(view(Severity.BREACH, isNecessity = true) is GateView.Neutral)
        assertTrue(view(Severity.PACE_WARNING, isNecessity = true) is GateView.Neutral)
    }

    @Test
    fun `a necessity never carries a quip`() {
        // Spec section 10: necessities are off-limits, never mocked. Neutral has no quip field,
        // so this asserts on the data class's own toString - which names every field it has.
        // It fails the moment someone adds a quip to Neutral and populates it.
        val breach = view(Severity.BREACH, isNecessity = true)
        val pace = view(Severity.PACE_WARNING, isNecessity = true)
        assertTrue("a necessity view must not carry the quip", !breach.toString().contains("Budget says no"))
        assertTrue("a necessity view must not carry the quip", !pace.toString().contains("Budget says no"))
    }

    @Test
    fun `a necessity never offers a dodge`() {
        // GATE_DODGES earns a rare for backing out. Crediting a chemist run would make rares
        // farmable, against 4A's "earned by restraint, never by spending".
        assertEquals(false, view(Severity.BREACH, isNecessity = true).recordsDodge)
        assertEquals(false, view(Severity.PACE_WARNING, isNecessity = true).recordsDodge)
        assertEquals(true, view(Severity.BREACH, isNecessity = false).recordsDodge)
        assertEquals(true, view(Severity.PACE_WARNING, isNecessity = false).recordsDodge)
        assertEquals(false, GateView.None.recordsDodge)
    }

    @Test
    fun `the neutral card reports the total after this payment`() {
        val v = view(Severity.BREACH, isNecessity = true) as GateView.Neutral
        assertEquals(624_000L, v.afterPaise)      // 400000 spent + 224000 candidate
        assertEquals(500_000L, v.budgetPaise)
    }

    @Test
    fun `the weed card reports how far over budget this puts you`() {
        val v = view(Severity.BREACH, isNecessity = false) as GateView.Weed
        assertEquals(124_000L, v.overPaise)       // 624000 after - 500000 budget
    }

    @Test
    fun `the streak card reports the overshoot against today's allowance`() {
        val v = view(Severity.PACE_WARNING, isNecessity = false) as GateView.Streak
        assertEquals(612_000L, v.overPaise)       // 624000 after - 12000 allowance
        assertEquals(12_000L, v.allowancePaise)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest --tests '*GatePresentationTest*'
```

Expected: FAIL — `Unresolved reference: GatePresentation`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/expensegarden/app/gate/GatePresentation.kt`:

```kotlin
package com.expensegarden.app.gate

import com.expensegarden.app.game.Archetype
import kotlin.math.abs

/**
 * What the gate shows. One variant per kind of stake, because the three cases genuinely differ:
 * a breach grows a weed, a pace warning costs the streak, and a necessity costs neither.
 *
 * Pure Kotlin on purpose — every branching rule here is a product decision, and decisions belong
 * in JVM tests rather than in a Composable no test can reach.
 */
sealed interface GateView {

    /**
     * Whether backing out of this counts as restraint.
     *
     * Declared on the interface so every variant is forced to answer. GATE_DODGES earns a rare
     * for backing out, and crediting a necessity would make that farmable - open a chemist
     * payment, tap Back three times, collect. 4A's rule is "earned by restraint, never by
     * spending", and a corrected amount on a necessity is neither.
     */
    val recordsDodge: Boolean

    /** Nothing to say. The silence rule: an OK verdict never interrupts. */
    data object None : GateView {
        override val recordsDodge: Boolean get() = false
    }

    /** A breach on a discretionary purchase — this really does grow a weed. */
    data class Weed(
        val archetype: Archetype,
        val variant: Int,
        val quip: String,
        val scopeLabel: String?,
        val overPaise: Long,
    ) : GateView {
        override val recordsDodge: Boolean get() = true
    }

    /** Ahead of pace on a discretionary purchase — nothing ugly grows, but the streak ends. */
    data class Streak(
        val days: Int,
        val quip: String,
        val overPaise: Long,
        val allowancePaise: Long,
    ) : GateView {
        override val recordsDodge: Boolean get() = true
    }

    /**
     * A necessity, at either severity. No quip and no dodge, by construction rather than by
     * convention — there is no field here to put one in.
     */
    data class Neutral(
        val scopeLabel: String,
        val afterPaise: Long,
        val budgetPaise: Long,
    ) : GateView {
        override val recordsDodge: Boolean get() = false
    }
}

object GatePresentation {

    /**
     * Weeds are always variant 0 — `PlantMapper.kt:86` maps `isWeed -> 0` regardless of tier.
     */
    private const val WEED_VARIANT = 0

    /**
     * Which weed a transaction grows. Mirrors `PlantMapper.kt:74` exactly, so the preview cannot
     * promise a thistle and then grow a mushroom. Requires the UUID to exist before the gate is
     * answered, which is why the draft mints it.
     */
    fun weedFor(txnUuid: String): Archetype =
        if (abs(txnUuid.hashCode()) % 2 == 0) Archetype.THISTLE_WEED else Archetype.ODD_MUSHROOM

    @Suppress("LongParameterList")
    fun of(
        severity: Severity,
        isNecessity: Boolean,
        streakDays: Int,
        txnUuid: String,
        quip: String,
        scopeLabel: String?,
        spentPaise: Long,
        budgetPaise: Long,
        candidatePaise: Long,
        allowancePaise: Long,
    ): GateView {
        if (severity == Severity.OK) return GateView.None
        val after = spentPaise + candidatePaise

        // Necessity wins over severity. The garden already exempts these (PlantMapper gates both
        // the weed and the zombie on !ownNecessity), so the gate was threatening a punishment
        // that does not exist.
        if (isNecessity) {
            return GateView.Neutral(
                scopeLabel = scopeLabel ?: "Overall",
                afterPaise = after,
                budgetPaise = budgetPaise,
            )
        }

        return when (severity) {
            Severity.BREACH -> GateView.Weed(
                archetype = weedFor(txnUuid),
                variant = WEED_VARIANT,
                quip = quip,
                scopeLabel = scopeLabel,
                overPaise = after - budgetPaise,
            )
            Severity.PACE_WARNING -> GateView.Streak(
                days = streakDays,
                quip = quip,
                overPaise = after - allowancePaise,
                allowancePaise = allowancePaise,
            )
            Severity.OK -> GateView.None      // unreachable; the early return above covers it
        }
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest --tests '*GatePresentationTest*'
```

Expected: `BUILD SUCCESSFUL`, 10 tests passed. A full `./gradlew testDebugUnitTest` now reports
**346** (336 + 10), which is the figure every later task expects.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/gate/GatePresentation.kt app/src/test/java/com/expensegarden/app/gate/GatePresentationTest.kt
git commit -m "feat: decide what the gate shows in testable pure Kotlin"
```

---

## Task 5: A streak the gate can read

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt:192-206`

- [ ] **Step 1: Extract the computation**

`runReconciler` is currently the only place that computes today's streak, inline. Pull it into a
suspend accessor directly above `runReconciler`, and have the reconciler call it — so the
day-totals and budget lookup exists once, not twice:

```kotlin
    /** Today's under-pace streak for the live month. The gate reads this to say what a purchase
     *  costs; the reconciler reads it to decide whether a streak.hit threshold was crossed. */
    suspend fun currentUnderPaceStreak(): Int {
        val nowMonth = YearMonth.now(zone)
        val monthKey = nowMonth.toString()
        val (from, to) = ledger.boundsOfMonth(monthKey)
        val dayTotals = db.transactionDao().loggedBetween(from, to)
            .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfMonth }
            .mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val overall = db.budgetDao().allForMonth(monthKey).firstOrNull { it.categoryId == null }?.amountPaise
        return StreakMath.underPaceStreak(
            dayTotals, overall, LocalDate.now(zone).dayOfMonth, nowMonth.lengthOfMonth(),
            firstObservedDayOf(nowMonth),
        )
    }
```

Then in `runReconciler`, delete the now-duplicated `dayTotals`, `overall` and `streak` lines and
replace them with:

```kotlin
        val streak = currentUnderPaceStreak()
```

Keep `monthTxns` if anything later in `runReconciler` still uses it; delete it if nothing does.

- [ ] **Step 2: Build and test**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 347 tests, 0 failures. The reconciler's behaviour is unchanged, so
no test should move.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/GardenRepository.kt
git commit -m "refactor: expose today's streak instead of computing it inside the reconciler"
```

---

## Task 6: Mint the transaction UUID at gate time

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt:36-41, 177-178`
- Modify: `app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt:30-38`

The weed the gate previews is a function of the UUID, and the UUID is currently minted inside
`save()` — after the gate has been answered. Minting it on the draft is what makes the preview
honest rather than a guess.

- [ ] **Step 1: Let save accept a UUID**

In `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt`, change the private `save`'s
first line (line 178) from generating unconditionally to accepting an override:

```kotlin
    private suspend fun save(
        draft: Draft,
        source: TxnSource,
        status: TxnStatus,
        breached: Boolean,
        uuidOverride: String? = null,
    ): String {
        // The QR path mints this before the gate so the dialog can preview the exact weed this
        // transaction will grow. Every other caller still gets a fresh one here.
        val uuid = uuidOverride ?: UUID.randomUUID().toString()
```

and thread it through `savePending` only — the manual path has no gate and needs no override:

```kotlin
    suspend fun savePending(draft: Draft, breachedAtLogging: Boolean, uuid: String? = null): String =
        save(draft, TxnSource.QR_GATE, TxnStatus.PENDING_CONFIRM, breachedAtLogging, uuid)
```

- [ ] **Step 2: Carry it on the draft**

In `app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt`, add a field to `EntryDraft`:

```kotlin
data class EntryDraft(
    val fromScan: Boolean = false,
    val vpa: String? = null,
    val payeeName: String = "",
    val amountText: String = "",
    val categoryId: Long? = null,
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    /** Minted when a scan draft starts, so the gate can preview the exact weed this will grow.
     *  Null on the manual path, which has no gate. */
    val txnUuid: String? = null,
)
```

In `startScanDraft`, set it — find the existing function and add `txnUuid = UUID.randomUUID().toString()`
to the `EntryDraft(...)` it constructs, adding `import java.util.UUID` if absent.

Then pass it in `savePendingFromDraft`:

```kotlin
    suspend fun savePendingFromDraft(amountPaise: Long, severity: Severity): String =
        ledger.savePending(
            draft = draft.value.toRepoDraft(amountPaise),
            breachedAtLogging = severity == Severity.BREACH,
            uuid = draft.value.txnUuid,
        )
```

- [ ] **Step 3: Build and test**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 347 tests, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt
git commit -m "feat: mint the transaction id before the gate so the preview is exact"
```

---

## Task 7: Assemble the GateView

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt:116-124`

`prepareGate` stays for now — `EntryScreen` still calls it, and deleting it here would break the
build. Task 8 removes it once its last caller is gone.

- [ ] **Step 1: Add prepareGateView beside the existing prepareGate**

```kotlin
    /** Everything the dialog needs, decided in GatePresentation. OK yields None, and the caller
     *  proceeds without interrupting (the silence rule at the gate). */
    suspend fun prepareGateView(amountPaise: Long): GateView {
        val d = draft.value
        val verdict = ledger.evaluateGate(d.categoryId!!, amountPaise, d.occurredAt)
        if (verdict.severity == Severity.OK) return GateView.None

        val isNecessity = categories.value.firstOrNull { it.id == d.categoryId }?.isNecessity ?: false
        // Necessities never ask for a quip — not "ask and discard". A quip marked used here would
        // be consumed from the no-repeat rotation for a dialog that never shows it.
        val quip = if (isNecessity) "" else container.quips.pick(verdict.severity, container.aiPrefs.tone)
        val offender = verdict.offender

        return GatePresentation.of(
            severity = verdict.severity,
            isNecessity = isNecessity,
            streakDays = container.garden.currentUnderPaceStreak(),
            txnUuid = d.txnUuid ?: UUID.randomUUID().toString(),
            quip = quip,
            scopeLabel = offender?.takeIf { it.categoryId != null }?.label,
            spentPaise = offender?.spentPaise ?: 0L,
            budgetPaise = offender?.budgetPaise ?: 0L,
            candidatePaise = amountPaise,
            allowancePaise = offender?.let {
                GateEvaluator.paceAllowancePaise(it.budgetPaise, ledger.dayOfMonthFor(d.occurredAt), ledger.daysInMonthFor(d.occurredAt))
            } ?: 0L,
        )
    }
```

`GateAggregator` already returns the offending `ScopeInput`, which carries `budgetPaise` and
`spentPaise` — no new query is needed.

`ledger.dayOfMonthFor` / `daysInMonthFor` do not exist. `LedgerRepository.dayAndLengthOf(epochMillis)`
(line 118) already returns exactly this pair and is **already public** — no visibility change is
needed. Use it instead:

```kotlin
            allowancePaise = offender?.let {
                val (day, days) = ledger.dayAndLengthOf(d.occurredAt)
                GateEvaluator.paceAllowancePaise(it.budgetPaise, day, days)
            } ?: 0L,
```

Add the imports `com.expensegarden.app.gate.GatePresentation`, `com.expensegarden.app.gate.GateView`,
`com.expensegarden.app.gate.GateEvaluator` and `java.util.UUID` if absent.

- [ ] **Step 2: Build and test**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 347 tests, 0 failures. `prepareGateView` has no caller yet, which is
intentional.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt
git commit -m "feat: assemble the gate view from the verdict and the category"
```

---

## Task 8: The dialog

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt:68, 226-268`
- Modify: `app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt` (delete `prepareGate` and `GatePrompt`)

- [ ] **Step 1: Swap the state type and the call**

In `EntryScreen.kt`, change the gate state declaration (line 68) from:

```kotlin
    var gate by remember { mutableStateOf<GatePrompt?>(null) }
```

to:

```kotlin
    var gate by remember { mutableStateOf<GateView?>(null) }
```

and change the scan branch of the `Button` onClick (around line 228) from `vm.prepareGate` to:

```kotlin
                    draft.fromScan -> scope.launch {
                        val view = vm.prepareGateView(amountPaise)
                        if (view == GateView.None) fireAndFinish(amountPaise, Severity.OK)
                        else gate = view
                    }
```

`fireAndFinish` still needs a `Severity` for `breachedAtLogging`. Add a helper next to the
`GateView` declarations in `gate/GatePresentation.kt`:

```kotlin
/** Only a breach flags the transaction — that flag is what grows the weed. */
fun GateView.severityForLogging(): Severity = when (this) {
    is GateView.Weed -> Severity.BREACH
    is GateView.Streak -> Severity.PACE_WARNING
    is GateView.Neutral -> Severity.PACE_WARNING    // a necessity never grows a weed either way
    GateView.None -> Severity.OK
}
```

Note the deliberate choice on `Neutral`: `PlantMapper` already exempts necessities from weeds via
`!ownNecessity`, so the flag is inert there — but passing `PACE_WARNING` rather than `BREACH`
keeps `breachedAtLogging` meaning "this grew a weed" rather than "this was over budget", which is
what the garden actually reads it for.

- [ ] **Step 2: Replace the AlertDialog**

Replace the whole `gate?.let { prompt -> AlertDialog(...) }` block at the end of `EntryScreen` with:

```kotlin
    gate?.let { view ->
        val amountPaise = Money.parseToPaise(draft.amountText) ?: return@let
        GateDialog(
            view = view,
            onProceed = {
                val severity = view.severityForLogging()
                gate = null
                fireAndFinish(amountPaise, severity)
            },
            onBackOut = {
                if (view.recordsDodge) vm.recordDodge(amountPaise)
                gate = null
                onDone()
            },
            onDismiss = { gate = null },
        )
    }
```

`recordsDodge` is declared on the `GateView` interface itself (Task 4), so it reads straight off
the value with no extra accessor.

- [ ] **Step 3: Write the dialog**

Add at the bottom of `EntryScreen.kt`:

```kotlin
/** The gate. A custom Dialog rather than AlertDialog because the emphasis has to invert: on a
 *  discretionary purchase the quiet option is the recommended one, and Material's confirmButton
 *  slot always renders rightmost and loudest. */
@Composable
private fun GateDialog(
    view: GateView,
    onProceed: () -> Unit,
    onBackOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var sprite by remember(view) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(view) {
        sprite = (view as? GateView.Weed)?.let { weed ->
            withContext(Dispatchers.IO) { SpriteLoader.decodePlant(context, weed.archetype, weed.variant) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = if (view is GateView.Neutral) Alignment.Start else Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (view) {
                    is GateView.Weed -> {
                        // A missing sprite degrades to text: the heading already names the
                        // consequence, so an uninstalled pack must not leave a broken slot.
                        sprite?.let {
                            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(96.dp))
                        }
                        Text("This grows a weed", style = MaterialTheme.typography.titleLarge)
                        Text(view.quip, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(view.scopeLabel, "${Money.display(view.overPaise)} over").joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is GateView.Streak -> {
                        Text("This ends a ${view.days}-day streak", style = MaterialTheme.typography.titleLarge)
                        Text(view.quip, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${Money.display(view.overPaise)} over today's ${Money.display(view.allowancePaise)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is GateView.Neutral -> {
                        Text("${view.scopeLabel}, after this", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${Money.display(view.afterPaise)} of your ${Money.display(view.budgetPaise)} budget.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Nothing grows badly here — necessities never do.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GateView.None -> Unit
                }

                Spacer(Modifier.height(8.dp))

                if (view is GateView.Neutral) {
                    // A necessity should be paid. The loud button is the one that proceeds.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onBackOut) { Text("Back") }
                        Button(onClick = onProceed, modifier = Modifier.padding(start = 8.dp)) { Text("Continue") }
                    }
                } else {
                    // Both stay visible, same tap target, no delay and no confirm step — the only
                    // change is which one is louder, and the user installed a budgeting app.
                    Button(onClick = onBackOut, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                    TextButton(onClick = onProceed, modifier = Modifier.fillMaxWidth()) { Text("Pay anyway") }
                }
            }
        }
    }
}
```

Add these imports to `EntryScreen.kt`:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.window.Dialog
import com.expensegarden.app.gate.GateView
import com.expensegarden.app.gate.severityForLogging
import com.expensegarden.app.render.SpriteLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

and remove the now-unused `androidx.compose.material3.AlertDialog` import **only if** no other
dialog in the file still uses it — `EntryScreen` has no other `AlertDialog`, so it goes.

- [ ] **Step 4: Delete the old path**

In `MainViewModel.kt`, delete the `prepareGate` function and the `GatePrompt` data class (line 40).
Both are now unreferenced.

- [ ] **Step 5: Build and test**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew testDebugUnitTest && ./gradlew lintDebug && ./gradlew installDebug
```

Expected: `BUILD SUCCESSFUL` for all three, 347 tests, 0 lint errors.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt app/src/main/java/com/expensegarden/app/gate/GatePresentation.kt
git commit -m "feat: show what a purchase costs before it is made"
```

---

## Task 9: Verification on device

The gate only fires on `draft.fromScan`, so every case below needs the QR path. Display
`<scratchpad>/qr32.png` on the Mac screen and scan it from the emulator's camera, or use any real
merchant QR.

- [ ] **Step 1: Set up a budget that will breach**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb shell am force-stop com.expensegarden.app
adb shell am start -n com.expensegarden.app/.MainActivity
```

Set an overall budget of ₹100 from the dashboard, then scan and enter ₹500 against **Shopping**.

Expected: the weed card. It names a weed, shows a sprite, and **"Not now" is the filled button**.

- [ ] **Step 2: The same amount against a necessity**

Scan again, enter ₹500, choose **Health**.

Expected: the neutral card. No quip, no sprite, **"Continue" is the filled button**, and the copy
is purely factual.

- [ ] **Step 3: Prove the necessity path records no dodge**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb shell "run-as com.expensegarden.app sqlite3 databases/expense-garden.db 'SELECT COUNT(*) FROM game_event WHERE type = \"gate.dodged\";'"
```

Note the number, tap **Back** on the necessity card three times, and run it again.

Expected: **unchanged**. Before this plan, each Back would have incremented it, and three of them
would have earned a rare.

- [ ] **Step 4: The pace case**

Set the overall budget to ₹10,000, log a few small manual expenses across earlier days so a streak
exists, then scan a purchase large enough to exceed today's allowance but stay under the budget.

Expected: the streak card, naming the same number the home strip shows.

**Found on device:** on a ledger with no streak yet this read *"This ends a 0-day streak"*. At zero
there is nothing to end, so `GateView.Streak` gained `endsAStreak` (`days > 0`) and the dialog falls
back to "This puts you ahead of pace"; the allowance line carries the detail either way. The rule
lives in the pure layer with a test, the wording in the renderer.

- [ ] **Step 5: Confirm the home strip number is now honest**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew connectedDebugAndroidTest
./gradlew installDebug
```

Expected: **71 instrumented tests pass at today's real date**, not only before the 8th. That is the
end-to-end proof the streak baseline landed.

On a device whose data predates this change, `firstObservedAt` is stamped on first launch *after*
upgrading, so the baseline falls back to the oldest `game_event` — which for an existing user is
the correct answer anyway.

- [ ] **Step 6: Update CLAUDE.md**

Change the unit test count from `(324)` to `(347)` in the Commands section, and add the gate
redesign to the Phase 4 roadmap line.

```bash
git add CLAUDE.md
git commit -m "docs: record the gate redesign and the new unit test count"
```

---

## Notes for whoever executes this

- **Do not make `firstObservedDay` a required parameter.** Nine call sites, six of them tests,
  currently rely on the default. A required parameter turns a contained change into a nine-file one.
- **`noSpendDays` is deliberately left alone.** "Nothing was logged that day" is true whether or not
  the device was watching; only the *under-pace* claim needs the ledger to have existed.
- **Never let a necessity ask for a quip.** `QuipRepository.pick` marks the row used, so asking and
  discarding would consume one from the no-repeat rotation for a dialog that never appears.
- **uiautomator lies about Compose button bounds** — it reports the expanded 48dp touch target.
  Measure pixels from `adb exec-out screencap` if you need exact geometry.
- `connectedDebugAndroidTest` uninstalls both APKs when it finishes. Install last, alone.
- The emulator needs `-gpu host` on a memory-constrained host or it falls back to software GL:
  ```bash
  ~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 -no-boot-anim -gpu host -memory 2048
  ```
