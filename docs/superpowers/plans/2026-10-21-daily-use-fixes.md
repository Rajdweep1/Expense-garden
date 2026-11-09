# Daily-Use Fixes Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans (his standing preference
> for this repo). Steps use checkbox (`- [ ]`) syntax. If a step's output doesn't match its Expected
> line: stop and report.

**Goal:** Before 1 Nov, fix the places where everyday use still trips:
- the necessity card's Back throws away the scan;
- the home strip reads as good news when the month is going wrong;
- the gate ignores scans not yet answered;
- cash payees split on spacing or case;
- the AI card and job freeze until a cold start;
- the all-time fold runs on the main thread.

**Architecture:** Each decision goes where a test can reach it:
- pure Kotlin for the gate's Back (`GateView.backOutReturnsToForm`) and the strip's text
  (`stripLabel`);
- the Room-backed repository, tested on the emulator, for what the gate counts and how cash payees
  match.

Lifecycle and threading change only in `AiViewModel`, `MainActivity` and `GardenRepository`. No
test can reach those, so they get a device smoke check. No entity change; Room stays v4.

**Tech Stack:** Kotlin 2.0.20, Compose, Room 4 (two new DAO queries, no schema change),
kotlinx.coroutines (`flowOn`, `flatMapLatest`).

**Spec:** `docs/superpowers/reviews/2026-10-18-functional-ux-review.md`, items R8, R10, R11, R12
(AI half), R13 and R19. He said "go ahead with the rest of the work" on 2026-10-21. Of R12, the
reconciler half is done: `GardenViewModel.onForeground` runs on every `onStart` since the November
work. Of R11, the "too broad" half is bounded by the fixed-cost cap (ruling 6).

## Rulings (defaults — each is his to overturn)

| # | Question | Default | Why | Cost if wrong |
|---|---|---|---|---|
| 0 | R8: what does a necessity card's Back do? | **Goes back to the form, with the amount kept and nothing recorded.** A new `GateView.backOutReturnsToForm` is true only for `Neutral`. | The 2026-09-17 spec says Back "only dismisses", because backing out of a necessity usually means the amount was wrong. It is a separate property from `recordsDodge`: "is this restraint" and "where does the button go" are different questions that happen to agree today. | None known. |
| 1 | R13: the word | **"over pace" replaces "ahead of pace"** in user-facing copy: the strip, the dashboard, the gate card's headline, and the AI prompt's pace fact (Persona.kt:52). Identifiers (`alreadyAhead`) and the seeded quip stay. | "Ahead" reads as good news, and one word should mean one thing across the app. | It sits next to "over budget", and the amber vs red tint tells them apart. The phone script's Steps 3–4 change with it (Task 7). |
| 2 | R13: the streak on the strip | **Shown only while the month is on pace.** | When the strip says over pace, today's line is already crossed, so today can't extend the streak. Showing it anyway reads as good news. | A streak that is ending is hidden a day early; it shows 0 tomorrow regardless. |
| 3 | R13: the tint | **Amber for "over pace", red for "over budget"**, from `GardenStatus.colors`. Home is wrapped in `GardenTheme(dark = false)` (MainActivity.kt:106), so these are the light tokens in both modes. A new `GardenTokensTest` case pins both at ≥4.5:1 on every sky; the lowest today are 4.69 and 5.97. | The dashboard already tints these; the strip was the odd one out. | None. |
| 4 | R10: what does the gate count? | **This month's LOGGED and PENDING_CONFIRM payments**, in both the scope sums and the fixed-cost math, so a pending rent is fixed just like a logged one. This applies at the gate only. The strip, the dashboard, the garden, detection and crossing events stay LOGGED-only. | A pending payment most likely went through. Judging scan 2 without scan 1 under-warns on exactly the multi-scan trip. The other surfaces report what has been confirmed. | A payment that failed keeps counting at the gate until he discards it. The gate errs strict, never silent. |
| 5 | R11: matching cash payees | **Trim and collapse spaces before saving or matching, and match case-insensitively (`COLLATE NOCASE`).** UPI payees still match on their VPA. No migration of existing rows. | "Chai Point", "  chai   point " and "CHAI POINT" are one payee; splitting them breaks detection and history. His real ledger starts after this ships. | Existing untrimmed cash payees (test data only) stay separate. NOCASE folds ASCII only, and Devanagari has no case, so nothing is lost. |
| 6 | R11's first half: a café paid once a month becomes "fixed" | **No further change.** The cap (a28a297..e7bfc79) already holds its fixed share to 1.25× its smaller month. | Detection can't tell such a café from a subscription by its rows; the cap limits the damage instead. | Up to 1.25× that café's usual bill passes the pace line each month. |
| 7 | R12, the AI half | **`AiViewModel.onForeground()` runs from `MainActivity.onStart`, beside the garden's.** It moves the daily card to today and starts the AI job, one at a time; the `init` launch goes. The card follows the day through `flatMapLatest`, opted in with `@OptIn(ExperimentalCoroutinesApi::class)`. | The job and the card's date were frozen when the ViewModel was created, so an app left in memory showed yesterday's card and wrote no digest until a cold start. `flatMapLatest` has been stable for years, and a new DAO query written only to avoid one annotation would be more code. | One Gemini call per foreground, only when `DigestTrigger` fires (it is scope-unique per day and per month). An experimental API could change on a coroutines bump, and versions are pinned. |
| 8 | R19: where the folds run | **On `Dispatchers.Default`, inside `GardenRepository`.** `observeAllTimeGarden` gets `.flowOn(Dispatchers.Default)`; `foldMonth` and `collection` wrap their bodies in `withContext(Dispatchers.Default)`. | Main-safe repository functions protect every caller: home, the greenhouse, the album, and the AI job's monthly facts. | None. Room reads already leave the main thread. |
| 9 | The orphaned KDoc at GatePresentation.kt:76-82 | **Moves above `severityForLogging`.** | Task 2 rewrites `headline()` right beside it; it is a deferred minor from the November review. | None. |

## Global Constraints

- Every Gradle command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- Device runs use the emulator only: no phone attached, `ANDROID_SERIAL=emulator-5554`.
  - Boot it in the background:
    `~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 -no-boot-anim -gpu host -no-snapshot-save`.
  - It is ready when `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell getprop sys.boot_completed`
    prints `1`.
- No new dependencies, no version bumps, no deprecation "fixes" (CLAUDE.md).
- No entity changes: Room stays v4. `git status --short app/schemas` must stay empty.
- Money is paise as `Long`. Instrumented test names are snake_case; JVM test names use backticks.
- `testcount` (define once per shell; it reads the JVM result XMLs, so run it after a `--rerun`):

```bash
testcount() { python3 -c "import glob,sys,xml.etree.ElementTree as E; rs=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/*.xml') if sys.argv[1] in f]; print(sum(int(r.get('tests')) for r in rs), 'tests,', sum(int(r.get('failures'))+int(r.get('errors')) for r in rs), 'failed')" "$1"; }
```

- Stage named paths only. Plain commit messages with no attribution lines. No push unless asked.

**Done when:** 417 JVM tests (413 + 4) and 99 instrumented tests (96 + 3) pass, `app/schemas` is
untouched, and the device smoke in Task 7 shows the amber "over pace" strip with no streak.

## Review Focus

1. **A necessity card's Back** keeps the typed amount and records no dodge. Pinned by `only a
   necessity's Back returns to the form` together with the existing `a necessity never offers a
   dodge` (Task 1).
2. **A multi-scan trip**: scan 1 is still pending when scan 2 reaches the gate. Pinned by
   `a_pending_payment_counts_at_the_gate`, which also checks that a discarded scan stops counting
   (Task 3).
3. **A pending rent at the gate** counts as fixed, not as spending. If the scope sums counted
   pending payments but the fixed-cost math didn't, the gate would warn on every purchase until
   the rent was answered. Pinned by `a_pending_rent_counts_as_fixed_at_the_gate` (Task 3).
4. **Cash payee spellings** ("Chai Point", "  chai   point ", "CHAI POINT") share one payee and
   keep the first spelling, while a UPI payee with the same name stays separate. Pinned by
   `cash_payees_match_whatever_the_spacing_or_case` (Task 4).
5. **The strip on pace, over pace and over budget**: the words, the streak, and the tint's
   contrast on every sky. Pinned by `the strip shows the streak only while the month is on pace`,
   `the strip asks for a budget when there is none` and `status text over the canvas clears 4_5 to 1
   on every sky` (Task 2). The tint itself is checked on the device (Task 7).

## File structure

All paths are under `app/src/main/java/com/expensegarden/app/`. Tests mirror them under
`app/src/test/…` and `app/src/androidTest/…`.

| File | Change |
|---|---|
| `gate/GatePresentation.kt` | Task 1: `backOutReturnsToForm`. Task 2: headline copy, comment on `Streak`, orphaned KDoc moved |
| `ui/EntryScreen.kt` | Task 1: the quiet button goes back to the form when the view says so |
| `ui/DashboardLabels.kt` | Task 2: "over pace"; new `stripLabel` |
| `ui/GardenHomeScreen.kt` | Task 2: the strip uses `stripLabel` and the status tint; `gardenHint` deleted |
| `ai/Persona.kt`, `ui/DashboardScreen.kt`, `ui/theme/GardenTheme.kt` | Task 2: copy and comments say "over pace" |
| `data/Daos.kt`, `data/LedgerRepository.kt` | Task 3: the gate's month counts pending payments. Task 4: cash payees normalised and matched case-insensitively |
| `ui/AiViewModel.kt`, `MainActivity.kt` | Task 5: the AI job and daily card follow the foreground |
| `data/GardenRepository.kt` | Task 6: folds on `Dispatchers.Default` |

---

## Task 1: A necessity card's Back returns to the form (R8)

**Files:**
- Modify: `gate/GatePresentation.kt` (`GateView`), `ui/EntryScreen.kt:280-284`
- Test: `app/src/test/java/com/expensegarden/app/gate/GatePresentationTest.kt` (15 → 16)

**Interfaces — produces:** `GateView.backOutReturnsToForm: Boolean`, declared on the interface
like `recordsDodge`, so every variant has to answer it.

- [ ] **Step 1: Failing test.** Add after `a necessity never offers a dodge`:

```kotlin
@Test
fun `only a necessity's Back returns to the form`() {
    // Backing out of a necessity usually means the amount was wrong, so it goes back to fix it
    // (spec 2026-09-17: Back "only dismisses"). A discretionary Not now abandons the purchase.
    assertEquals(true, view(Severity.BREACH, isNecessity = true).backOutReturnsToForm)
    assertEquals(true, view(Severity.PACE_WARNING, isNecessity = true).backOutReturnsToForm)
    assertEquals(false, view(Severity.BREACH, isNecessity = false).backOutReturnsToForm)
    assertEquals(false, view(Severity.PACE_WARNING, isNecessity = false).backOutReturnsToForm)
    assertEquals(false, GateView.None.backOutReturnsToForm)
}
```

- [ ] **Step 2: Watch it fail.** Run:
  `./gradlew testDebugUnitTest --rerun --tests 'com.expensegarden.app.gate.GatePresentationTest'`.
  Expected: `BUILD FAILED`, with a compilation error for the unresolved reference
  `backOutReturnsToForm`.
- [ ] **Step 3: Implement.**
  - On `GateView`, add `val backOutReturnsToForm: Boolean`. Its KDoc says why (ruling 0). It is
    `true` on `Neutral` and `false` on `None`, `Weed` and `Streak`.
  - In `EntryScreen`'s `onBackOut`: when `view.backOutReturnsToForm`, do what `onDismiss` does
    (`gate = null; submitting = false`). Otherwise keep today's dodge-and-leave.
- [ ] **Step 4: Run.**
  - The Step 2 command. Expected: `BUILD SUCCESSFUL`, and `testcount GatePresentationTest` →
    `16 tests, 0 failed`.
  - Full JVM: `./gradlew testDebugUnitTest --rerun`, then `testcount Test`. Expected:
    `414 tests, 0 failed`.
- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/gate/GatePresentation.kt app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt app/src/test/java/com/expensegarden/app/gate/GatePresentationTest.kt
git commit -m "fix: send a necessity card's Back to the form instead of home"
```

---

## Task 2: The home strip tells the truth (R13)

**Files:**
- Modify: `ui/DashboardLabels.kt`, `ui/GardenHomeScreen.kt:139-154,289-293`, `gate/GatePresentation.kt:41,76-90`,
  `ai/Persona.kt:52`, comments at `ui/DashboardScreen.kt:252` and `ui/theme/GardenTheme.kt:19`
- Test: `app/src/test/java/com/expensegarden/app/ui/DashboardLabelsTest.kt` (1 → 3),
  `app/src/test/java/com/expensegarden/app/ui/theme/GardenTokensTest.kt` (9 → 10), and the copy in
  `GatePresentationTest` (`:90,99,105,172`), `GateEvaluatorTest:19` (test name) and
  `MonthStatsFolderTest:56` (comment)

**Interfaces — produces:** `internal fun stripLabel(overallBudgetPaise: Long?, hint: Severity,
streakDays: Int): String` in `ui/DashboardLabels.kt`.

- [ ] **Step 1: Failing copy.** Change the expected strings from "ahead of pace" to "over pace":
  - `DashboardLabelsTest:13` → `"over pace"`;
  - `GatePresentationTest:90` → `"This puts Shopping over pace"`;
  - `GatePresentationTest:99` and `:172` → `"This puts you further over pace"`;
  - `GatePresentationTest:105` → `"This puts you over pace"`.

  Rename `GateEvaluatorTest`'s `ahead of pace is PACE_WARNING` to `over pace is PACE_WARNING`, and
  make the comment at `MonthStatsFolderTest:56` say "over pace".

  Run: `./gradlew testDebugUnitTest --rerun --tests 'com.expensegarden.app.gate.GatePresentationTest' --tests 'com.expensegarden.app.ui.DashboardLabelsTest'`.

  Expected: `BUILD FAILED`, `17 tests completed, 5 failed`, all `ComparisonFailure`s:
  - `there is no pace without a budget`;
  - `a category pace warning claims no streak`;
  - `a pace warning already over the line claims no streak`;
  - `with no streak yet the card names only the pace`;
  - `a fixed-cost payment judges already-ahead by the line before it`.
- [ ] **Step 2: Failing strip tests.** Add to `DashboardLabelsTest`:

```kotlin
@Test fun `the strip shows the streak only while the month is on pace`() {
    assertEquals("₹15,000 · on pace · 🌱17d  ›", stripLabel(1_500_000L, Severity.OK, streakDays = 17))
    // Over today's line already: today can't extend the streak, so showing it reads as good news.
    assertEquals("₹15,000 · over pace  ›", stripLabel(1_500_000L, Severity.PACE_WARNING, streakDays = 17))
    assertEquals("₹15,000 · over budget  ›", stripLabel(1_500_000L, Severity.BREACH, streakDays = 17))
    assertEquals("₹15,000 · on pace  ›", stripLabel(1_500_000L, Severity.OK, streakDays = 0))
}

@Test fun `the strip asks for a budget when there is none`() {
    assertEquals("set a budget  ›", stripLabel(null, Severity.OK, streakDays = 0))
}
```

  Add to `GardenTokensTest`, after `chrome over the canvas clears 4_5 to 1 on every sky`:

```kotlin
/** The home strip tints "over pace" and "over budget" (review R13, 2026-10-18). Home is light in
 *  both modes (MainActivity's GardenTheme(dark = false)), so these are the light tokens. */
@Test
fun `status text over the canvas clears 4_5 to 1 on every sky`() {
    for (weather in Weather.values()) {
        for (stop in GardenPalette.sky(weather)) {
            val sky = stop.toArgb() and 0xFFFFFF
            val backdrop = ContrastMath.over(0xFFFFFF, GardenTokens.CanvasChromeAlpha, sky)
            assertContrast("warning over $weather #%06X".format(sky), GardenTokens.LightWarning, backdrop, 4.5)
            assertContrast("over-budget over $weather #%06X".format(sky), GardenTokens.LightOver, backdrop, 4.5)
        }
    }
}
```

  Run the Step 1 command plus `--tests 'com.expensegarden.app.ui.theme.GardenTokensTest'`.
  Expected: `BUILD FAILED`, with a compilation error for the unresolved reference `stripLabel`.
- [ ] **Step 3: Implement.**
  - `paceLine` returns `"over pace"` for `PACE_WARNING`.
  - `stripLabel` returns `"set a budget  ›"` without a budget. Otherwise it returns
    `"<Money.display(budget)> · <paceLine>"`, plus `" · 🌱<n>d"` only when `hint == OK` and
    `streakDays > 0`, plus `"  ›"`.
  - In the home strip, the label is `stripLabel(h.overallBudgetPaise, h.hint, streak)`. Its colour
    comes from `GardenStatus.colors`: `warning` for `PACE_WARNING` and `over` for `BREACH`, but
    only when a budget exists. Otherwise it keeps the inherited `onSurface`. Delete `gardenHint`.
  - `headline()` says "over pace" in all four branches. `Streak`'s KDoc (`:41`) begins "Over pace
    on a discretionary purchase".
  - `Persona.kt:52`'s fact says "over pace", and so do the comments at `DashboardScreen.kt:252`
    and `GardenTheme.kt:19`.
  - Move the orphaned KDoc (`:76-82`, "What to flag on the saved transaction…") above
    `severityForLogging` (ruling 9).
- [ ] **Step 4: Run.**
  - The Step 2 command. Expected: `BUILD SUCCESSFUL`, and `testcount GatePresentationTest` →
    `16 tests, 0 failed`, `testcount DashboardLabelsTest` → `3 tests, 0 failed`, and
    `testcount GardenTokensTest` → `10 tests, 0 failed`.
  - Full JVM. Expected: `417 tests, 0 failed`.
  - `grep -rn "ahead of pace" app/src/main` prints nothing.
- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/ui/DashboardLabels.kt app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt app/src/main/java/com/expensegarden/app/gate/GatePresentation.kt app/src/main/java/com/expensegarden/app/ai/Persona.kt app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt app/src/main/java/com/expensegarden/app/ui/theme/GardenTheme.kt app/src/test/java/com/expensegarden/app/ui/DashboardLabelsTest.kt app/src/test/java/com/expensegarden/app/ui/theme/GardenTokensTest.kt app/src/test/java/com/expensegarden/app/gate/GatePresentationTest.kt app/src/test/java/com/expensegarden/app/gate/GateEvaluatorTest.kt app/src/test/java/com/expensegarden/app/stats/MonthStatsFolderTest.kt
git commit -m "fix: say over pace, tint it, and show the streak only while on pace"
```

---

## Task 3: The gate counts payments still waiting for an answer (R10)

**Files:**
- Modify: `data/Daos.kt` (`TransactionDao`), `data/LedgerRepository.kt:160,206`
- Test: `app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt` (11 → 13)

**Interfaces — produces:**
- `TransactionDao.loggedOrPendingSumsByCategory(fromMillis: Long, toMillis: Long): List<CategorySum>`.
- `TransactionDao.loggedOrPendingBetween(fromMillis: Long, toMillis: Long): List<TransactionEntity>`.

Both have `status IN ('LOGGED', 'PENDING_CONFIRM')` and otherwise mirror `loggedSumsByCategory` and
`loggedBetween`.

- [ ] **Step 1: Failing tests.** Append to `GateScopeTest`:

```kotlin
@Test fun a_pending_payment_counts_at_the_gate() = runBlocking {
    fun noonOn(d: Int) = LocalDate.of(2026, 11, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-11", amountPaise = 1_500_000, updatedAt = 1L))  // ₹15,000
    // Scan 1: ₹1,000 at a shop, paid but not yet answered.
    val first = repo.savePending(
        LedgerRepository.Draft(vpa = "shop@ybl", payeeName = "Shop", amountPaise = 100_000, categoryId = 7,
            note = null, occurredAt = noonOn(2)),
        breachedAtLogging = false,
    )
    // Scan 2, ₹200 on 2 Nov: ₹1,200 against a ₹1,150 line is a warning. Without scan 1 it was ₹200.
    assertEquals(Severity.PACE_WARNING, repo.evaluateGate(7, 20_000, noonOn(2), ledgerBeganAt = null, payeeVpa = "shop@ybl").severity)
    // Discarded, scan 1 counts nowhere.
    repo.discard(first)
    assertEquals(Severity.OK, repo.evaluateGate(7, 20_000, noonOn(2), ledgerBeganAt = null, payeeVpa = "shop@ybl").severity)
}

@Test fun a_pending_rent_counts_as_fixed_at_the_gate() = runBlocking {
    fun noonOn(m: Int, d: Int) = LocalDate.of(2026, m, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    fun rent(m: Int, d: Int) = LedgerRepository.Draft(vpa = "landlord@ybl", payeeName = "Landlord", amountPaise = 1_800_000,
        categoryId = 101, note = null, occurredAt = noonOn(m, d))
    db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-11", amountPaise = 4_500_000, updatedAt = 1L))  // ₹45,000
    repo.saveManualLogged(rent(10, 1), breachedAtLogging = false)    // October's rent: a ₹22,500 cap in November
    repo.savePending(rent(11, 1), breachedAtLogging = false)         // November's, not yet answered
    // ₹1,000 on 2 Nov. The pending rent is fixed, so the line is ₹20,070 and ₹19,000 is under it.
    // Counted as spending but not as fixed, it would be a warning against a ₹3,450 line.
    assertEquals(Severity.OK, repo.evaluateGate(101, 100_000, noonOn(11, 2), ledgerBeganAt = null, payeeVpa = null).severity)
}
```

  - Boot the emulator (Global Constraints).
  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GateScopeTest`.
  - Expected: `BUILD FAILED`, with 1 of 13 failed: `a_pending_payment_counts_at_the_gate`, with
    `expected:<PACE_WARNING> but was:<OK>`.
  - `a_pending_rent_counts_as_fixed_at_the_gate` passes today. It is a guard that fails only if
    pending payments reach the sums without reaching the fixed-cost math (Review Focus 3).
- [ ] **Step 2: Implement.**
  - Add the two queries.
  - `scopeInputs` sums with `loggedOrPendingSumsByCategory`.
  - `evaluateGate` reads its month from `loggedOrPendingBetween`, mapped to `LOGGED` copies for
    the fixed-cost math (`fixedSpentPaise` and `newPaymentShare` count LOGGED rows only). A
    one-line comment says why (ruling 4).
  - `recurringCaps`, `emitCrossings` and every other caller keep their LOGGED queries.
- [ ] **Step 3: Run.**
  - The Step 1 command. Expected: `Finished 13 tests`, `BUILD SUCCESSFUL`.
  - Full JVM. Expected: `417 tests, 0 failed`.
- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/data/Daos.kt app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt
git commit -m "fix: count payments still waiting for an answer at the gate"
```

---

## Task 4: Cash payees match whatever the spacing or case (R11)

**Files:**
- Modify: `data/Daos.kt:34-35` (`PayeeDao.cashPayeeByName`), `data/LedgerRepository.kt:257-264`
  (`resolvePayee`)
- Test: `app/src/androidTest/java/com/expensegarden/app/data/LedgerTransitionTest.kt` (6 → 7)

- [ ] **Step 1: Failing test.** Append to `LedgerTransitionTest`:

```kotlin
@Test fun cash_payees_match_whatever_the_spacing_or_case() = runBlocking {
    fun cash(name: String, vpa: String? = null) = LedgerRepository.Draft(vpa = vpa, payeeName = name, amountPaise = 10_000,
        categoryId = 103, note = null, occurredAt = 1_000L)
    val uuids = listOf("Chai Point", "  chai   point ", "CHAI POINT").map { repo.saveManualLogged(cash(it), breachedAtLogging = false) }
    val payeeIds = uuids.map { db.transactionDao().byUuid(it)!!.payeeId }
    assertEquals(1, payeeIds.toSet().size)
    assertEquals("Chai Point", db.transactionDao().rowByUuid(uuids.last())?.payeeName)   // the first spelling is kept
    // A UPI payee with the same name is someone else: those match on their VPA.
    val upi = repo.saveManualLogged(cash("Chai Point", vpa = "chai@ybl"), breachedAtLogging = false)
    assertNotEquals(payeeIds.first(), db.transactionDao().byUuid(upi)!!.payeeId)
}
```

  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.LedgerTransitionTest`.
  - Expected: `BUILD FAILED`, with 1 of 7 failed: `cash_payees_match_whatever_the_spacing_or_case`,
    with `expected:<1> but was:<3>`.
- [ ] **Step 2: Implement.**
  - `cashPayeeByName` matches with `name = :name COLLATE NOCASE`.
  - `resolvePayee` normalises a cash payee's name before the lookup and before the insert: trim,
    and collapse inner whitespace to one space. UPI payees are untouched.
- [ ] **Step 3: Run.**
  - The Step 1 command. Expected: `Finished 7 tests`, `BUILD SUCCESSFUL`.
  - Full JVM. Expected: `417 tests, 0 failed`.
- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/data/Daos.kt app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/androidTest/java/com/expensegarden/app/data/LedgerTransitionTest.kt
git commit -m "fix: match cash payees regardless of spacing and case"
```

---

## Task 5: The AI job and the daily card follow the foreground (R12, AI half)

**Files:**
- Modify: `ui/AiViewModel.kt:33-40`, `MainActivity.kt:66-71`

No test can reach this: it is ViewModel lifecycle wiring around a job whose LLM call needs a key
the emulator doesn't have. It is checked by the full suites and the device smoke in Task 7.

- [ ] **Step 1: Implement.**
  - `AiViewModel` keeps today's date key in a `MutableStateFlow`.
  - `dailyCard` is `dayKey.flatMapLatest { container.digests.observeDaily(it) }`, with
    `@OptIn(ExperimentalCoroutinesApi::class)` (ruling 7). Delete the comment about resolving the
    key once.
  - New `fun onForeground()`: set the key to today, then start `runAiJob()` unless a run is still
    active. Remove the `init` launch.
  - `MainActivity.onStart` calls `aiVm.onForeground()` after `gardenVm.onForeground()`, and the
    comment there covers both.
- [ ] **Step 2: Run.**
  - Full JVM. Expected: `417 tests, 0 failed`.
  - `./gradlew assembleDebug`. Expected: `BUILD SUCCESSFUL`, with no new warning from these two
    files.
- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/ui/AiViewModel.kt app/src/main/java/com/expensegarden/app/MainActivity.kt
git commit -m "fix: run the AI job and move the daily card on every foreground"
```

---

## Task 6: Folds run off the main thread (R19)

**Files:**
- Modify: `data/GardenRepository.kt` (`observeAllTimeGarden` `:99-124`, `collection` `:167`,
  `foldMonth` `:220`)

As with Task 5, no test can observe the thread. It is checked by the full suites and the device
smoke in Task 7.

- [ ] **Step 1: Implement.**
  - `observeAllTimeGarden` returns its `combine(…)` with `.flowOn(Dispatchers.Default)`.
  - `collection` and `foldMonth` run their bodies in `withContext(Dispatchers.Default)`.
  - The class KDoc, or the first of these functions, gets one line on why: these are the
    repository's folds, and main-safety belongs with them (ruling 8).
- [ ] **Step 2: Run.**
  - Full JVM. Expected: `417 tests, 0 failed`.
  - `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GardenRepositoryTest`.
    Expected: `Finished 9 tests`, `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/data/GardenRepository.kt
git commit -m "fix: fold the garden off the main thread"
```

---

## Task 7: Every suite, the device smoke, then the counts

- [ ] **Step 1: Every suite.**
  - `./gradlew testDebugUnitTest --rerun-tasks`, then `testcount Test`. Expected:
    `417 tests, 0 failed`, with result files from this run.
  - `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`. Expected:
    `Finished 99 tests`.
  - `git status --short app/schemas`. Expected: empty.
- [ ] **Step 2: Device smoke** on the emulator, after the instrumented run (it uninstalls the app).
  - `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`, then launch the app.
  - Set an overall budget of ₹15,000 on the dashboard. Log one manual Shopping payment between
    today's line (₹15,000 × day ÷ days in month × 1.15) and ₹15,000.
  - Read the strip with `uiautomator dump`. Expected: the text `₹15,000 · over pace  ›`, and no
    `🌱`. Take a screenshot; expected: that label is amber.
  - Open the greenhouse, a past month and the album, then return home. Expected: each renders, and
    `adb logcat -d -s AndroidRuntime:E` shows no `FATAL`.
  - Stop the emulator: `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 emu kill`.
- [ ] **Step 3: Docs.**
  - `CLAUDE.md`: `# JVM unit tests (417)` and `# instrumented (99)`.
  - `docs/superpowers/plans/2026-10-11-phone-e2e.md`:
    - Step 0's count strings become `Run the 99 instrumented tests` and
      `` `tests 99 failures 0 errors 0` ``.
    - Step 3's heading and expectations say "over pace" instead of "ahead of pace", and "Step 3.1
      already put you over".
    - Step 4.1 reads: strip **₹1,000 · over pace**. ₹1,000 is not more than ₹1,000, so this entry
      grows an ordinary plant, not a weed.
  - Commit both with the message `docs: counts and phone script for the daily-use fixes`. His go on
    this plan covers this docs commit, as before. This plan file stays uncommitted.
- [ ] **Step 4: Report.** Give before and after for each item, the suite counts, the smoke
  screenshot and the commits. No push.

## After this plan: the rest of the review, in order

Each of these gets its own plan, which comes to him before any code:

1. **Settings tells the truth (before 2B):**
   - R5: Restore shows the server's and the phone's counts, including unsynced changes, and
     refuses an empty snapshot over a non-empty ledger.
   - R15: backup status tells off, 401, a bad URL and offline apart.
   - R16: a new Gemini key or voice applies at once, and a bad key says so.
2. **Sync identity (before 2B):** R22. Device-local payee and event ids are server keys. This
   needs a design across Go and Android.
3. **Weeds:** R9, with the review's rulings 3–4 (compost, and weeds from manual logs). This needs
   a design.
4. **Accessibility and visuals:**
   - R7: TalkBack and the garden.
   - R14: the greenhouse month view.
   - R20: light-mode contrast and chip checkmarks.
   - R21: landscape layout and tap targets.
5. **Render cost and polish:**
   - R18: cache the garden's static ground.
   - The review's severity-1 list.
   - The deferred minors:
     - crossing events ignore fixed costs (`LedgerRepository.emitCrossings`);
     - `GateScopeTest`'s four helper copies;
     - `foldAllTime`'s caps are untested, and the 4/5 threshold is unpinned;
     - the `houseLevel` KDoc;
     - the undo bar re-shows;
     - `carryForward`'s JSON parse is unguarded;
     - "₹0 over" under 50 paise;
     - the release build can't reach http core-api.

## Out of scope

- The rows of review R11 already bounded by the cap (ruling 6), and merging existing duplicate cash
  payees (ruling 5).
- Counting pending payments anywhere but the gate (ruling 4).
- Everything in "After this plan".
