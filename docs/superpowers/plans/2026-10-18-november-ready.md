# November-Ready Fixes Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans (his standing preference
> for this repo). Steps use checkbox (`- [ ]`) syntax. If a step's output doesn't match its Expected
> line: stop and report.

**Goal:** Before 1 Nov — the first full month of real use — budgets survive the month boundary,
rewards are only earned on days a budget was in force, the gate only says true things, a mistaken
tap can be undone, double taps can't log or pay twice, the phone runs a fast build, and my
2026-10-18 sign regression is gone.

**Architecture:** No schema change (Room stays v4, the server is untouched). Budget history is
recorded as two new append-only `game_event` types, `budget.carried` and `budget.set` — the
server's `game_event.type` is deliberately unconstrained (`001_init.sql:47-48`). Every product rule
lands in pure, JVM-tested Kotlin (`BudgetFloor`, `StreakMath`, `GateAggregator`,
`GatePresentation`, `PendingLabels`, `Money`); repositories wire it and are tested against
in-memory Room on the emulator. Undo and double-tap safety are one primitive: a status transition
guarded by the expected current status.

**Tech Stack:** Kotlin 2.0.20, Compose (M3 `SnackbarHost`), Room 4 (DAO queries only), AGP signing
config.

**Spec:** `docs/superpowers/reviews/2026-10-18-functional-ux-review.md` (R1–R4, R6, R17).

## Rulings (his "pick defaults", 2026-10-18 — each is a default he can overturn)

| # | Question | Default | Why | Cost if wrong |
|---|---|---|---|---|
| 1 | Budget rollover | **Automatic.** A month with no budget rows copies the latest earlier month's rows, once. A month he clears stays cleared. | The gate must never go silent by accident; a standing budget is an intention, not a monthly chore. | He re-sets a budget he meant to drop; one tap. |
| 2 | Do unused days count? | **Yes, unchanged.** The streak's new floor is the budget, not app usage. | A no-spend day and a day he didn't open the app look identical in the data — that is the no-spend rare's whole premise. | A dormant ledger can still earn no-spend rares (the villa.db case). |
| 3 | Weeds: compost or amend the invariant? | **Neither, in this plan.** Weeds stay permanent (gate spec §3); compost stays a Phase-4 follow-up. | Not a November risk; needs its own design. | The invariant stays violated until then. |
| 4 | Manual-log weeds | **Unchanged** (1B behaviour); revisit with #3. | Same. | Late honest logging can still grow a weed. |
| 5 | Corrections | **Undo only** — a snackbar that reverts the status. No edit/delete UI. | Covers mis-taps, the common case, with no new screens or sync semantics. | A typo found later still needs a ruling-#5 sequel. |
| 6 | Phone's daily build | **The debug-signed release build**, installed after the E2E (its Step 8 needs `run-as`). | 3.6 ms vs 20 ms of garden UI-thread work per frame (review). | `run-as` debugging on the phone needs a debug reinstall. |
| 7 | R17, my sign regression | **Revert `d026a0c`.** The original left-corner clip returns: cosmetic, and it pans fine. | The "face the house" rule broke wherever neighbours straddle the centre line; a collision-aware placement is its own design. | The corner sign is half cut at the default zoom again. |

## Global Constraints

- Every Gradle command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
  Anything on a device: `ANDROID_SERIAL=emulator-5554`, and no phone attached.
- No new dependencies, no version bumps, no deprecation "fixes" (CLAUDE.md).
- No entity changes: Room stays v4. `git status app/schemas` must stay empty after every build.
- Money is paise as `Long`. `game_event` is append-only (no UPDATE/DELETE of events anywhere new).
- Instrumented test names are snake_case.
- `testcount` from `2026-10-18-remaining-fixes.md` Conventions; stage named paths only; plain
  commit messages, no attribution; no push.
- Exact copy (the user sees these strings): every string in quotes below is final.

## Review Focus

1. **The app stays in memory across midnight on the 1st** (no cold start). Carry-forward must still
   run → Task 2 triggers it on every foreground, on the gate path and before a dashboard budget
   edit. `the_gate_sees_a_carried_budget` pins that carried rows reach the gate; the call sites
   themselves are reviewed, not tested.
2. **He clears a budget on purpose** → it must not come back that month →
   `a_month_the_user_cleared_stays_cleared` (Task 2).
3. **Double taps** on Log it, Continue to pay, the gate's buttons and the pending card →
   `confirming_twice_logs_once`, `saving_the_same_pending_twice_keeps_one_row` (Task 6), UI guards
   (Task 7).
4. **Rotation or process death mid-payment** must not launch a second payment →
   `savePendingOnce` returns false on the retry (Task 6), and Task 7 launches the UPI intent only on
   true.
5. **Undo after the row changed elsewhere** (e.g. confirmed, then Undo of an older Discard) →
   `a_stale_transition_changes_nothing` (Task 6).

## File structure

| File | Responsibility |
|---|---|
| `data/BudgetRepository.kt` | + `carryForward`, + `budget.set` events |
| `data/Daos.kt` | + `BudgetDao.latestMonthBefore`, + `TransactionDao.transition`, + `observePendingRows` (replaces `observePendingConfirm`) |
| `game/BudgetFloor.kt` (new) | first day a month's overall budget was in force — pure |
| `game/StreakMath.kt`, `game/GardenFolder.kt` | streak floor + fixed-cost-aware line |
| `data/GardenRepository.kt` | wires the floor and fixed costs; month.closed needs a whole-month budget |
| `gate/GateAggregator.kt`, `gate/GatePresentation.kt` | allowance from the verdict; honest Streak card |
| `core/Money.kt` | + `displayRupees` |
| `data/AppDatabase.kt`, `ai/Persona.kt` | two quips replaced, breach premise reworded |
| `data/LedgerRepository.kt` | guarded transitions, `savePendingOnce`, `revert`, `recurringPayeeIds` |
| `ui/PendingLabels.kt` (new), `ui/MainViewModel.kt`, `ui/GardenHomeScreen.kt`, `ui/EntryScreen.kt`, `ui/GardenViewModel.kt`, `MainActivity.kt` | pending copy, undo snackbar, busy guards, foreground hook |
| `app/build.gradle.kts` | release signed with the debug key |

---

## Task 1: Revert the month-sign regression (R17)

- [ ] **Step 1: Revert.**

```bash
git revert --no-commit d026a0c
git commit -m "revert: keep month signs left of their tile" -m "The house-facing rule moved every sign left of the house's centre line, so neighbouring signs that straddle it overlapped (OCT on JUL in the 2026-10-18 review). The original left-corner clip is cosmetic and pans fine."
```
- [ ] **Step 2: Verify.** `./gradlew testDebugUnitTest --tests 'com.expensegarden.app.render.IsoMathTest'`,
  then `testcount IsoMathTest`. Expected: `BUILD SUCCESSFUL`, `10 tests, 0 failed`;
  `grep -c signpostSide app/src/main/java/com/expensegarden/app/render/*.kt` prints only zeros.

## Task 2: Budgets carry forward into an empty month (R1)

**Interfaces — produces:** `BudgetRepository.carryForward(month: String): Boolean` (true = rows were
copied); `BudgetDao.latestMonthBefore(month: String): String?`; event `budget.carried` with payload
`{"month": "<YYYY-MM>", "from": "<YYYY-MM>", "overall": <true when an overall row was copied>}`;
`GardenViewModel.onForeground()`.

- [ ] **Step 1: Failing tests.** In `androidTest/.../data/BudgetRepositoryTest.kt` (existing fixture;
  imports `org.json.JSONObject`, `org.junit.Assert.assertFalse`; helper
  `suspend fun carried(m: String) = db.gameEventDao().ofType("budget.carried").filter { JSONObject(it.payloadJson).getString("month") == m }`).
  In the first test, also assert `JSONObject(carried("2026-11").single().payloadJson).getBoolean("overall")`:

```kotlin
@Test fun carrying_forward_copies_the_latest_months_budgets_into_an_empty_month() = runBlocking {
    repo.setBudget(null, "2026-10", 1_500_000); repo.setBudget(3, "2026-10", 400_000)
    assertTrue(repo.carryForward("2026-11"))
    assertEquals(mapOf(null to 1_500_000L, 3L to 400_000L),
        db.budgetDao().allForMonth("2026-11").associate { it.categoryId to it.amountPaise })
    val octMax = db.budgetDao().allForMonth("2026-10").maxOf { it.updatedAt }
    assertTrue(db.budgetDao().allForMonth("2026-11").all { it.updatedAt > octMax })   // dirty for sync
    assertEquals(1, carried("2026-11").size)
}
@Test fun a_month_with_budgets_is_left_alone() = runBlocking {
    repo.setBudget(null, "2026-10", 1_500_000); repo.setBudget(null, "2026-11", 900_000)
    assertFalse(repo.carryForward("2026-11"))
    assertEquals(900_000L, db.budgetDao().overallForMonth("2026-11")!!.amountPaise)
}
@Test fun a_month_the_user_cleared_stays_cleared() = runBlocking {
    repo.setBudget(null, "2026-10", 1_500_000)
    repo.carryForward("2026-11"); repo.setBudget(null, "2026-11", null)
    assertFalse(repo.carryForward("2026-11"))
    assertTrue(db.budgetDao().allForMonth("2026-11").isEmpty())
}
@Test fun carrying_skips_a_gap_to_the_latest_month_with_budgets() = runBlocking {
    repo.setBudget(null, "2026-07", 3_200_000)
    assertTrue(repo.carryForward("2026-10"))
    assertEquals(3_200_000L, db.budgetDao().overallForMonth("2026-10")!!.amountPaise)
}
@Test fun with_no_budget_ever_set_nothing_is_carried() = runBlocking {
    assertFalse(repo.carryForward("2026-11"))
    assertTrue(db.gameEventDao().ofType("budget.carried").isEmpty())
}
```

  In `GateScopeTest.kt` (existing fixture — its `LedgerRepository` field is `repo`; add
  `import java.time.LocalDate`):

```kotlin
@Test fun the_gate_sees_a_carried_budget() = runBlocking {
    val budgets = BudgetRepository(db)
    budgets.setBudget(null, "2026-10", 1_000_000)
    budgets.carryForward("2026-11")
    val nov15 = LocalDate.of(2026, 11, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    assertEquals(Severity.BREACH, repo.evaluateGate(7, 1_100_000, nov15, null).severity)
}
```

- [ ] **Step 2: Watch them fail to compile.** `./gradlew compileDebugAndroidTestKotlin 2>&1 | grep -oE "Unresolved reference '[a-zA-Z]+'|BUILD [A-Z]+" | sort -u`.
  Expected: `BUILD FAILED`, `Unresolved reference 'carryForward'`.
- [ ] **Step 3: Implement.**
  - `BudgetDao`: `@Query("SELECT MAX(month) FROM budget WHERE month < :month") suspend fun latestMonthBefore(month: String): String?`
  - `BudgetRepository.carryForward(month)`: inside one `db.withTransaction` (Room serialises write
    transactions, which is what makes concurrent callers safe): return false if `allForMonth(month)`
    is non-empty, or if a `budget.carried` event for `month` exists, or if `latestMonthBefore` is
    null. Otherwise insert a copy of every row of that source month with `updatedAt = clock.next()`,
    drop any tombstone for each new row key (as `setBudget` does), append the `budget.carried`
    event (`createdAt = System.currentTimeMillis()`), and return true. Call `onChanged()` after a copy.
  - `GardenViewModel`: replace the `init { … runReconciler() }` block with
    `fun onForeground()` that, unless its previous job is still active, launches
    `container.budgets.carryForward(container.ledger.currentMonthKey())` then
    `container.garden.runReconciler()`. One trigger only: two concurrent reconcilers could both emit
    the same streak.hit.
  - `MainActivity`: `override fun onStart() { super.onStart(); gardenVm.onForeground() }`.
  - `MainViewModel.prepareGateView` and `saveManualFromDraft`, and `DashboardViewModel.setBudget` /
    `clearBudget`: call `container.budgets.carryForward(ledger.currentMonthKey())` first (covers an
    app that stays in the foreground across midnight on the 1st; in the dashboard, a first category
    edit would otherwise make the month non-empty and stop the overall budget carrying).
- [ ] **Step 4: Run them on the emulator.**
  `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.BudgetRepositoryTest`
  → `Finished 10 tests`; same for `…data.GateScopeTest` → `Finished 6 tests`.
  Then `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`, `testcount ""` → `364 tests, 0 failed`.
- [ ] **Step 5: Commit** (BudgetRepository.kt, Daos.kt, GardenViewModel.kt, MainActivity.kt,
  MainViewModel.kt, DashboardViewModel.kt, BudgetRepositoryTest.kt, GateScopeTest.kt):
  `fix: carry budgets into a month that has none` + body: "Budgets were stored per month and nothing
  copied them forward, so on the 1st the gate approved everything until each one was re-entered.
  The first foreground of a month (and the gate itself) now copies the latest month's budgets, once;
  a month cleared on purpose stays cleared."

## Task 3: Streaks and month rewards need a budget in force (R2)

**Interfaces — consumes:** `budget.carried` (Task 2). **Produces:** event `budget.set` with payload
`{"month", "categoryId" (null for overall), "amountPaise"}`;
`BudgetFloor.overallFromDay(month: YearMonth, carried: Boolean, overallSetAt: List<Long>, legacyStampedAt: Long?, zone: ZoneId): Int`
(returns `1..month.lengthOfMonth() + 1`; `lengthOfMonth() + 1` = no day of the month);
`GardenRepository.budgetFromDayOf(month: YearMonth): Int`;
`GardenFolder.foldAllTime(…, budgetFromDay: Int = 1)`.

- [ ] **Step 1: Failing tests.**
  - New `test/.../game/BudgetFloorTest.kt` (zone `Asia/Kolkata`, month `2026-10`,
    `fun at(iso: String) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()`):
    `a carried month counts from the 1st` → `overallFromDay(oct, true, listOf(at("2026-10-18T13:22")), null, zone) == 1`;
    `a budget first set on the 18th counts from the 18th` → `… false, listOf(at("2026-10-18T13:22")), null …) == 18`;
    `the earliest of several sets wins` → sets on the 20th, 9th, 25th → `9`;
    `a row older than budget events counts from its stamp` → no sets, legacy `at("2026-10-05T09:00")` → `5`;
    `a budget written after the month counts no day of it` → legacy `at("2026-11-02T09:00")` → `32`;
    `a stamp before the month counts the whole month` → set `at("2026-09-30T23:00")` → `1`.
  - `GardenFolderTest`: give the `foldAll` and `fold` helpers a `budgetFromDay: Int = 1`
    pass-through, then `a budget set yesterday grants no streak for the days before it`: today
    2026-10-19, overall budget ₹15,000, no October spend → `streakDays == 1` with
    `budgetFromDay = 18` and `== 18` with the default — asserted through both helpers (the monthly
    digest reads `fold`'s streak).
  - `BudgetRepositoryTest`: `setting_a_budget_records_when_it_was_set` → after
    `setBudget(null, "2026-10", 1_500_000)` exactly one `budget.set` event whose payload has
    `month == "2026-10"`, `isNull("categoryId")`, `amountPaise == 1_500_000`; after
    `setBudget(null, "2026-10", null)` still exactly one.
  - `GardenRepositoryTest` (its existing `GardenRepository` and `LedgerRepository`, called `garden`
    and `ledger` below, plus `budgets = BudgetRepository(db)` — `Store` is private to
    BudgetRepositoryTest; imports `java.time.LocalDate`, `org.json.JSONObject`;
    `fun draftOn(d: LocalDate) = LedgerRepository.Draft(vpa = null, payeeName = "Kirana", amountPaise = 50_000, categoryId = 7, note = null, occurredAt = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())`;
    `suspend fun closed(m: String)` = the payload `JSONObject` of the single `month.closed` event
    whose `month == m`):

```kotlin
@Test fun a_month_whose_budget_was_set_after_it_began_closes_without_one() = runBlocking {
    budgets.setBudget(null, "2025-03", 1_500_000)        // stamped now, long after March 2025
    ledger.saveManualLogged(draftOn(LocalDate.of(2025, 3, 10)), breachedAtLogging = false)
    garden.runReconciler()
    assertTrue(closed("2025-03").isNull("overallBudgetPaise"))
}
@Test fun a_carried_month_closes_with_its_budget() = runBlocking {
    budgets.setBudget(null, "2025-03", 1_500_000)
    budgets.carryForward("2025-04")
    ledger.saveManualLogged(draftOn(LocalDate.of(2025, 4, 10)), breachedAtLogging = false)
    garden.runReconciler()
    assertEquals(1_500_000L, closed("2025-04").getLong("overallBudgetPaise"))
}
```

- [ ] **Step 2: Watch them fail.** JVM: `./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.BudgetFloorTest' 2>&1 | grep -oE "Unresolved reference '[a-zA-Z]+'|BUILD [A-Z]+" | sort -u`
  → `BUILD FAILED`, `Unresolved reference 'BudgetFloor'`. Emulator (these compile against Task 2's
  API): `BudgetRepositoryTest` → 1 failure, `setting_a_budget_records_when_it_was_set`;
  `GardenRepositoryTest` → 1 failure, `a_month_whose_budget_was_set_after_it_began_closes_without_one`.
  `a_carried_month_closes_with_its_budget` passes before and after — it guards the carried path.
- [ ] **Step 3: Implement.**
  - `game/BudgetFloor.kt` per the interface: carried → 1; else the earliest of `overallSetAt`, or
    `legacyStampedAt`, or (neither) 1; that instant's local month before `month` → 1, after →
    `lengthOfMonth() + 1`, inside → its day of month.
  - `BudgetRepository.setBudget`: when `amountPaise > 0`, append `budget.set`
    (`createdAt = System.currentTimeMillis()` — wall time, not the sync clock) in the same transaction.
  - `GardenRepository`: `private fun budgetFromDay(events: List<GameEventEntity>, overall: BudgetEntity?, month: YearMonth): Int`
    (parses `budget.carried` / `budget.set` payloads — JSON stays in the data layer; `carried` means
    a `budget.carried` event for the month with `overall == true`, so a carry of category budgets
    alone doesn't backdate a later overall budget) and `suspend fun budgetFromDayOf(month)` reading
    `ofType` and the overall row. `foldMonth` and `observeCurrentGarden` pass
    `budgetFromDayOf(month)` into `GardenFolder.fold`.
    `currentUnderPaceStreak` passes `maxOf(firstObservedDayOf(m), budgetFromDayOf(m))` as the floor;
    `observeAllTimeGarden` passes `budgetFromDay(allEvents, <overall row of budgets>, YearMonth.now(zone))`;
    `runReconciler` writes `overallBudgetPaise` only when `budgetFromDayOf(month) == 1`, else `NULL`.
  - `GardenFolder.foldAllTime` and `GardenFolder.fold` (both gain `budgetFromDay: Int = 1`):
    `streakDays` uses `maxOf(firstObservedDay, budgetFromDay)`; `noSpendDays` keeps
    `firstObservedDay` (ruling 2). `fold` matters because the monthly digest narrates its streak
    (`AiViewModel.kt:107`).
- [ ] **Step 4: Run.** `testcount BudgetFloorTest` → `6 tests, 0 failed`; `testcount GardenFolderTest`
  → `17 tests, 0 failed`; emulator: `BudgetRepositoryTest` → `Finished 11 tests`,
  `GardenRepositoryTest` → `Finished 6 tests`; full JVM → `371 tests, 0 failed`.
- [ ] **Step 5: Commit:** `fix: earn streaks and month rewards only while a budget is in force` +
  body naming the device evidence (streak.hit 3/7/14 written 90 s after a day-18 budget).

## Task 4: The gate card says only true things (R3)

**Interfaces — produces:** `GateVerdict(severity, offender, allowancePaise: Long = 0L)` (0 when OK; the
default keeps every existing construction compiling);
`GateView.Streak(days, quip, overPaise, allowancePaise, scopeLabel: String? = null, alreadyAhead: Boolean = false)`;
`fun GateView.Streak.headline(): String`; `Money.displayRupees(paise: Long): String`;
`internal val STATIC_QUIPS: List<Pair<String, String>>` in `AppDatabase.kt`.

- [ ] **Step 1: Failing tests.**
  - `GateAggregatorTest` — `the offender's allowance carries the fixed term on the overall scope only`:
    overall `ScopeInput(null, "Overall", 1_500_000, 1_100_000, 0)`, candidate 100_000, fixed
    1_000_000, day 10/31 → `PACE_WARNING`, `allowancePaise == 1_185_483`; and with overall
    `ScopeInput(null, "Overall", 5_000_000, 200_000, 0)` plus `ScopeInput(7, "Shopping", 600_000, 200_000, 1)`,
    candidate 50_000, same fixed → offender Shopping, `allowancePaise == 222_580`.
  - `GatePresentationTest` — give the `view` helper `scopeLabel: String? = "Shopping"`,
    `spentPaise: Long = 400_000L` and `allowancePaise: Long = 12_000L` parameters. Two existing
    tests pinned the false claim (a ruling of this plan) and change: **rewrite**
    `a discretionary pace warning previews the streak` to `scopeLabel = null, spentPaise = 10_000L`
    → `days == 9`, `headline() == "This ends a 9-day streak"`; in `a zero streak has nothing to end`
    the `earned` line becomes
    `view(Severity.PACE_WARNING, isNecessity = false, streakDays = 1, scopeLabel = null, spentPaise = 10_000L)`
    (`endsAStreak` stays). Every other existing test is unchanged. Add:
    `a category pace warning claims no streak` (`spentPaise = 10_000L`) → `days == 0`,
    `headline() == "This puts Shopping ahead of pace"`;
    `a pace warning already over the line claims no streak` (`scopeLabel = null, spentPaise = 20_000L`)
    → `days == 0`, `alreadyAhead`, `headline() == "This puts you further ahead of pace"`;
    `with no streak yet the card names only the pace` (`scopeLabel = null, streakDays = 0, spentPaise = 10_000L`)
    → `headline() == "This puts you ahead of pace"`.
  - `MoneyTest` — `displayRupees rounds to whole rupees with Indian grouping`:
    `123_388 → "₹1,234"`, `1_001_612 → "₹10,016"`, `15_000_000 → "₹1,50,000"`, `50 → "₹1"`, `49 → "₹0"`.
  - `PersonaTest` — `the breach premise does not claim the budget is already gone`:
    `Persona.quipPrompt(Tone.SHARP, Severity.BREACH, 5)` contains
    `"This payment would take a budget past its limit."` and not `"already gone"` (a category
    breach can leave the month's overall budget intact).
  - New `test/.../data/StaticQuipsTest.kt`: `each severity keeps five static lines` (5 per
    `PACE_WARNING` and `BREACH` — Step 7 of the phone script depends on it) and
    `no static line claims what the gate cannot know` (none contains `"already gone"`,
    `"archaeology"`, `"This one's fine"`, `"It's not the 1st"`).
- [ ] **Step 2: Watch them fail to compile.** Expected unresolved: `headline`, `alreadyAhead`,
  `displayRupees`, `STATIC_QUIPS` (and `allowancePaise` on GateVerdict).
- [ ] **Step 3: Implement.**
  - `GateAggregator.aggregate` fills `allowancePaise` with
    `GateEvaluator.paceAllowancePaise(offender.budgetPaise, fixedForScope, dayOfMonth, daysInMonth)`.
  - `GatePresentation.of` (PACE branch): `alreadyAhead = spentPaise > allowancePaise`;
    `days = if (scopeLabel == null && !alreadyAhead) streakDays else 0`. `headline()`:
    category → `"This puts $scopeLabel further ahead of pace"` if alreadyAhead else
    `"This puts $scopeLabel ahead of pace"`; overall → `"This ends a $days-day streak"` if days > 0,
    else `"This puts you further ahead of pace"` if alreadyAhead, else `"This puts you ahead of pace"`.
  - `Money.displayRupees`: `"₹" + groupIndian((paise + 50) / 100)` (non-negative inputs only, as
    every call site is).
  - `MainViewModel.prepareGateView`: `allowancePaise = verdict.allowancePaise` (the 3-argument
    recomputation goes).
  - `EntryScreen.GateDialog`: Streak title `view.headline()`; numbers line
    `listOfNotNull(view.scopeLabel, "${Money.displayRupees(view.overPaise)} over today's ${Money.displayRupees(view.allowancePaise)}").joinToString(" · ")`;
    Weed line uses `displayRupees` too.
  - Quips: hoist the seed list to `STATIC_QUIPS`; replace
    `"This one's fine. The next three are the problem."` with
    `"The calendar's behind your spending. Slow down?"`,
    `"You're spending like it's the 1st. It's not the 1st."` (false on the 1st, when a pace warning
    is likeliest) with `"Ahead of the plan for today. Worth a pause?"`, and
    `"Budget's already gone. This is just archaeology now."` with
    `"The budget won't stretch to this. Still want it?"`. `Persona` BREACH premise becomes
    `"This payment would take a budget past its limit."` Seeds apply to new databases only — the
    phone gets one at its E2E install.
- [ ] **Step 4: Run.** `testcount` → GateAggregatorTest `6`, GatePresentationTest `14`, MoneyTest `19`,
  PersonaTest `4`, StaticQuipsTest `2`, all `0 failed`; full JVM → `379 tests, 0 failed`.
- [ ] **Step 5: Commit:** `fix: make the gate card claim only what this payment does`.

## Task 5: The streak uses the same line as the gate (R3, fixed costs)

**Interfaces — produces:** `StreakMath.underPaceStreak(dayTotalsPaise, budgetPaise, today, daysInMonth, firstObservedDay = 1, fixedByDayPaise: Map<Int, Long> = emptyMap())`;
`LedgerRepository.recurringPayeeIds(monthKey: String, ledgerBeganAt: Long?): Set<Long>`.

- [ ] **Step 1: Failing test.** `StreakMathTest` — `fixed costs move the streak's line the way they move the gate's`:

```kotlin
val days = mapOf(1 to 1_010_000L) + (2..9).associateWith { 10_000L }   // ₹10,000 rent + ₹100/day
assertEquals(9, StreakMath.underPaceStreak(days, 1_500_000, 10, 31, fixedByDayPaise = mapOf(1 to 1_000_000L)))
assertEquals(0, StreakMath.underPaceStreak(days, 1_500_000, 10, 31))   // the old line: rent fails day 1
```

- [ ] **Step 2: Watch it fail to compile** (`fixedByDayPaise`).
- [ ] **Step 3: Implement.** Day d's line is
  `GateEvaluator.paceAllowancePaise(budget, fixedThrough(d), d, daysInMonth)` with
  `fixedThrough(d)` the sum of `fixedByDayPaise` for days 1..d. Extract `recurringPayeeIds` from
  `LedgerRepository.fixedSpentPaise` (which then uses it). `GardenFolder.foldAllTime` passes the
  current month's recurring-payee spend by day; `GardenRepository.currentUnderPaceStreak` derives
  the same from `ledger.recurringPayeeIds(monthKey, ledgerBeganAt())`.
- [ ] **Step 4: Run.** `testcount StreakMathTest` → `10 tests, 0 failed`; full JVM → `380 tests, 0 failed`.
- [ ] **Step 5: Commit:** `fix: pace the streak against the same fixed-cost line as the gate`.

## Task 6: Guarded status transitions (R4, data layer)

**Interfaces — produces:**
`TransactionDao.transition(uuid: String, from: TxnStatus, to: TxnStatus, updatedAt: Long): Int`
(`UPDATE txn SET status = :to, updatedAt = :updatedAt WHERE uuid = :uuid AND status = :from`);
`TransactionDao.observePendingRows(): Flow<List<TxnRow>>` (the `observeRecent` projection,
`WHERE t.status = 'PENDING_CONFIRM' ORDER BY t.createdAt`; `observePendingConfirm` is deleted);
`LedgerRepository.confirm(uuid): Boolean`, `discard(uuid): Boolean`,
`revert(uuid: String, from: TxnStatus, to: TxnStatus): Boolean`,
`savePendingOnce(draft: Draft, breachedAtLogging: Boolean, uuid: String): Boolean`,
`observePendingRows(): Flow<List<TxnRow>>`. `savePending` keeps returning the uuid.

- [ ] **Step 1: Failing tests.** New `androidTest/.../data/LedgerTransitionTest.kt` (fixture copied
  from BudgetRepositoryTest, including its own private `Store` class;
  `repo = LedgerRepository(db, SyncClock({ 1_000L }, Store()))`;
  `fun draft(payeeName: String = "Corner Shop", amountPaise: Long = 100) = LedgerRepository.Draft(vpa = "shop@ybl", payeeName = payeeName, amountPaise = amountPaise, categoryId = 7, note = null, occurredAt = 1_760_000_000_000L)`):

```kotlin
@Test fun confirming_twice_logs_once() = runBlocking {
    val uuid = repo.savePending(draft(), breachedAtLogging = false)
    assertTrue(repo.confirm(uuid)); assertFalse(repo.confirm(uuid))
    assertEquals(1, db.gameEventDao().ofType("transaction.logged").count { it.transactionUuid == uuid })
}
@Test fun a_stale_transition_changes_nothing() = runBlocking {
    val uuid = repo.savePending(draft(), false)
    assertFalse(repo.revert(uuid, TxnStatus.LOGGED, TxnStatus.DISCARDED))
    assertEquals(TxnStatus.PENDING_CONFIRM, db.transactionDao().byUuid(uuid)!!.status)
}
@Test fun discarding_then_undoing_brings_the_pending_back() = runBlocking {
    val uuid = repo.savePending(draft(), false)
    assertTrue(repo.discard(uuid))
    assertTrue(repo.revert(uuid, TxnStatus.DISCARDED, TxnStatus.PENDING_CONFIRM))
    assertEquals(listOf(uuid), repo.observePendingRows().first().map { it.uuid })
}
@Test fun undoing_a_manual_log_takes_it_out_of_the_month() = runBlocking {
    val uuid = repo.saveManualLogged(draft(amountPaise = 45_000), breachedAtLogging = false)
    assertTrue(repo.revert(uuid, TxnStatus.LOGGED, TxnStatus.DISCARDED))
    assertEquals(0L, db.transactionDao().loggedSumBetween(0L, Long.MAX_VALUE))
}
@Test fun saving_the_same_pending_twice_keeps_one_row() = runBlocking {
    assertTrue(repo.savePendingOnce(draft(), false, "scan-1"))
    assertFalse(repo.savePendingOnce(draft(), false, "scan-1"))
    assertEquals(1, repo.observePendingRows().first().size)
}
@Test fun pending_rows_carry_the_payee_name() = runBlocking {
    repo.savePending(draft(payeeName = "Sharma Chai"), false)
    assertEquals("Sharma Chai", repo.observePendingRows().first().single().payeeName)
}
```

- [ ] **Step 2: Watch them fail to compile** (`revert`, `savePendingOnce`, `observePendingRows`
  unresolved, plus a Unit-vs-Boolean type mismatch on `confirm`, which the grep doesn't print).
- [ ] **Step 3: Implement.** `confirm` = `transition(PENDING_CONFIRM → LOGGED)` inside its
  transaction, and only when that changed a row does it append `transaction.logged` and run
  `emitCrossings`; `discard` = `transition(PENDING_CONFIRM → DISCARDED)`; `revert` = one
  `transition`; each calls `onChanged()` only when it changed a row. `savePendingOnce` = one
  `db.withTransaction { if (byUuid(uuid) != null) false else { savePending(…, uuid); true } }`.
  `MainViewModel.pendingConfirm` becomes `StateFlow<List<TxnRow>>` from `observePendingRows()`;
  `GardenHomeScreen.kt:176`'s `lastPending` becomes `mutableStateOf<TxnRow?>(null)` (drop the
  `TransactionEntity` import) — the card keeps `vm.confirmPending(txn.uuid)` until Task 7.
- [ ] **Step 4: Run.** Emulator: `LedgerTransitionTest` → `Finished 6 tests`; `CrossingEventTest`
  still green; full JVM → `380 tests, 0 failed`.
- [ ] **Step 5: Commit** (Daos.kt, LedgerRepository.kt, MainViewModel.kt, GardenHomeScreen.kt,
  LedgerTransitionTest.kt): `fix: make pending and undo transitions idempotent`.

## Task 7: Pending card, Undo and double-tap guards (R4, UI)

**Interfaces — consumes:** Task 6. **Produces:** `object PendingLabels { fun question(row: TxnRow): String;
fun detail(row: TxnRow, total: Int, zone: ZoneId): String; fun logged(amountPaise: Long, payee: String): String;
fun discarded(amountPaise: Long, payee: String): String }`;
`data class UndoOffer(val message: String, val uuid: String, val from: TxnStatus, val to: TxnStatus)`;
`MainViewModel.undoOffer: StateFlow<UndoOffer?>`, `applyUndo(offer)`, `dismissUndo(offer)`,
`confirmPending(row: TxnRow)`, `discardPending(row: TxnRow)`,
`savePendingFromDraft(amountPaise, severity): Boolean`.

- [ ] **Step 1: Failing tests.** New `test/.../ui/PendingLabelsTest.kt` (zone Asia/Kolkata, a
  `TxnRow` for ₹200 to "Sharma Chai" at 2026-10-18T14:16):
  `the question names the amount and the payee` → `"Did ₹200 to Sharma Chai go through?"`;
  `the detail gives the time, and the queue only when there is one` → total 1 → `"18 Oct, 14:16"`,
  total 3 → `"18 Oct, 14:16 · 1 of 3"`;
  `undo messages say what happened to which payment` → `logged(20_000, "Sharma Chai") == "Logged ₹200 to Sharma Chai"`,
  `discarded(…) == "Discarded ₹200 to Sharma Chai"`.
- [ ] **Step 2: Watch them fail to compile** (`PendingLabels`).
- [ ] **Step 3: Implement.**
  - `PendingLabels` with `DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)`.
  - `MainViewModel`: `confirmPending` / `discardPending` set `undoOffer` only when the repository
    returned true (`LOGGED → PENDING_CONFIRM` and `DISCARDED → PENDING_CONFIRM` respectively);
    `saveManualFromDraft` sets `UndoOffer(logged(amount, payee or "Unknown"), uuid, LOGGED, DISCARDED)`
    after its save; `applyUndo` calls `ledger.revert`; `dismissUndo` clears only that offer.
    `savePendingFromDraft` calls `savePendingOnce` with `requireNotNull(draft.value.txnUuid)` — every
    scan draft carries one (`MainViewModel.kt:123`, `DraftState.kt`).
  - `GardenHomeScreen`: a `SnackbarHost` first in the bottom column; a `LaunchedEffect(offer)` shows
    `offer.message` with action `"Undo"` and `SnackbarDuration.Long`, applying the undo on
    `ActionPerformed`, then dismisses. The pending card renders `PendingLabels.question` and
    `detail(row, pending.size, zone)` inside `key(row.uuid)`, and its two buttons disable after
    the first tap.
  - `EntryScreen`: `var submitting by remember { mutableStateOf(false) }`; "Log it"/"Continue to pay"
    are `enabled = !submitting`, set on a valid submit, reset when validation fails or the gate is
    dismissed (`onDismiss`); the gate's buttons disable after the first tap; `fireAndFinish`
    launches the UPI intent only when `savePendingFromDraft` returned true, and always calls
    `onDone()`.
- [ ] **Step 4: Run.** `testcount PendingLabelsTest` → `3 tests, 0 failed`; full JVM →
  `383 tests, 0 failed`; `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit:** `fix: name the pending payment, offer undo, and ignore double taps`.

## Task 8: The phone's daily build is the release build (R6)

- [ ] **Step 1: Sign release with the debug key.** `app/build.gradle.kts` `release { … }` gains
  `signingConfig = signingConfigs.getByName("debug")` (same key as the phone's install, so it updates
  in place either way).
- [ ] **Step 2: Put the review ledger back.** Tasks 2, 3 and 6 ran `connectedDebugAndroidTest`,
  which uninstalls the app and its data. With `ad() { ~/Library/Android/sdk/platform-tools/adb -s emulator-5554 "$@"; }`
  and `SNAP=<session scratchpad>/rev/db_snapshot`: `installDebug`; launch once and
  `ad shell am force-stop com.expensegarden.app`; `ad push "$SNAP/garden.db" "$SNAP/garden.db-wal" /data/local/tmp/`;
  `ad shell "run-as com.expensegarden.app sh -c 'rm -f databases/garden.db* && cp /data/local/tmp/garden.db /data/local/tmp/garden.db-wal databases/'"`.
  Expected after launch: the year-long island and the review spend on the strip (₹10,750 while it
  is October). If the snapshot is gone (scratchpad cleared), rebuild it: `run-as … sh /data/local/tmp/restore.sh`
  (villa.db), then the four October entries and the ₹15,000 budget through the UI, as on
  2026-10-18.
- [ ] **Step 3: Verify the release build on the emulator.** `ANDROID_SERIAL=emulator-5554 ./gradlew installRelease`
  → `Installed on 1 device.`;
  `ad shell dumpsys package com.expensegarden.app | grep -oE "pkgFlags=\[[^]]*\]"` → no
  `DEBUGGABLE`; launch: the strip shows the same spend as before `installRelease`. Then
  `installDebug` again (the dev loop stays debug).
- [ ] **Step 4: Commit:** `build: sign the release build with the debug key`.

## Task 9: Watch it work, then the docs

- [ ] **Step 1: Real build, review ledger** (as restored in Task 8). Launch with the launcher intent.
  Expected — in October: the October budget row predates `budget.set`, so its floor is its stamp,
  the 18th; the strip no longer reads `🌱17d`, and any `🌱Nd` has N ≤ today's day − 18. From
  1 Nov: the strip shows a budget (carried on this launch) and a `budget.carried` event exists for
  the month. Either way: a crop of the month signs shows OCT and JUL apart (compare
  `rev/signs_crop_prefix.png`); Log manually ₹450 "Chaiwala" Groceries → a snackbar
  `"Logged ₹450 to Chaiwala"` with `"Undo"`; tap Undo → the strip total returns to its prior value.
- [ ] **Step 2: Gate and pending cards** on a throwaway worktree off HEAD with three uncommitted
  edits: `draft.fromScan ->` → `true ->` (EntryScreen); `startManualDraft()` mints
  `txnUuid = UUID.randomUUID().toString()`; `fireAndFinish` passes `draft.vpa ?: "test@ybl"` (the
  emulator has no UPI app, so it toasts "No UPI app found" and returns home). Note the strip's
  spend S and compute today's line L = budget × day ÷ days-in-month × 1.15.
  - Pay `max(₹500, ⌈L − S⌉ + ₹100)` in Shopping, then **Not now**. Expected title:
    `"This puts you further ahead of pace"` if the strip read "ahead of pace"; otherwise
    `"This ends a N-day streak"` with N the strip's 🌱 count, or `"This puts you ahead of pace"` with
    no 🌱. The numbers line has no paise. Skip this check once L + ₹100 exceeds the budget (from
    the 27th of a 31-day month).
  - Pay ₹1 "Corner Shop" in Shopping; if a gate shows, **Pay anyway**. Either way you land home on
    `"Did ₹1 to Corner Shop go through?"` with a time line. Discard → `"Discarded ₹1 to Corner Shop"`
    + Undo; Undo → the card returns.

  Reinstall the real debug build and delete the worktree.
- [ ] **Step 3: Every suite.** `testDebugUnitTest --rerun-tasks` → `383 tests, 0 failed`;
  `ANDROID_SERIAL=emulator-5554 connectedDebugAndroidTest` → `Finished 88 tests`;
  `git status --short app/schemas` → empty. Stop the emulator.
- [ ] **Step 4: Docs.**
  - CLAUDE.md: counts `(383)` / `(88)`; add
    `./gradlew installRelease                    # the phone's daily build: not debuggable, same key`;
    roadmap: after "the review fixes" add `**the November-ready fixes** (done 2026-10-xx — budgets
    carry forward, rewards need a budget in force, an honest gate card, undo, the release build) →`.
  - `2026-10-11-phone-e2e.md`: "Read this first" and Steps 2.4–2.5 quote
    `Did ₹1 to ‹shop name› go through?`; Step 2.6 notes the `"Logged ₹1 to ‹shop name›"` Undo bar
    (don't tap Undo); Step 3.3 expects `"This puts you further ahead of pace"` and whole-rupee
    figures that add up to ₹992 within ₹1; Step 10 adds `ANDROID_SERIAL=<serial> ./gradlew installRelease`
    after the wipe, before setting the real budget.
  - Commit both: `docs: counts, roadmap and phone script for the November fixes` (his go on this
    plan covers this docs commit, as it did for `b1077a7`; this plan file itself stays uncommitted).
- [ ] **Step 5: Report** before/after per item, the suite counts and the commits. No push.

## Out of scope (from the review, for later)

R5 Restore guard (before 2B goes live) · R7 TalkBack semantics · R8 necessity "Back" · R9 weeds
(rulings 3–4) · R10–R16 · R18–R22 · regret-toggle rare farm and the album's no-spend floor ·
floats in the pace allowance · dashboard paisa precision.
