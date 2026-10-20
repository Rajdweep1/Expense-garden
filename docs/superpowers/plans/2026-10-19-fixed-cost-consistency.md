# Fixed-Cost Consistency (M4/M5) Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans (his standing preference
> for this repo). Steps use checkbox (`- [ ]`) syntax. If a step's output doesn't match its Expected
> line: stop and report.

**Goal:** Before 1 Dec, the payment gate and the monthly digest judge a recurring payee's spend by
the same fixed-cost line the strip and the live streak already use. 1 Dec is the first day a
complete look-back month exists, so the first day fixed-cost detection can fire on the phone's
fresh ledger.

**Architecture:** Two pure-layer changes, each wired by one repository call.
- **Gate (M4).** The gate learns whether the payment it is judging is itself a fixed cost (its
  payee is recurring this month). If so, the payment joins the overall scope's fixed term, and the
  verdict also keeps the line from before the payment for the "already ahead" test.
- **Month fold (M5).** The month fold takes the month's recurring payees from its caller and paces
  its streak against the per-day fixed slice, as `foldAllTime` already does.

No schema change, no new event type; Room stays v4.

**Tech Stack:** Kotlin 2.0.20, Room 4 (existing DAO queries only), JUnit 4 (JVM and instrumented).

**Spec:** the two deferred minors below, from the 2026-10-19 final review of the November-ready
branch (`b1077a7..b0a953e`, pushed). They exist only in that review's report, so the plan carries
them. The rulings table is the design.

## The findings

- **M4: the gate can claim a streak ends when it won't.**
  - `LedgerRepository.evaluateGate` (`LedgerRepository.kt:205-213`) paces against
    `fixedSpentPaise(monthKey, …)`, which is the month's LOGGED spend on recurring payees. The
    payment being judged isn't logged yet, so it is never in that term.
  - The moment it is logged, the strip (`MainViewModel.kt:89-94`) counts it as fixed, and so does
    the streak (`GardenRepository.currentUnderPaceStreak`, the `fixedByDayPaise` slice).
  - So for a payment to a recurring payee the gate draws a lower line than everything that reads
    the ledger a second later.
  - Example: a ₹15,000 budget, ₹300 spent by 2 Nov, and a ₹1,000 tiffin bill whose payee was paid
    exactly once in October.
    - The gate's line is ₹1,150, and ₹1,300 is over it: "This ends a N-day streak".
    - After the log, the strip's line is ₹2,073: "on pace", and the streak goes on.
- **M5: the monthly digest's streak ignores fixed costs.**
  - `GardenFolder.fold` passes no `fixedByDayPaise` to `StreakMath` (`GardenFolder.kt:74-76`).
  - `GardenRepository.foldMonth` is what the monthly digest reads (`AiViewModel.kt:107`, then
    `PromptFacts.kt:39` "No-overspend streak"), and so does the past-months album
    (`GardenViewModel.kt:89`).
  - With ₹10,000 rent on the 1st and ₹100 a day, the postcard is told a streak the strip never
    showed.

**Reach.** `RecurringPayees.detect` needs at least one complete, fully watched look-back month. The
phone's ledger starts at the E2E's Step 10 wipe in late October, so November is the first complete
month, and both findings can fire from 1 Dec.

## Rulings (defaults — each is his to overturn)

| # | Question | Default | Why | Cost if wrong |
|---|---|---|---|---|
| 1 | How M4 is fixed | **The gate treats a recurring payee's payment as the fixed cost it becomes once logged.** Not a copy-only fix. | Removing just the streak claim would leave "This puts you ahead of pace" contradicting the strip's "on pace" a second later. Following the classification the strip, dashboard and streak already use is the only way the gate stops contradicting them. | A payment to a recurring payee can no longer trigger a pace warning by itself; a breach still fires. In December that includes any shop paid exactly once in November (ruling 5). |
| 2 | "Already ahead" with a fixed-cost payment | **Judged against the line before this payment.** The verdict carries both lines. | A fixed-cost payment raises today's line. Judging the earlier spend against the raised line would bring back the "This ends a N-day streak" misattribution Task 4 of the November plan removed. | None (one field). |
| 3 | `evaluateGate`'s payee | **A required `payeeVpa: String?` parameter, no default.** | A caller that forgets it would silently bring M4 back. The manual-log path passes its own null VPA, which is right there: it reads only BREACH, and no fixed term moves a breach. | Six mechanical edits in `GateScopeTest`. |
| 4 | How M5 is fixed | **`foldMonth` brings the month's recurring payees** (`LedgerRepository.recurringPayeeIds`, the same source as the live streak). The fold derives the per-day fixed slice itself, as `foldAllTime` does. | The fold sees one month, so it can't detect recurring payees; its caller can. The fold's weather keeps `fixedPaise = 0`, because an archived month can't reach PACE (the comment at `GardenFolder.kt:48-57` argues it). | None. |
| 5 | The one-complete-month detection rule | **Unchanged.** `RecurringPayeesTest` pins it: `one complete month of history is enough`. | Requiring two months would mean no fixed costs in December, so a day-1 rent payment would put the strip and gate "ahead of pace" for three weeks. That is the measured problem fixed costs were built for (`RecurringPayees.kt:9-16`). | In December, payments to a shop paid exactly once in November pass the pace line as fixed. The strip already does this today; ruling 1 makes the gate agree with it. |
| 6 | `observeCurrentGarden` | **Left as is.** It has no production caller; only `GardenRepositoryTest` reads it. | Not a path the user sees. | None. |
| 7 | Device check | **None; the two instrumented tests stand in.** | The gate's recurring case needs a QR payment and a fully watched look-back month, and the emulator offers neither without rewriting the app's prefs. `GateScopeTest` pins the repository path, and the two `MainViewModel` call sites are one-line pass-throughs for the final reviewer. | A wiring slip in `MainViewModel` would reach the phone. The final review reads both call sites. |

## Global Constraints

- Every Gradle command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
  Anything on a device: `ANDROID_SERIAL=emulator-5554`, and no phone attached.
- No new dependencies, no version bumps, no deprecation "fixes" (CLAUDE.md).
- No entity changes: Room stays v4. `git status --short app/schemas` must stay empty.
- Money is paise as `Long`. `game_event` is append-only.
- Instrumented test names are snake_case; JVM test names use backticks.
- `testcount` (define once per shell; reads the JVM result XMLs, so run it after a `--rerun`):

```bash
testcount() { python3 -c "import glob,sys,xml.etree.ElementTree as E; rs=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/*.xml') if sys.argv[1] in f]; print(sum(int(r.get('tests')) for r in rs), 'tests,', sum(int(r.get('failures'))+int(r.get('errors')) for r in rs), 'failed')" "$1"; }
```

- Stage named paths only; plain commit messages, no attribution lines; no push unless asked.

**Done when:** 398 JVM tests (392 + 6) and 92 instrumented (90 + 2) pass, and `app/schemas` is
untouched.

## Review Focus

1. **A shop paid for the first time** has no payee row yet, so the gate must treat the payment as
   variable. Pinned by the `new@ybl` line of
   `a_payment_to_a_recurring_payee_is_paced_as_the_fixed_cost_it_becomes` (Task 1).
2. **A fixed-cost payment that takes the month past its whole budget** is still a breach: fixed
   costs move only the pace line. Pinned by `a fixed-cost payment over the budget is still a breach`
   (Task 1).
3. **A recurring payee inside a category with its own budget** still counts in that category's line,
   which never had a fixed term. Pinned by `a recurring payment still counts in its category's own
   line` (Task 1).
4. **Already over today's line before a fixed-cost payment** means no streak claim. Pinned by
   `a fixed-cost payment judges already-ahead by the line before it` (Task 1).
5. **December, a shop paid exactly once in November:** the gate treats the payment as fixed, as the
   strip already does. That is ruling 5, pinned by `RecurringPayeesTest`'s
   `one complete month of history is enough` (unchanged).

## File structure

| File | Change |
|---|---|
| `gate/GateAggregator.kt` | `aggregate` gains `candidateFixedPaise`; `GateVerdict` gains `allowanceBeforePaise` |
| `gate/GatePresentation.kt` | `of` gains a required `allowanceBeforePaise`; "already ahead" judged against it |
| `data/LedgerRepository.kt` | `evaluateGate` gains a required `payeeVpa`; `fixedSpentPaise(monthKey, recurring)` |
| `ui/MainViewModel.kt` | passes `d.vpa` (both `evaluateGate` calls) and `verdict.allowanceBeforePaise` |
| `game/GardenFolder.kt` | `fold` gains `recurringPayeeIds`; one private per-day-sum helper shared by both folds |
| `data/GardenRepository.kt` | `foldMonth` passes the month's recurring payees |

All paths are under `app/src/main/java/com/expensegarden/app/`; tests mirror them under `app/src/test/…`
and `app/src/androidTest/…`.

---

## Task 1: The gate paces a recurring payee's payment as the fixed cost it becomes (M4)

**Files:**
- Modify: `gate/GateAggregator.kt`, `gate/GatePresentation.kt:114-162`,
  `data/LedgerRepository.kt:186-213`, `ui/MainViewModel.kt:142-170,198`
- Test: `app/src/test/java/com/expensegarden/app/gate/GateAggregatorTest.kt` (+4),
  `app/src/test/java/com/expensegarden/app/gate/GatePresentationTest.kt` (+1),
  `app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt` (+1, and its six existing
  `evaluateGate` calls)

**Interfaces — produces:**
- `data class GateVerdict(val severity: Severity, val offender: ScopeInput?, val allowancePaise: Long = 0L, val allowanceBeforePaise: Long = allowancePaise)`.
  - `allowancePaise` is the offender's line including this payment's own fixed share.
  - `allowanceBeforePaise` is the same line without it. The two differ only when the payment is a
    fixed cost and the offender is the overall scope.
- `GateAggregator.aggregate(scopes: List<ScopeInput>, candidatePaise: Long, fixedPaise: Long, dayOfMonth: Int, daysInMonth: Int, candidateFixedPaise: Long = 0L): GateVerdict`.
  `candidateFixedPaise` joins the OVERALL scope's fixed term only.
- `GatePresentation.of(…, allowancePaise: Long, allowanceBeforePaise: Long): GateView`, a required
  last parameter.
- `LedgerRepository.evaluateGate(categoryId: Long, amountPaise: Long, occurredAt: Long, ledgerBeganAt: Long?, payeeVpa: String?): GateVerdict`, required.
- `LedgerRepository.fixedSpentPaise(monthKey: String, recurring: Set<Long>): Long`, replacing the
  `ledgerBeganAt` overload. `evaluateGate` is its only caller.

- [ ] **Step 1: Failing JVM tests.** `GateAggregatorTest` (same package as `GateEvaluator`; no new
  imports):

```kotlin
@Test fun `a recurring payee's payment joins the overall fixed term`() {
    // ₹15,000 budget, ₹300 spent by 2 Nov, a ₹1,000 tiffin bill the strip will count as fixed.
    val overall = ScopeInput(null, "Overall", 1_500_000L, 30_000L, 0)
    assertEquals(Severity.PACE_WARNING, GateAggregator.aggregate(listOf(overall), 100_000L, 0L, 2, 30).severity)
    assertEquals(
        Severity.OK,
        GateAggregator.aggregate(listOf(overall), 100_000L, 0L, 2, 30, candidateFixedPaise = 100_000L).severity,
    )
}

@Test fun `the verdict keeps the line from before a fixed-cost payment`() {
    val v = GateAggregator.aggregate(
        listOf(ScopeInput(null, "Overall", 3_000_000L, 1_200_000L, 0)), 1_500_000L, 0L, 10, 30,
        candidateFixedPaise = 1_500_000L,
    )
    assertEquals(Severity.PACE_WARNING, v.severity)
    assertEquals(GateEvaluator.paceAllowancePaise(3_000_000L, 1_500_000L, 10, 30), v.allowancePaise)
    assertEquals(GateEvaluator.paceAllowancePaise(3_000_000L, 0L, 10, 30), v.allowanceBeforePaise)
}

@Test fun `a fixed-cost payment over the budget is still a breach`() =
    assertEquals(
        Severity.BREACH,
        GateAggregator.aggregate(
            listOf(ScopeInput(null, "Overall", 1_500_000L, 1_000_000L, 0)), 600_000L, 0L, 10, 30,
            candidateFixedPaise = 600_000L,
        ).severity,
    )

@Test fun `a recurring payment still counts in its category's own line`() {
    val v = GateAggregator.aggregate(
        listOf(ScopeInput(null, "Overall", 5_000_000L, 30_000L, 0), ScopeInput(101L, "Food", 200_000L, 50_000L, 1)),
        100_000L, 0L, 2, 30, candidateFixedPaise = 100_000L,
    )
    assertEquals(Severity.PACE_WARNING, v.severity)
    assertEquals("Food", v.offender?.label)
}
```

`GatePresentationTest`. The `view` helper gains `allowanceBeforePaise: Long = allowancePaise`,
passed through to `of`:

```kotlin
@Test
fun `a fixed-cost payment judges already-ahead by the line before it`() {
    // Rent raises today's line from ₹11,500 to ₹20,750; ₹12,000 spent before it was already over.
    val ahead = view(Severity.PACE_WARNING, isNecessity = false, scopeLabel = null,
        spentPaise = 1_200_000L, allowancePaise = 2_075_000L, allowanceBeforePaise = 1_150_000L) as GateView.Streak
    assertEquals("This puts you further ahead of pace", ahead.headline())
    val onPace = view(Severity.PACE_WARNING, isNecessity = false, scopeLabel = null,
        spentPaise = 800_000L, allowancePaise = 2_075_000L, allowanceBeforePaise = 1_150_000L) as GateView.Streak
    assertEquals("This ends a 9-day streak", onPace.headline())
}
```

- [ ] **Step 2: Stub, then watch them fail.**
  - Add the new parameter and field with the defaults above, but don't use them: `aggregate`
    ignores `candidateFixedPaise`, and `of` ignores `allowanceBeforePaise`.
  - `of` keeps `alreadyAhead = spentPaise > allowancePaise` for now, and `MainViewModel` passes
    `allowanceBeforePaise = verdict.allowanceBeforePaise`.
  - Run: `./gradlew testDebugUnitTest --rerun --tests 'com.expensegarden.app.gate.*'`
  - Expected: `BUILD FAILED`, 3 failures:
    - `a recurring payee's payment joins the overall fixed term`:
      `expected:<OK> but was:<PACE_WARNING>`.
    - `the verdict keeps the line from before a fixed-cost payment`: the allowance assertion.
    - `a fixed-cost payment judges already-ahead by the line before it`: a `ComparisonFailure` on
      the headline, "further ahead of pace" expected and "ends a 9-day streak" actual.

  The breach and category tests pass on the stub; they guard what must not change.
- [ ] **Step 3: Implement.**
  - `aggregate` uses `fixedPaise + candidateFixedPaise` as the overall scope's fixed term for the
    verdict and `allowancePaise`, and `fixedPaise` alone for `allowanceBeforePaise`. Category
    scopes get neither.
  - `of` judges `alreadyAhead = spentPaise > allowanceBeforePaise`.
  - KDoc for the new parameter, the new field and the `of` parameter: one line each, saying why
    (rulings 1 and 2).
- [ ] **Step 4: Run.** Same command. Expected: `BUILD SUCCESSFUL`;
  `testcount GateAggregatorTest` → `10 tests, 0 failed`; `testcount GatePresentationTest` →
  `15 tests, 0 failed`.
- [ ] **Step 5: Failing integration test.** `GateScopeTest`:

```kotlin
@Test fun a_payment_to_a_recurring_payee_is_paced_as_the_fixed_cost_it_becomes() = runBlocking {
    fun noonOn(m: Int, d: Int) = LocalDate.of(2026, m, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-11", amountPaise = 1_500_000, updatedAt = 1L))  // ₹15,000
    // One ₹1,000 tiffin bill in October makes the tiffin service a fixed cost in November.
    repo.saveManualLogged(LedgerRepository.Draft(vpa = "tiffin@ybl", payeeName = "Tiffin", amountPaise = 100_000,
        categoryId = 101, note = null, occurredAt = noonOn(10, 2)), breachedAtLogging = false)
    repo.saveManualLogged(LedgerRepository.Draft(vpa = null, payeeName = "Chai", amountPaise = 30_000,
        categoryId = 103, note = null, occurredAt = noonOn(11, 1)), breachedAtLogging = false)
    val nov2 = noonOn(11, 2)
    // ₹1,300 is over the ₹1,150 line only if the bill is variable; as the fixed cost the strip
    // will call it once logged, the line is ₹2,073.
    assertEquals(Severity.OK, repo.evaluateGate(101, 100_000, nov2, ledgerBeganAt = null, payeeVpa = "tiffin@ybl").severity)
    // A first payment to a new merchant has no payee row, so it stays variable.
    assertEquals(Severity.PACE_WARNING, repo.evaluateGate(101, 100_000, nov2, ledgerBeganAt = null, payeeVpa = "new@ybl").severity)
}
```

  Add `payeeVpa: String?` to `evaluateGate`, ignored for now. Append `payeeVpa = null` to the six
  existing `GateScopeTest` calls and `d.vpa` to both `MainViewModel` calls (`:147`, `:198`).
  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GateScopeTest`
  - Expected: `Tests on … failed: There was 1 failure(s)`:
    `a_payment_to_a_recurring_payee_is_paced_as_the_fixed_cost_it_becomes` with
    `expected:<OK> but was:<PACE_WARNING>`. The other 6 pass.
- [ ] **Step 6: Implement.**
  - `evaluateGate` computes `recurringPayeeIds(monthKey, ledgerBeganAt)` once and passes
    `fixedSpentPaise(monthKey, recurring)` and `candidateFixedPaise`.
  - `candidateFixedPaise` is `amountPaise` when `payeeVpa`'s payee (`db.payeeDao().byVpa`) exists
    and is in `recurring`, else `0L`.
  - `fixedSpentPaise(monthKey, recurring)` keeps its backdating KDoc (`LedgerRepository.kt:178-185`).
  - `evaluateGate`'s KDoc gains `@param payeeVpa`: one sentence for rulings 1 and 3.
- [ ] **Step 7: Run.**
  - The Step 5 command. Expected: `Finished 7 tests`, `BUILD SUCCESSFUL`.
  - Full JVM: `./gradlew testDebugUnitTest --rerun`, then `testcount Test`. Expected:
    `397 tests, 0 failed`.
- [ ] **Step 8: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/gate/GateAggregator.kt app/src/main/java/com/expensegarden/app/gate/GatePresentation.kt app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt app/src/test/java/com/expensegarden/app/gate/GateAggregatorTest.kt app/src/test/java/com/expensegarden/app/gate/GatePresentationTest.kt app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt
git commit -m "fix: pace a recurring payee's payment as the fixed cost it becomes"
```

---

## Task 2: The month's streak paces against its fixed costs (M5)

**Files:**
- Modify: `game/GardenFolder.kt:18-82` (and the per-day sums in `foldAllTime`, `:173-179`),
  `data/GardenRepository.kt` (`foldMonth`)
- Test: `app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt` (+1),
  `app/src/androidTest/java/com/expensegarden/app/data/GardenRepositoryTest.kt` (+1)

**Interfaces:**
- Consumes: `LedgerRepository.recurringPayeeIds(monthKey: String, ledgerBeganAt: Long?): Set<Long>`
  (exists).
- Produces: `GardenFolder.fold(…, overallByDay: Map<Int, Long>? = null, recurringPayeeIds: Set<Long> = emptySet())`
  (new last parameter).

- [ ] **Step 1: Failing integration test.** It compiles against today's code. `GardenRepositoryTest`:

```kotlin
@Test fun the_month_fold_paces_its_streak_against_rent() = runBlocking {
    GardenPrefs(ApplicationProvider.getApplicationContext()).firstObservedAt =
        LocalDate.of(2025, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    budgets.setBudget(null, "2025-05", 1_500_000)
    budgets.carryForward("2025-06")                                  // ₹15,000 all of June
    suspend fun paid(payee: String, vpa: String?, paise: Long, day: LocalDate, cat: Long) = ledger.saveManualLogged(
        LedgerRepository.Draft(vpa = vpa, payeeName = payee, amountPaise = paise, categoryId = cat, note = null,
            occurredAt = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()),
        breachedAtLogging = false,
    )
    paid("Landlord", "landlord@ybl", 1_000_000, LocalDate.of(2025, 5, 1), 401)   // once in May: June's rent is fixed
    paid("Landlord", "landlord@ybl", 1_000_000, LocalDate.of(2025, 6, 1), 401)
    for (d in 1..30) paid("Chai", null, 10_000, LocalDate.of(2025, 6, d), 103)
    // Against the plain line the rent fails June 1–21; against the fixed-cost line no day fails.
    assertEquals(30, garden.foldMonth("2025-06").streakDays)
}
```

  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GardenRepositoryTest`
  - Expected: 1 failure, `the_month_fold_paces_its_streak_against_rent` with
    `expected:<30> but was:<9>`. The other 8 pass.
- [ ] **Step 2: Failing JVM test.** `GardenFolderTest`. The `fold` helper gains
  `recurringPayeeIds: Set<Long> = emptySet()`, passed through:

```kotlin
@Test fun `the month fold paces its streak against the month's fixed costs`() {
    // The monthly digest reads this number; the strip's streak already takes rent out of the line.
    val rent = txn(2, day = 1, paise = 1_000_000).copy(payeeId = 77)
    val chai = (1..9).map { txn(103, day = it, paise = 10_000) }
    val budget = listOf(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 1_500_000, updatedAt = 1L))
    val today = LocalDate.of(2026, 7, 10)
    assertEquals(9, fold(chai + rent, budget, today = today, recurringPayeeIds = setOf(77L)).streakDays)
    assertEquals(0, fold(chai + rent, budget, today = today).streakDays)   // the plain line: rent fails day 1
}
```

  - Add the `fold` parameter, ignored.
  - Run: `./gradlew testDebugUnitTest --rerun --tests 'com.expensegarden.app.game.GardenFolderTest'`
  - Expected: 1 failure, `expected:<9> but was:<0>`.
- [ ] **Step 3: Implement.**
  - Extract `private fun dayTotalsOf(txns: List<TransactionEntity>, zone: ZoneId): Map<Int, Long>`
    (sum of `amountPaise` grouped by day of month in `zone`). This is the third copy of that
    grouping, so it gets extracted. Use it for `dayTotals` in both folds and for the fixed slice in
    both.
  - `fold` passes `fixedByDayPaise = dayTotalsOf(ordered.filter { it.payeeId in recurringPayeeIds }, zone)`.
  - Change the first paragraph of the `fixedPaise = 0` comment (`GardenFolder.kt:48-51`) to say the
    caller now brings the recurring payees, and that only the streak uses them (ruling 4).
  - `foldMonth` passes `recurringPayeeIds = ledger.recurringPayeeIds(monthKey, ledgerBeganAt())`,
    with the comment "The monthly digest and the album narrate this month's streak; it paces
    against the same fixed costs the strip's did."
- [ ] **Step 4: Run.**
  - The Step 2 command. Expected: `testcount GardenFolderTest` → `18 tests, 0 failed`.
  - The Step 1 command. Expected: `Finished 9 tests`, `BUILD SUCCESSFUL`.
  - Full JVM. Expected: `398 tests, 0 failed`.
- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/game/GardenFolder.kt app/src/main/java/com/expensegarden/app/data/GardenRepository.kt app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt app/src/androidTest/java/com/expensegarden/app/data/GardenRepositoryTest.kt
git commit -m "fix: pace the month's streak against its fixed costs"
```

---

## Task 3: Every suite, then the counts

- [ ] **Step 1: Every suite.**
  - `./gradlew testDebugUnitTest --rerun-tasks`, then `testcount Test`. Expected:
    `398 tests, 0 failed`, with result files from this run.
  - `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`. Expected: `Finished 92 tests`.
  - `git status --short app/schemas`. Expected: empty.
  - Stop the emulator.
- [ ] **Step 2: Docs.**
  - `CLAUDE.md`: `# JVM unit tests (398)` and `# instrumented (92)`.
  - `docs/superpowers/plans/2026-10-11-phone-e2e.md` Step 0: `Run the 92 instrumented tests` and
    `` `tests 92 failures 0 errors 0` ``.
  - Commit both: `docs: test counts for the fixed-cost fixes`. His go on this plan covers this docs
    commit, as it did for the November plan; this plan file itself stays uncommitted.
- [ ] **Step 3: Report.** Before and after for M4 and M5, the suite counts and the commits. No push.

## Out of scope

- The other deferred minors from the 2026-10-19 review: M1 (undo bar re-shows), M2 (clearing every
  budget), M3, and M6–M11.
- The one-complete-month detection rule (ruling 5).
- `observeCurrentGarden` (ruling 6).
- R5 Restore guard, R7 TalkBack and R8–R22 from the 2026-10-18 review.
