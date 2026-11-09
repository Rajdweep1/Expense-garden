# Early Rent Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans (his standing preference
> for this repo). Steps use checkbox (`- [ ]`) syntax. If a step's output doesn't match its Expected
> line: stop and report.

**Goal:** Rent paid in the week before the 1st stays a fixed cost. Today it drops out of fixed
costs for three months.

**Architecture:** Recurring detection stops using calendar months. Inside `RecurringPayees.detect`,
a detection month runs from the 25th to the 24th, so a payment made on or after the 25th counts
toward the next month. These are unchanged:
- the 4/5 rule and the cap;
- the rule that the current month is never examined;
- spending, which is still counted by calendar month everywhere.

The three callers that query `detect`'s window take its start from a new
`RecurringPayees.windowStart`. No schema change; Room stays v4.

**Tech Stack:** Kotlin 2.0.20, Room 4 (existing DAO queries only), JUnit 4.

**Spec:** his request on 2026-10-20 ("write the plan for the early-rent fix"). It fixes finding I3
of the fixed-cost cap branch's final review, which was left open as pre-existing. There is no spec
doc, so the plan states the problem, and tests that fail on today's code pin it:

`detect` groups payments by calendar month (RecurringPayees.kt:59-61). Suppose rent is paid on
1 Sep and 1 Oct, and then November's ₹18,000 rent goes out early, on 31 Oct:
- October holds two equal rents. Neither is 4/5 of the month (`:84`), so the landlord fails October.
- November holds no rent, so the landlord is missing from it (`:87`).

The landlord drops out of fixed costs for three months:

| Month | Look-back months | Why the landlord fails |
|---|---|---|
| November | September, October | October fails |
| December | October, November | both fail |
| January | November, December | November fails |

Take December's rent on 1 Dec, with a ₹45,000 budget. As spending, it raises a pace warning
against a ₹1,669 line. Treated as the fixed cost it is, its line would be ₹19,001.

## Rulings (defaults — each is his to overturn)

| # | Question | Default | Why | Cost if wrong |
|---|---|---|---|---|
| 0 | How does an early payment reach the month it's for? | **Detection months run from the 25th to the 24th.** A payment on or after the 25th counts toward the next month, in `detect` only. | It's one grouping change inside `detect`; outside the window, no caller changes. A bill due between the 25th and the 31st stays consistent, because it always counts toward the next month. | The edge moves from the 1st to between the 24th and the 25th, and any payee whose payment day crosses it drops out the way rent does today (corrected after the final review, 2026-10-21): a bill usually paid on the 27th that once goes out on the 23rd, a bill whose day wanders between the 22nd and the 27th, or rent paid on or after the 25th of its own month. It costs less than at the 1st: from about the 27th (the 25th in February) the pace line is at or above the budget, so being fixed only matters on the days before that. A payee paid from the 25th on is also detected a month later: a new one, a changed amount and a cancellation each show up a month late. Rent paid more than 7 days early (6 when the month has 30 days, 4 in February, 5 in a leap year) still lands in the old month. |
| 1 | A day of the month, or a fixed number of days before the 1st? | **A day of the month.** | A fixed shift moves with month length. Seven days before 1 Oct is 24 Sep, so a payee paid on the 24th would land twice in October and never in September. | None found: every month has a 25th. |
| 2 | Which day? | **The 25th** (`NEXT_MONTH_FROM_DAY = 25`). | It covers rent paid in the week before the 1st. That week matters this month: 1 Nov 2026 is a Sunday. | If one of his fixed costs falls due on the 24th or the 25th, this needs a different day. |
| 3 | When does a detection month count as watched in full? | **Still from the calendar 1st.** `observedInFull` is unchanged, so a ledger begun on 28 Oct watched November. | The 2026-10-11 floor exists to catch a partial first month. This ledger missed only a few days of possible early payments. A floor on the 25th would cost it its first full month of detection, and rent on the 1st would trip the gate again. The existing test `a ledger that began earlier counts the month in full` (ledger begun 28 Sep) still passes. | Payments in those few days before the ledger began are invisible. An early rent paid then waits a month longer to be detected. A payee paid both then and later looks like it was paid once. |
| 4 | What about the first month he pays early? | **Out of scope: that calendar month still holds two rents** (corrected after the final review, 2026-10-21). October closes ₹18,000 heavier, because the month's close and BREACH count every rupee (GardenRepository.kt:305, GateEvaluator.kt:53-55). On 31 Oct the pace line is already above the budget, so the gate's verdict on the early rent is BREACH if it takes October over budget, and OK otherwise. If the payment is filed under Rent (a necessity), it gets the neutral card and no weed (PlantMapper.kt:70). | Counting the payment in November instead is accrual. Every month total would need the same rule: the strip, the dashboard, the garden, the digest and the reconciler. That's a design of its own. | Unless October's budget has a rent's slack, October closes over budget and forfeits its MONTH_UNDER_BUDGET rare (RareEngine.kt:75). The month he goes back to paying on the 1st holds no rent, so it comes in a rent under budget. Each happens once per switch. |
| 5 | `LOOK_BACK_MONTHS` | **Private. Callers get the window from `windowStart`.** | The window no longer starts on a calendar 1st. All three callers built it from the constant, and a caller that did so again would cut off the early payment. Making it private means the old expression no longer compiles. | None. |
| 6 | The KDoc at RecurringPayees.kt:108-113 | **Moves back above `fixedShares`.** The cap branch's fix pass inserted `newPaymentShare` between that doc and its function, leaving `fixedShares` undocumented. | It's in the file this plan edits, and the change is comment-only. | None. |

## Global Constraints

- Every Gradle command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- Device runs use the emulator only: no phone attached, `ANDROID_SERIAL=emulator-5554`.
  - Boot it in the background:
    `~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 -no-boot-anim -gpu host -no-snapshot-save`.
  - It is ready when `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell getprop sys.boot_completed`
    prints `1`.
- No new dependencies, no version bumps, no deprecation "fixes" (CLAUDE.md).
- No entity changes: Room stays v4. `git status --short app/schemas` must stay empty.
- The edge is a local date in the caller's `zone`.
- Instrumented test names are snake_case; JVM test names use backticks.
- `testcount` (define once per shell; it reads the JVM result XMLs, so run it after a `--rerun`):

```bash
testcount() { python3 -c "import glob,sys,xml.etree.ElementTree as E; rs=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/*.xml') if sys.argv[1] in f]; print(sum(int(r.get('tests')) for r in rs), 'tests,', sum(int(r.get('failures'))+int(r.get('errors')) for r in rs), 'failed')" "$1"; }
```

- Stage named paths only. Plain commit messages with no attribution lines. No push unless asked.

**Done when:** 413 JVM tests (408 + 5) and 96 instrumented tests (95 + 1) pass, and `app/schemas`
is untouched.

## Review Focus

1. **I3 itself: November's rent paid on 31 Oct.** The landlord stays fixed in November, December
   and January, at the ₹22,500 cap. Pinned by `November's rent paid on 31 October keeps the
   landlord fixed through January` (Task 1) and `rent_paid_early_stays_a_fixed_cost` (Task 2).
2. **The edge.** A second landlord payment on 24 Oct is a second October rent; one on 25 Oct is
   November's. Pinned by `a payment counts toward the next month from the 25th, not the 24th`.
3. **A payee due on the 24th, across a 30-day month** (ruling 1). Pinned by `a payee paid on the
   24th every month is recurring`.
4. **A ledger begun between the 25th and the 1st** still watched the next month (ruling 3). Pinned
   by `a ledger begun after the 25th still watched the next month`.
5. **The query window reaches back to the 25th.** A window that starts on the 1st cuts off the
   early payment, and the landlord drops out again.
   - Pinned by `the look-back window starts on the 25th before its first month`.
   - Also pinned by `rent_paid_early_stays_a_fixed_cost`: its ₹100 on 10 Oct makes October exist
     without the 30 Sep rent, so a window from 1 Oct fails it.
   - The strip's and the dashboard's windows have no test harness. The compiler pins them instead
     (ruling 5).

## File structure

All paths are under `app/src/main/java/com/expensegarden/app/`. Tests mirror them under
`app/src/test/…` and `app/src/androidTest/…`.

| File | Change |
|---|---|
| `stats/RecurringPayees.kt` | Task 1: detection months, class doc, KDoc moved (ruling 6). Task 2: `windowStart`, `LOOK_BACK_MONTHS` private. |
| `data/LedgerRepository.kt`, `ui/MainViewModel.kt`, `ui/DashboardViewModel.kt` | Task 2: the window starts at `windowStart`. |

---

## Task 1: Detection months run from the 25th

**Files:**
- Modify: `stats/RecurringPayees.kt`
- Test: `app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt` (22 → 26)

**Interfaces — produces:** `RecurringPayees.detect`, with an unchanged signature, groups LOGGED rows
by detection month. `private const val NEXT_MONTH_FROM_DAY = 25` lives inside `RecurringPayees`.

- [ ] **Step 1: Failing tests.** Append these to the end of `RecurringPayeesTest`, after `an unknown
  beginning keeps every month with data`, because two of them use `november`:

```kotlin
// ---- early payments across a month boundary (final review I3, 2026-10-20) ----

@Test fun `November's rent paid on 31 October keeps the landlord fixed through January`() {
    // Calendar months gave October two rents and November none. The ₹100 on 15 Nov gives November
    // data, so the landlord's absence from it counts.
    val rent = 1_800_000L
    val txns = listOf(
        txn(1, 2026, 9, 1, rent), txn(1, 2026, 10, 1, rent), txn(1, 2026, 10, 31, rent), txn(1, 2026, 12, 1, rent),
        txn(2, 2026, 11, 15, 10_000),
    )
    for (current in listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1))) {
        assertEquals("$current", mapOf(1L to 2_250_000L), RecurringPayees.detect(txns, current, zone, null))
    }
}

@Test fun `a payment counts toward the next month from the 25th, not the 24th`() {
    val rentOnThe1st = listOf(txn(1, 2026, 9, 1, 1_800_000), txn(1, 2026, 10, 1, 1_800_000))
    assertEquals(setOf(1L), detect(rentOnThe1st + txn(1, 2026, 10, 25, 1_800_000), november))        // November's rent, early
    assertEquals(emptySet<Long>(), detect(rentOnThe1st + txn(1, 2026, 10, 24, 1_800_000), november))  // a second October rent
}

@Test fun `a payee paid on the 24th every month is recurring`() {
    // Seven days before 1 Oct is 24 Sep: a fixed shift would put both payments in October.
    assertEquals(setOf(1L), detect(listOf(txn(1, 2026, 9, 24), txn(1, 2026, 10, 24)), november))
}

@Test fun `a ledger begun after the 25th still watched the next month`() {
    // Its first days count toward November, but a month is watched from its calendar 1st.
    assertEquals(
        setOf(1L),
        detect(listOf(txn(1, 2026, 10, 31, 1_800_000)), YearMonth.of(2026, 12), ledgerBeganAt = at(2026, 10, 28)),
    )
}
```

- [ ] **Step 2: Watch them fail.**
  - Run: `./gradlew testDebugUnitTest --rerun --tests 'com.expensegarden.app.stats.RecurringPayeesTest'`.
  - Expected: `BUILD FAILED`, `26 tests completed, 3 failed`, all `AssertionError`s:
    - `November's rent…` with `2026-11 expected:<{1=2250000}> but was:<{}>`;
    - `a payment counts toward the next month…` with `expected:<[1]> but was:<[]>`;
    - `a ledger begun after the 25th…` with `expected:<[1]> but was:<[]>`.
  - `a payee paid on the 24th…` passes. It is a guard that only a fixed 7-day shift fails
    (ruling 1).
- [ ] **Step 3: Implement.**
  - **`detect`.** Group LOGGED rows by detection month instead of calendar month. A row's detection
    month is the `YearMonth` of its local date in `zone`, plus one month when the date's day of
    month is `NEXT_MONTH_FROM_DAY` or later. Nothing else in `detect` changes, and
    `observedInFull` doesn't change either (ruling 3).
  - **Docs.**
    - Class doc: add a short paragraph after "COMPLETE MONTHS ONLY…". It should say that months here
      run from the 25th to the 24th, so November's rent paid on 31 Oct is November's. It should give
      the reasons for rulings 0 and 1, and say that spending is still counted by calendar month.
    - `observedInFull`: its KDoc says why it stays keyed to the calendar 1st (ruling 3).
    - Move the KDoc at `:108-113` above `fixedShares` (ruling 6).
- [ ] **Step 4: Run.**
  - The Step 2 command. Expected: `BUILD SUCCESSFUL`, and `testcount RecurringPayeesTest` →
    `26 tests, 0 failed`.
  - Full JVM: `./gradlew testDebugUnitTest --rerun`, then `testcount Test`. Expected:
    `412 tests, 0 failed`. No existing test changes; none has a payment on or after the 25th of a
    look-back month.
- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/stats/RecurringPayees.kt app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt
git commit -m "fix: count a payment from the 25th toward next month's recurring detection"
```

---

## Task 2: The window reaches back to the 25th

**Files:**
- Modify: `stats/RecurringPayees.kt`, `data/LedgerRepository.kt:183-184`,
  `ui/MainViewModel.kt:78-79`, `ui/DashboardViewModel.kt:39-40`
- Test: `RecurringPayeesTest.kt` (26 → 27),
  `app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt` (10 → 11)

**Interfaces:**
- Consumes: Task 1's detection months and `NEXT_MONTH_FROM_DAY`.
- Produces: `RecurringPayees.windowStart(currentMonth: YearMonth, zone: ZoneId): Long`, in epoch
  millis. `LOOK_BACK_MONTHS` becomes private (ruling 5).

- [ ] **Step 1: Failing JVM test.** Append to `RecurringPayeesTest`:

```kotlin
@Test fun `the look-back window starts on the 25th before its first month`() {
    // January looks back to November and December, and November's detection month begins on 25 Oct.
    assertEquals(at(2026, 10, 25), RecurringPayees.windowStart(YearMonth.of(2027, 1), zone))
}
```

  Run the Task 1 Step 2 command. Expected: `BUILD FAILED`, a compilation error in
  `RecurringPayeesTest.kt` for the unresolved reference `windowStart`.
- [ ] **Step 2: Failing instrumented test.** Append to `GateScopeTest`:

```kotlin
@Test fun rent_paid_early_stays_a_fixed_cost() = runBlocking {
    fun noonOn(m: Int, d: Int) = LocalDate.of(2026, m, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    suspend fun paid(vpa: String?, payee: String, paise: Long, m: Int, d: Int) = repo.saveManualLogged(
        LedgerRepository.Draft(vpa = vpa, payeeName = payee, amountPaise = paise, categoryId = 101, note = null,
            occurredAt = noonOn(m, d)),
        breachedAtLogging = false,
    )
    db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-12", amountPaise = 4_500_000, updatedAt = 1L))  // ₹45,000
    paid("landlord@ybl", "Landlord", 1_800_000, 9, 30)    // October's rent, paid early
    paid(null, "Chai", 10_000, 10, 10)                    // October has data even without that rent
    paid("landlord@ybl", "Landlord", 1_800_000, 10, 31)   // November's rent, paid early
    paid(null, "Dinner", 150_000, 11, 15)
    // December's rent is the fixed cost it was in October and November: ₹18,000 under a ₹19,001
    // line. As spending, it is over a ₹1,669 line.
    assertEquals(
        Severity.OK,
        repo.evaluateGate(101, 1_800_000, noonOn(12, 1), ledgerBeganAt = null, payeeVpa = "landlord@ybl").severity,
    )
}
```

  - Boot the emulator (Global Constraints).
  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GateScopeTest`.
  - Expected: `BUILD FAILED`, with 1 of 11 failed: `rent_paid_early_stays_a_fixed_cost`, with
    `expected:<OK> but was:<PACE_WARNING>`. Task 1's grouping alone doesn't fix this, because the
    window still starts on 1 Oct and cuts off the 30 Sep rent.
- [ ] **Step 3: Implement.**
  - `windowStart` returns the start of the day, in `zone`, of the 25th (`NEXT_MONTH_FROM_DAY`) of
    the calendar month `LOOK_BACK_MONTHS + 1` months before `currentMonth`. Its KDoc says why it
    exists (ruling 5).
  - `LOOK_BACK_MONTHS` becomes `private const val`.
  - `detect`'s `@param txns` now reads: "any superset of the window from [windowStart]".
  - The three `lookBackFrom` computations become `RecurringPayees.windowStart(ym, zone)`. Each class
    already has a `zone` from `ZoneId.systemDefault()` (LedgerRepository.kt:24, MainViewModel.kt:57,
    DashboardViewModel.kt:27).
- [ ] **Step 4: Run.**
  - The Task 1 Step 2 command. Expected: `BUILD SUCCESSFUL`, and `testcount RecurringPayeesTest`
    → `27 tests, 0 failed`.
  - Full JVM: `./gradlew testDebugUnitTest --rerun`, then `testcount Test`. Expected:
    `413 tests, 0 failed`.
  - `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GateScopeTest,com.expensegarden.app.data.GardenRepositoryTest`.
    Expected: `Finished 20 tests`, `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/stats/RecurringPayees.kt app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt
git commit -m "fix: start recurring detection's window on the 25th before its first month"
```

---

## Task 3: Every suite, then the counts

- [ ] **Step 1: Every suite.**
  - `./gradlew testDebugUnitTest --rerun-tasks`, then `testcount Test`. Expected:
    `413 tests, 0 failed`, with result files from this run.
  - `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`. Expected:
    `Finished 96 tests`.
  - `git status --short app/schemas`. Expected: empty.
  - Stop the emulator: `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 emu kill`.
- [ ] **Step 2: Docs.**
  - `CLAUDE.md`: `# JVM unit tests (413)` and `# instrumented (96)`.
  - In `docs/superpowers/plans/2026-10-11-phone-e2e.md` Step 0, change two strings:
    `Run the 96 instrumented tests` and `` `tests 96 failures 0 errors 0` ``.
  - Commit both with the message `docs: test counts for the early-rent fix`. His go on this plan
    covers this docs commit, as before. This plan file stays uncommitted.
- [ ] **Step 3: Report.** Give the I3 scenario before and after, the suite counts and the commits.
  No push.

## Out of scope

- Accrual: counting an early payment in the month it's for (ruling 4).
- Learning each payee's usual day instead of using one edge for everyone.
- Rent that is always split in two (the cap plan's ruling 9).
- The cap branch's deferred minors: the 4/5 threshold is unpinned, `foldAllTime`'s use of caps is
  untested, one test name is stale, and `fixedShares` returns entity copies.
- R5, R7, R8–R22.
