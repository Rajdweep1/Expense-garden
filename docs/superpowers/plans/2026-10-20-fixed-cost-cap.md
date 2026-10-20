# Fixed-Cost Cap Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans (his standing preference
> for this repo). Steps use checkbox (`- [ ]`) syntax. If a step's output doesn't match its Expected
> line: stop and report.

**Goal:** Before 1 Dec, a recurring payee's spend counts as fixed only up to what it was last month
(plus a quarter). This replaces "the first payment of the month is fixed" and closes the three gaps
below.

**Architecture:** `RecurringPayees.detect` returns each recurring payee's monthly **cap**, set from
its regular payment in the latest complete look-back month. The month's payments to that payee fill
the cap in date order, and anything past it is spending. One pure function,
`RecurringPayees.fixedShares`, does the filling. Every monthly total and per-day slice that called
`fixedPayments` calls it instead. The gate's own payment gets whatever is left of its payee's cap.
Detection stops rejecting a payee for one small extra payment. No schema change; Room stays v4.

**Tech Stack:** Kotlin 2.0.20, Room 4 (existing DAO queries only), JUnit 4.

**Spec:** his go on 2026-10-19/20 ("ya go ahead" to "write the amount-based rule … before 1 Dec")
after the first-payment branch's final review. The plan carries the problems it must fix. All three
are pinned below by tests that fail on today's code:

1. **Small payment first.** `fixedPayments` (RecurringPayees.kt:98-103) treats the earliest payment
   of the month as the fixed one.
   - Example: ₹500 to the landlord on the 1st, then ₹18,000 rent on a ₹45,000 budget. The rent is
     treated as spending: pace dialogs on days 1–17 and a month-end projection of ₹1,29,500 on
     day 5.
   - At the gate, that rent payment is a warning today.
2. **The December leak.** With one complete look-back month, a shop paid exactly once in November is
   "recurring" in December, and its first December payment counts as fixed whatever its size.
   - Example: ₹300 once in November, then a ₹10,000 order on 3 Dec against a ₹30,000 budget. Today
     the gate says OK (line ₹12,225.80).
3. **The two-month rent dropout.** `detect` requires exactly one payment per look-back month
   (RecurringPayees.kt:73-76). One ₹500 top-up to the landlord in October removes rent from the fixed
   costs for November and December.

## Rulings (defaults — each is his to overturn)

| # | Question | Default | Why | Cost if wrong |
|---|---|---|---|---|
| 0 | "The payment closest to last month's amount" (what he approved) vs a cap | **A cap:** up to last month's regular payment + 25% of whatever is paid to the payee counts as fixed, filled in date order. | "Closest" still picks one payment. Rent split in two, or rent plus a top-up, would leave part of the rent as spending, and the gate would have to re-pick "closest" whenever a better match arrived. A cap fixes all three problems with one rule, and the total doesn't depend on payment order. | Spending with a recurring payee up to its cap passes the pace line, whatever it was for. |
| 1 | Who is recurring | **A payee whose largest payment is at least 80% of everything paid to it, in every complete look-back month.** Integer check: `largest × 5 ≥ total × 4`. A single payment qualifies, so today's rule is a special case. | A fixed cost is one regular payment a month. A small top-up shouldn't disqualify it (problem 3). Comparable repeat orders still should: two equal visits make 50%. | A shop with one big and one small visit in each look-back month becomes recurring, with its cap as the limit of the damage. |
| 2 | The cap | **Changed by the final review: the smaller of the look-back months' regular payments × 5 / 4**, in whole paise (it was the latest month's). | The latest-month cap let a one-off big payment become next month's allowance: ₹300 in November and a ₹10,000 one-off in December gave the shop a ₹12,500 January cap, which is problem 2 a month later. 25% slack still covers bills that drift. No floats. | A misclassified payee can pass up to 1.25× its smaller recent payment a month. A rent hike above 25% leaves the excess as spending until both look-back months carry it, and a bill that jumps more than 25% month on month has its excess counted as spending. |
| 3 | Order of filling | **By (`occurredAt`, `uuid`). Each payment takes `min(amount, cap left)`; the rest is spending.** | The monthly total is the same in any order. The per-day placement follows the dates, as the streak needs. | The excess lands on the latest payments, on the days they were made. |
| 4 | How shares are handed back | **`fixedShares` returns copies of the payment rows, each carrying only its fixed share** (zero shares dropped). | Every caller already groups rows by day and sums amounts, so the call sites stay one-liners. | A copy must never be written to the database. Only folds and sums read them, and the KDoc says so. |
| 5 | The gate's payment | **`min(amount, cap − already logged to that payee this month)`, never below 0.** | This is the same share the fill gives it once logged. QR drafts are dated now, so they always fill last. | None reachable: only manual drafts can be backdated, and they read only BREACH. |
| 6 | `GardenFolder.fold`'s parameter | **`recurringCaps: Map<Long, Long>`, required.** `observeCurrentGarden` passes the caps too. | The repo's rule for fixed-cost inputs is no defaults (RecurringPayees.kt:41-42). This also resolves the last two reviews' deferred minor about it. | None. |
| 7 | The class doc's "an amount threshold would classify a one-off impulse buy as fixed" | **Still true, and kept.** Amounts never decide *whether* a payee is recurring, only how much of its spend counts as fixed. | The doc is rewritten to say exactly that. | None. |
| 8 | Tests that pin the first-payment rule | **Rewritten:** three in RecurringPayeesTest, and GateScopeTest's second-bill test with a bill past the cap. | They pin the behaviour this plan replaces. | As rulings 2–3. |
| 9 | Rent split into two equal payments every month | **Still not recurring:** each half is 50%. Pre-existing, out of scope. | Telling a split rent from two equal shop visits needs more than amounts. | A tenant who always splits rent gets no fixed-cost treatment. |
| 10 | Comments that describe the old model | **Rewritten where this plan touches the model:** GateAggregator.kt:25,29-32, LedgerRepository.kt:178, the `evaluateGate` KDoc, GardenFolder.kt:32,53,177, StreakMath.kt:18, MonthStatsFolder.kt:31, PaceProjector.kt:6. | They would state the removed rule. This also resolves the last review's deferred minor. | None. |

## Global Constraints

- Every Gradle command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
  Anything on a device: `ANDROID_SERIAL=emulator-5554`, and no phone attached.
- No new dependencies, no version bumps, no deprecation "fixes" (CLAUDE.md).
- No entity changes: Room stays v4. `git status --short app/schemas` must stay empty.
- Money is paise as `Long`, never floats; the 4/5 and 5/4 ratios are integer arithmetic.
- Instrumented test names are snake_case; JVM test names use backticks.
- `testcount` (define once per shell; reads the JVM result XMLs, so run it after a `--rerun`):

```bash
testcount() { python3 -c "import glob,sys,xml.etree.ElementTree as E; rs=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/*.xml') if sys.argv[1] in f]; print(sum(int(r.get('tests')) for r in rs), 'tests,', sum(int(r.get('failures'))+int(r.get('errors')) for r in rs), 'failed')" "$1"; }
```

- Stage named paths only; plain commit messages, no attribution lines; no push unless asked.

**Done when:** 405 JVM tests (401 + 4) and 95 instrumented (93 + 2) pass, and `app/schemas` is
untouched.

## Review Focus

1. **A small payment before the regular one (problem 1)** leaves the regular one fixed. Pinned by
   `a small payment before the regular one leaves the regular one fixed`, `a small payment before
   rent doesn't make the rent spending` and `a_small_payment_before_rent_leaves_the_rent_fixed`
   (Tasks 1–2).
2. **A big order to a payee with a small regular payment (problem 2)** is mostly spending. Pinned by
   `a big order to a payee with a small regular payment is mostly spending` and
   `a_big_order_to_a_payee_with_a_small_regular_payment_is_mostly_spending`.
3. **Rent plus a small top-up in a look-back month (problem 3)** stays recurring. Pinned by
   `a small extra payment doesn't stop the regular one being recurring`.
4. **Payments past the cap** are spending everywhere. Pinned by `payments to a recurring payee fill
   its cap in date order` and `a_payment_past_a_recurring_payees_cap_is_spending`.
5. **Same-day payments to one payee** fill the cap the same way whatever order the rows arrive in.
   Pinned by `the fill is the same whatever order the rows arrive in`.

## File structure

All paths are under `app/src/main/java/com/expensegarden/app/`. Tests mirror them under
`app/src/test/…` and `app/src/androidTest/…`.

| File | Change |
|---|---|
| `stats/RecurringPayees.kt` | `detect` returns caps (dominant payment, cap = 5/4 of it); `fixedShares` replaces `fixedPayments`; `fixedSpentPaise` takes caps; class doc |
| `data/LedgerRepository.kt` | `recurringPayeeIds` → `recurringCaps`; the gate's payment gets its share of the cap (Task 2) |
| `game/GardenFolder.kt` | `fold(…, recurringCaps)` required; both folds fill caps |
| `data/GardenRepository.kt` | `foldMonth`, `observeCurrentGarden` and `currentUnderPaceStreak` pass caps |
| `ui/MainViewModel.kt`, `ui/DashboardViewModel.kt` | the strip and dashboard pass caps |
| `gate/GateAggregator.kt`, `game/StreakMath.kt`, `stats/MonthStatsFolder.kt`, `stats/PaceProjector.kt` | comments only (ruling 10) |

---

## Task 1: Recurring payees get a monthly fixed cap

**Files:**
- Modify: `stats/RecurringPayees.kt`, `data/LedgerRepository.kt:178-186,207-213`,
  `game/GardenFolder.kt:32-34,53,77,173-178`, `data/GardenRepository.kt:89-92,237,269-275`,
  `ui/MainViewModel.kt:92-93`, `ui/DashboardViewModel.kt:50-51`, comments per ruling 10.
- Test: `app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt` (16 → 19),
  `app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt` (19 → 20)

**Interfaces — produces:**
- `RecurringPayees.detect(txns: List<TransactionEntity>, currentMonth: YearMonth, zone: ZoneId, ledgerBeganAt: Long?): Map<Long, Long>`
  maps payee id to its cap in paise.
- `RecurringPayees.fixedShares(monthTxns: List<TransactionEntity>, caps: Map<Long, Long>): List<TransactionEntity>`
  returns copies of the LOGGED rows whose payee has a cap, each carrying its fixed share. It
  replaces `fixedPayments`.
- `RecurringPayees.fixedSpentPaise(monthTxns: List<TransactionEntity>, caps: Map<Long, Long>): Long`
  is the sum of `fixedShares`.
- `LedgerRepository.recurringCaps(monthKey: String, ledgerBeganAt: Long?): Map<Long, Long>`
  replaces `recurringPayeeIds`.
- `GardenFolder.fold(…, overallByDay: Map<Int, Long>? = null, recurringCaps: Map<Long, Long>)`,
  with `recurringCaps` required.

- [ ] **Step 1: Failing tests.** In `RecurringPayeesTest`:
  - The `detect` helper (`:26-30`) returns `RecurringPayees.detect(…).keys`. The existing
    membership tests stay as they are.
  - Delete the three first-payment tests (ruling 8): `only a recurring payee's first payment of the
    month is fixed`, `the first payment is chosen the same way whatever order the rows arrive in`
    and `the earliest payment is the fixed one even when a later one is bigger`.
  - Change `fixed spend ignores non-LOGGED rows` to pass `mapOf(1L to 2_250_000L)`.
  - Add:

```kotlin
@Test fun `a small extra payment doesn't stop the regular one being recurring`() {
    // Rent plus a ₹500 top-up in July; rent alone in August.
    val txns = listOf(
        txn(1, 2026, 7, 1, amountPaise = 1_800_000), txn(1, 2026, 7, 15, amountPaise = 50_000),
        txn(1, 2026, 8, 1, amountPaise = 1_800_000),
    )
    assertEquals(setOf(1L), detect(txns))
}

@Test fun `the cap is the latest month's regular payment plus a quarter`() {
    val txns = listOf(
        txn(1, 2026, 7, 1, amountPaise = 1_800_000),
        txn(1, 2026, 8, 1, amountPaise = 2_000_000), txn(1, 2026, 8, 20, amountPaise = 50_000),
    )
    assertEquals(mapOf(1L to 2_500_000L), RecurringPayees.detect(txns, YearMonth.of(2026, 9), zone, null))
}

@Test fun `payments to a recurring payee fill its cap in date order`() {
    val caps = mapOf(1L to 2_250_000L)                     // ₹18,000 rent last month
    val month = listOf(
        txn(1, 2026, 9, 9, amountPaise = 500_000),          // ₹5,000 more: only ₹4,000 fits
        txn(1, 2026, 9, 3, amountPaise = 1_800_000),        // rent
        txn(1, 2026, 9, 1, amountPaise = 50_000),           // ₹500 before rent
        txn(2, 2026, 9, 3, amountPaise = 45_000),           // lunch, not recurring
    )
    assertEquals(2_250_000L, RecurringPayees.fixedSpentPaise(month, caps))
    assertEquals(listOf(50_000L, 1_800_000L, 400_000L), RecurringPayees.fixedShares(month, caps).map { it.amountPaise })
}

@Test fun `the fill is the same whatever order the rows arrive in`() {
    // Same day, so the uuid breaks the tie.
    val small = txn(1, 2026, 9, 1, amountPaise = 500)
    val rent = txn(1, 2026, 9, 1, amountPaise = 1_800_000)
    val caps = mapOf(1L to 1_000_000L)
    val expected = listOf(rent.copy(amountPaise = 1_000_000))
    assertEquals(expected, RecurringPayees.fixedShares(listOf(small, rent), caps))
    assertEquals(expected, RecurringPayees.fixedShares(listOf(rent, small), caps))
}

@Test fun `a small payment before the regular one leaves the regular one fixed`() {
    val month = listOf(txn(1, 2026, 9, 9, amountPaise = 1_800_000), txn(1, 2026, 9, 1, amountPaise = 50_000))
    assertEquals(1_850_000L, RecurringPayees.fixedSpentPaise(month, mapOf(1L to 2_250_000L)))
}

@Test fun `a big order to a payee with a small regular payment is mostly spending`() {
    // ₹300 once last month made the shop "recurring"; this month only ₹375 of a ₹10,000 order is fixed.
    assertEquals(37_500L, RecurringPayees.fixedSpentPaise(listOf(txn(3, 2026, 9, 3, amountPaise = 1_000_000)), mapOf(3L to 37_500L)))
}
```

  In `GardenFolderTest`:
  - The `fold` helper's `recurringPayeeIds: Set<Long> = emptySet()` becomes
    `recurringCaps: Map<Long, Long> = emptyMap()`.
  - The two existing callers pass `recurringCaps = mapOf(77L to 1_250_000L)`. Their expectations
    are unchanged: 9 and 0.
  - Add:

```kotlin
@Test fun `a small payment before rent doesn't make the rent spending`() {
    val july = listOf(
        txn(2, day = 1, paise = 50_000).copy(payeeId = 77),       // ₹500 to the landlord
        txn(2, day = 2, paise = 1_000_000).copy(payeeId = 77),    // rent
    )
    val budget = listOf(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 3_000_000, updatedAt = 1L))
    assertEquals(9, fold(july, budget, today = LocalDate.of(2026, 7, 10), recurringCaps = mapOf(77L to 1_250_000L)).streakDays)
}
```

- [ ] **Step 2: Stub, rewire, watch them fail.** Change the signatures above while keeping today's
  behaviour:
  - `detect` maps today's set to `Long.MAX_VALUE`.
  - `fixedShares` returns today's first-payment selection over `caps.keys`, at full amounts.
  - `fixedSpentPaise` sums it.

  Rewire every caller to the new types (ruling 6 included):
  - `LedgerRepository.recurringCaps`. `evaluateGate` keeps its first-payment candidate rule for now;
    `payeeId in caps` reads the keys.
  - `GardenRepository`: `currentUnderPaceStreak`, `foldMonth`, and `observeCurrentGarden`, which
    now passes `ledger.recurringCaps(monthKey, ledgerBeganAt())`.
  - `GardenFolder`'s two `fixedShares` calls.
  - `MainViewModel`, `DashboardViewModel`.

  Run:
  `./gradlew testDebugUnitTest --rerun --tests 'com.expensegarden.app.stats.RecurringPayeesTest' --tests 'com.expensegarden.app.game.GardenFolderTest'`.

  Expected: `BUILD FAILED` with 7 failures, all `AssertionError`s:
  - `a small extra payment…`
  - `the cap is…`
  - `payments … fill its cap…`
  - `the fill is the same…`
  - `a small payment before the regular one…` (`expected:<1850000> but was:<50000>`)
  - `a big order…` (`expected:<37500> but was:<1000000>`)
  - `a small payment before rent…` (`expected:<9> but was:<0>`)
- [ ] **Step 3: Implement.**
  - **`detect`.** Keep the look-back month selection and its comment (`:56-71`). In each look-back month, group
    LOGGED rows by payee. A payee qualifies when `largest × 5 ≥ total × 4`. Recurring payees qualify
    in every look-back month. Each one's cap is its largest payment in the latest look-back month ×
    5 / 4.
  - **`fixedShares`.** Take the LOGGED rows whose payee has a cap and sort them by (`occurredAt`,
    `uuid`). Each takes `min(amountPaise, cap left)`. Return copies with that amount, dropping
    zeros.
  - **Docs and comments.** Rewrite the class doc's rule paragraph per rulings 1 and 7. Add one KDoc
    line on `fixedShares` saying copies must never be written back. Rewrite the comments in
    ruling 10.
- [ ] **Step 4: Run.**
  - The Step 2 command. Expected: `BUILD SUCCESSFUL`; `testcount RecurringPayeesTest` →
    `19 tests, 0 failed`; `testcount GardenFolderTest` → `20 tests, 0 failed`.
  - Full JVM. Expected: `405 tests, 0 failed`.
  - `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GardenRepositoryTest,com.expensegarden.app.data.GateScopeTest`.
    Expected: `Finished 17 tests`, `BUILD SUCCESSFUL`. Today's gate rule and the month fold's rent
    test are unchanged by caps that hold them.
- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/stats/RecurringPayees.kt app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/main/java/com/expensegarden/app/game/GardenFolder.kt app/src/main/java/com/expensegarden/app/data/GardenRepository.kt app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt app/src/main/java/com/expensegarden/app/gate/GateAggregator.kt app/src/main/java/com/expensegarden/app/game/StreakMath.kt app/src/main/java/com/expensegarden/app/stats/MonthStatsFolder.kt app/src/main/java/com/expensegarden/app/stats/PaceProjector.kt app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt
git commit -m "fix: give each recurring payee a monthly fixed cap from its regular payment"
```

---

## Task 2: The gate's payment gets what is left of its payee's cap

**Files:**
- Modify: `data/LedgerRepository.kt` (`evaluateGate`)
- Test: `app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt` (8 → 10)

**Interfaces:**
- Consumes: `LedgerRepository.recurringCaps` and `RecurringPayees.fixedSpentPaise(monthTxns, caps)`
  (Task 1).
- Produces: `evaluateGate` with an unchanged signature.

- [ ] **Step 1: Failing tests.** In `GateScopeTest`, replace
  `a_second_payment_to_a_recurring_payee_in_a_month_is_variable` (ruling 8) with
  `a_payment_past_a_recurring_payees_cap_is_spending`.
  - Same setup as the old test (₹1,000 tiffin in October; tiffin ₹1,000 and chai ₹300 on 1 Nov;
    ₹15,000 budget).
  - The second bill is `200_000`, with the comment "₹250 of the ₹1,250 cap is left, so ₹1,750 of
    this bill is spending".
  - It asserts `Severity.PACE_WARNING`.

  Add the two below. Each defines `noonOn` and `paid` locally, as the replaced test did, with
  `paid` logging to category 101:

```kotlin
@Test fun a_small_payment_before_rent_leaves_the_rent_fixed() = runBlocking {
    // ... noonOn, paid as above
    db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-11", amountPaise = 4_500_000, updatedAt = 1L))  // ₹45,000
    paid("landlord@ybl", "Landlord", 1_800_000, 10, 1)   // October's rent: a ₹22,500 cap in November
    paid("landlord@ybl", "Landlord", 50_000, 11, 1)      // ₹500 to the landlord before rent
    assertEquals(
        Severity.OK,
        repo.evaluateGate(101, 1_800_000, noonOn(11, 3), ledgerBeganAt = null, payeeVpa = "landlord@ybl").severity,
    )
}

@Test fun a_big_order_to_a_payee_with_a_small_regular_payment_is_mostly_spending() = runBlocking {
    // ... noonOn, paid as above
    db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-12", amountPaise = 3_000_000, updatedAt = 1L))  // ₹30,000
    paid("shop@ybl", "Shop", 30_000, 11, 5)       // ₹300 once in November: "recurring" in December, a ₹375 cap
    paid(null, "Chai", 200_000, 12, 1)
    // Only ₹375 of a ₹10,000 order is fixed: ₹12,000 against a ₹3,671 line is a warning.
    assertEquals(
        Severity.PACE_WARNING,
        repo.evaluateGate(101, 1_000_000, noonOn(12, 3), ledgerBeganAt = null, payeeVpa = "shop@ybl").severity,
    )
}
```

  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GateScopeTest`.
  - Expected: 2 failures:
    - `a_small_payment_before_rent_leaves_the_rent_fixed` with
      `expected:<OK> but was:<PACE_WARNING>`.
    - `a_big_order_to_a_payee_with_a_small_regular_payment_is_mostly_spending` with
      `expected:<PACE_WARNING> but was:<OK>`.

    The other 8 pass. The past-cap test is a guard, and it passes on both rules.
- [ ] **Step 2: Implement.** `candidateFixedPaise` is `min(amountPaise, max(0, cap − sum of
  monthTxns to that payee))` when the payee has a cap, else `0L`. `firstThisMonth` goes. The
  `evaluateGate` KDoc and its body comment, and GateAggregator's `candidateFixedPaise` KDoc, describe
  the cap (ruling 10).
- [ ] **Step 3: Run.** The Step 1 command. Expected: `Finished 10 tests`, `BUILD SUCCESSFUL`.
  Full JVM: `405 tests, 0 failed`.
- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/main/java/com/expensegarden/app/gate/GateAggregator.kt app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt
git commit -m "fix: give the gate's payment what is left of its payee's fixed cap"
```

---

## Task 3: Every suite, then the counts

- [ ] **Step 1: Every suite.**
  - `./gradlew testDebugUnitTest --rerun-tasks`, then `testcount Test`. Expected:
    `405 tests, 0 failed`, with result files from this run.
  - `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`. Expected: `Finished 95 tests`.
  - `git status --short app/schemas`. Expected: empty.
  - Stop the emulator.
- [ ] **Step 2: Docs.**
  - `CLAUDE.md`: `# JVM unit tests (405)` and `# instrumented (95)`.
  - `docs/superpowers/plans/2026-10-11-phone-e2e.md` Step 0: `Run the 95 instrumented tests` and
    `` `tests 95 failures 0 errors 0` ``.
  - Commit both: `docs: test counts for the fixed-cost cap`. His go on this plan covers this docs
    commit, as before; this plan file stays uncommitted.
- [ ] **Step 3: Report.** Before and after for the three problems, the suite counts and the commits.
  No push.

## Out of scope

- Detecting rent that is always split in two (ruling 9).
- Requiring two complete look-back months.
- The `houseLevel` KDoc that sits above `dayTotalsOf` (deferred minor from the fixed-cost review).
- R5, R7, R8–R22.
