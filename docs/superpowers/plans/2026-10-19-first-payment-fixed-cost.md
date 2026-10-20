# First-Payment Fixed Cost Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans (his standing preference
> for this repo). Steps use checkbox (`- [ ]`) syntax. If a step's output doesn't match its Expected
> line: stop and report.

**Goal:** Before 1 Dec, only a recurring payee's **first** logged payment of the month counts as a
fixed cost — in the strip, the dashboard, the streaks and the gate — so a merchant misread as
"recurring" can slip at most one payment a month past the pace line.

**Architecture:** One pure selection, `RecurringPayees.fixedPayments`, decides which of a month's
payments are fixed; `fixedSpentPaise` (every monthly fixed total) and the three per-day fixed slices
call it instead of filtering by payee. The gate's own payment is fixed only when its recurring
payee has no logged payment yet this month — the same answer the selection gives once it is logged.
No schema change; Room stays v4.

**Tech Stack:** Kotlin 2.0.20, Room 4 (existing DAO queries only), JUnit 4.

**Spec:** the model recommendation of the 2026-10-19 final review of the fixed-cost branch (ruled
there as his call, then approved: "write the first-payment plan"). The plan carries it:

> Count only a recurring payee's first LOGGED payment each month as fixed, everywhere —
> `RecurringPayees.fixedSpentPaise`, the per-day slices in GardenFolder and
> `currentUnderPaceStreak`, and the gate's payment (fixed only when that payee has no LOGGED payment
> yet this month). It matches `detect`'s own "exactly one per month" definition
> (`RecurringPayees.kt:18`), keeps gate and strip agreeing, and limits a false positive to one
> payment per merchant per month. Cost: rent paid in two instalments loses the second from the fixed
> term.

**Why now.** `RecurringPayees.detect` calls a payee recurring when it was paid exactly once in each
complete look-back month. On the phone's fresh ledger December has one complete month, November, so
every payee paid exactly once in November is "recurring" in December — and today *every* December
payment to it counts as fixed (`RecurringPayees.kt:89-92`), marketplaces that use one VPA for all
orders included. Since `2ce5756` the gate agrees, so none of those payments can raise a pace warning.

## Rulings (defaults — each is his to overturn)

| # | Question | Default | Why | Cost if wrong |
|---|---|---|---|---|
| 1 | A second payment to a recurring payee in the same month | **Variable spend.** | `detect` recognises a fixed cost by "paid exactly once a month"; a second payment is by definition not that pattern, and a marketplace's many orders are the false positive to contain. | Rent paid in two instalments, or rent plus a deposit to the same landlord: the second counts against the pace line that month. **And when the smaller payment comes first** (₹500 to the landlord before rent, rent split in two), the rent itself is the variable one: about two weeks of pace dialogs on QR payments, an overcast strip, a cut streak and a ballooned projection (final review: ₹500 then ₹18,000 rent on a ₹45,000 budget → warnings on days 1–17, ₹1,29,500 projected on day 5). |
| 2 | Which payment is "first" | **The earliest `occurredAt`, `uuid` breaking ties, LOGGED rows only.** | Deterministic whatever order a caller's rows arrive in, so the strip, dashboard, folds and live streak pick the same row; LOGGED-only matches `detect` and `fixedSpentPaise` today. | A first attempt still pending when he pays the same payee again: the gate treats the retry as the fixed one; if both are logged later, the earlier becomes the fixed one, so the gate's verdict on the retry was lenient by one payment. Confirming an earlier-dated pending payment after a later one was logged moves the fixed slot to it; if it is the smaller one, the strip turns overcast and the live streak drops with no dialog to explain it (undoing the confirm restores both). |
| 3 | How the gate knows its payment would be first | **No LOGGED payment to that payee yet this month.** | Exact for QR drafts, which are dated now; only manual drafts can be backdated (`EntryScreen.kt:139`), and the manual path reads only BREACH. | None reachable. |
| 4 | `fixed spend sums only this month's recurring payees` asserts the old rule ("same payee again, still counted", ₹18,005) | **Rewritten** to assert ruling 1. | It pins exactly the behaviour this plan changes. | As ruling 1. |
| 5 | `LedgerRepository.fixedSpentPaise(monthKey, recurring)` | **Folded into `evaluateGate`**, its only caller, which now needs the month's LOGGED rows for both the fixed term and the first-payment check. Its rationale moves with it as a comment. | One read of the month instead of two. | None. |
| 6 | A test for `GardenRepository.currentUnderPaceStreak`'s call site | **None of its own.** | The live streak's answer depends on today's date; the shared selection and both folds are pinned, and the call-site change is one line. | A wiring slip there makes the strip's streak disagree with the folds; the final review reads it. |
| 7 | December's first payment to a payee paid once in November | **Still fixed.** This plan caps the leak; it doesn't close it. | Telling a once-a-month restaurant from rent with one month of history needs another signal (an amount check against last month's payment) — a separate design. | The review's own example — a ₹10,000 first marketplace order on 3 Dec — still passes as fixed if it is that payee's first payment in December. One payment per such payee per month escapes until January brings a second complete month. |

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

**Done when:** 400 JVM tests (398 + 2) and 93 instrumented (92 + 1) pass, and `app/schemas` is
untouched.

## Review Focus

1. **A second payment to a recurring payee in one month** is variable everywhere. The monthly totals
   (strip, dashboard, gate's logged term) are pinned by `only a recurring payee's first payment of
   the month is fixed`; both folds' streaks by `a second payment to a recurring payee counts
   against the line`; the gate's own payment by
   `a_second_payment_to_a_recurring_payee_in_a_month_is_variable` (Tasks 1–2).
2. **Two payments to the same payee on the same day** — every caller must pick the same one, whatever
   order its rows arrive in. Pinned by `the first payment is chosen the same way whatever order the
   rows arrive in` (Task 1).
3. **An earlier payment to the payee that was discarded or is still pending** is not "first". Pinned
   for the totals by the existing `fixed spend ignores non-LOGGED rows`; the gate reads
   `TransactionDao.loggedBetween`, LOGGED rows only (ruling 2).
4. **A recurring payee's only payment of the month** stays fixed — the M4/M5 behaviour must survive.
   Pinned by the existing `a_payment_to_a_recurring_payee_is_paced_as_the_fixed_cost_it_becomes`,
   `the month fold paces its streak against the month's fixed costs` and
   `the_month_fold_paces_its_streak_against_rent`, which must stay green unchanged.
5. **December's first payment to a payee paid once in November** still passes as fixed — ruling 7,
   deliberately not pinned.

## File structure

| File | Change |
|---|---|
| `stats/RecurringPayees.kt` | + `fixedPayments`; `fixedSpentPaise` sums it |
| `game/GardenFolder.kt` | both folds' per-day fixed slice from `fixedPayments` (`:77`, `:178`) |
| `data/GardenRepository.kt` | `currentUnderPaceStreak`'s per-day fixed slice from `fixedPayments` (`:274`) |
| `data/LedgerRepository.kt` | `evaluateGate`: one read of the month, first-payment check; `fixedSpentPaise` wrapper removed |

All paths are under `app/src/main/java/com/expensegarden/app/`; tests mirror them under `app/src/test/…`
and `app/src/androidTest/…`.

---

## Task 1: A month's fixed cost is each recurring payee's first payment

**Files:**
- Modify: `stats/RecurringPayees.kt:84-92`, `game/GardenFolder.kt:77,178`, `data/GardenRepository.kt:274`
- Test: `app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt` (one rewritten, +1),
  `app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt` (+1)

**Interfaces — produces:**
`RecurringPayees.fixedPayments(monthTxns: List<TransactionEntity>, recurring: Set<Long>): List<TransactionEntity>`
— the month's fixed-cost payments: each recurring payee's earliest LOGGED row by
(`occurredAt`, `uuid`). `fixedSpentPaise(monthTxns, recurring)` keeps its signature and sums it.

- [ ] **Step 1: Failing tests.** `RecurringPayeesTest` — replace `fixed spend sums only this month's
  recurring payees` (ruling 4) with these two:

```kotlin
@Test fun `only a recurring payee's first payment of the month is fixed`() {
    val month = listOf(
        txn(1, 2026, 9, 9, amountPaise = 500),          // the same payee again: variable spend
        txn(1, 2026, 9, 1, amountPaise = 1_800_000),    // rent: the month's first payment to it
        txn(2, 2026, 9, 3, amountPaise = 45_000),       // lunch, not recurring
    )
    assertEquals(1_800_000L, RecurringPayees.fixedSpentPaise(month, setOf(1L)))
    assertEquals(listOf(1_800_000L), RecurringPayees.fixedPayments(month, setOf(1L)).map { it.amountPaise })
}

@Test fun `the first payment is chosen the same way whatever order the rows arrive in`() {
    // Same day, so the uuid breaks the tie, and every caller must pick the same row.
    val small = txn(1, 2026, 9, 1, amountPaise = 500)
    val rent = txn(1, 2026, 9, 1, amountPaise = 1_800_000)
    assertEquals(listOf(rent), RecurringPayees.fixedPayments(listOf(small, rent), setOf(1L)))
    assertEquals(listOf(rent), RecurringPayees.fixedPayments(listOf(rent, small), setOf(1L)))
}
```

  `GardenFolderTest`:

```kotlin
@Test fun `a second payment to a recurring payee counts against the line`() {
    // ₹10,000 rent on the 1st is the month's fixed cost; another ₹10,000 to the landlord on the
    // 2nd is spending, and it keeps every later day over a ₹30,000 budget's line.
    val juneRent = txn(2, day = 1, paise = 1_000_000, month = 6).copy(payeeId = 77)
    val july = listOf(
        txn(2, day = 1, paise = 1_000_000).copy(payeeId = 77),
        txn(2, day = 2, paise = 1_000_000).copy(payeeId = 77),
    )
    val budget = listOf(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 3_000_000, updatedAt = 1L))
    val today = LocalDate.of(2026, 7, 10)
    assertEquals(0, fold(july, budget, today = today, recurringPayeeIds = setOf(77L)).streakDays)
    assertEquals(0, foldAll(listOf(juneRent) + july, budget, today = today).streakDays)   // detects 77 from June
}
```

- [ ] **Step 2: Stub, wire, watch them fail.** Add `fixedPayments` returning today's selection
  (`monthTxns.filter { it.status == TxnStatus.LOGGED && it.payeeId in recurring }`), make
  `fixedSpentPaise` sum it, and switch the three per-day slices to it:
  `GardenFolder.kt:77` → `dayTotalsOf(RecurringPayees.fixedPayments(ordered, recurringPayeeIds), zone)`,
  `GardenFolder.kt:178` → `dayTotalsOf(RecurringPayees.fixedPayments(currentTxns, recurring), zone)`,
  `GardenRepository.kt:274` → `byDay(RecurringPayees.fixedPayments(monthTxns, recurring))`.
  Run: `./gradlew testDebugUnitTest --rerun --tests 'com.expensegarden.app.stats.RecurringPayeesTest' --tests 'com.expensegarden.app.game.GardenFolderTest'`.
  Expected: `BUILD FAILED`, 3 failures — `only a recurring payee's first payment of the month is fixed`
  (`expected:<1800000> but was:<1800500>`), `the first payment is chosen the same way…` (its first
  `assertEquals`), `a second payment to a recurring payee counts against the line`
  (`expected:<0> but was:<9>`).
- [ ] **Step 3: Implement `fixedPayments`.** LOGGED rows of recurring payees, sorted by
  (`occurredAt`, `uuid`), first per `payeeId`. KDoc, one line each on `fixedPayments` and
  `fixedSpentPaise`: why only the first counts (ruling 1).
- [ ] **Step 4: Run.** Same command. Expected: `BUILD SUCCESSFUL`; `testcount RecurringPayeesTest` →
  `15 tests, 0 failed`; `testcount GardenFolderTest` → `19 tests, 0 failed`. Full JVM
  (`./gradlew testDebugUnitTest --rerun`, then `testcount Test`) → `400 tests, 0 failed`.
- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/stats/RecurringPayees.kt app/src/main/java/com/expensegarden/app/game/GardenFolder.kt app/src/main/java/com/expensegarden/app/data/GardenRepository.kt app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt
git commit -m "fix: count only a recurring payee's first payment of the month as fixed"
```

---

## Task 2: The gate's payment is fixed only as its payee's first of the month

**Files:**
- Modify: `data/LedgerRepository.kt:178-226`, the comment at `data/GardenRepository.kt:268`
- Test: `app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt` (+1)

**Interfaces:**
- Consumes: `RecurringPayees.fixedSpentPaise(monthTxns, recurring)` (Task 1).
- Produces: `LedgerRepository.evaluateGate` (signature unchanged); `LedgerRepository.fixedSpentPaise`
  removed (ruling 5).

- [ ] **Step 1: Failing integration test.** It compiles against today's code. `GateScopeTest`:

```kotlin
@Test fun a_second_payment_to_a_recurring_payee_in_a_month_is_variable() = runBlocking {
    fun noonOn(m: Int, d: Int) = LocalDate.of(2026, m, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-11", amountPaise = 1_500_000, updatedAt = 1L))  // ₹15,000
    suspend fun paid(vpa: String?, payee: String, paise: Long, m: Int, d: Int) = repo.saveManualLogged(
        LedgerRepository.Draft(vpa = vpa, payeeName = payee, amountPaise = paise, categoryId = 101, note = null,
            occurredAt = noonOn(m, d)),
        breachedAtLogging = false,
    )
    paid("tiffin@ybl", "Tiffin", 100_000, 10, 2)   // once in October: recurring in November
    paid("tiffin@ybl", "Tiffin", 100_000, 11, 1)   // November's first tiffin bill: the fixed one
    paid(null, "Chai", 30_000, 11, 1)
    // A second ₹1,000 tiffin bill is spending: ₹2,300 against the ₹2,073 line is a warning.
    assertEquals(
        Severity.PACE_WARNING,
        repo.evaluateGate(101, 100_000, noonOn(11, 2), ledgerBeganAt = null, payeeVpa = "tiffin@ybl").severity,
    )
}
```

  Run: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GateScopeTest`.
  Expected: 1 failure, `a_second_payment_to_a_recurring_payee_in_a_month_is_variable` with
  `expected:<PACE_WARNING> but was:<OK>`; the other 7 pass.
- [ ] **Step 2: Implement.** `evaluateGate` reads the month's LOGGED rows once
  (`db.transactionDao().loggedBetween(from, to)` over `boundsOfMonth(monthKey)`), passes
  `RecurringPayees.fixedSpentPaise(monthTxns, recurring)` as the fixed term, and
  `candidateFixedPaise = amountPaise` only when the payee exists, is in `recurring`, and no row of
  `monthTxns` has its `payeeId`. Delete `LedgerRepository.fixedSpentPaise`; keep its two reasons
  (the day-1 rent dialog, and scoping to the payment's own month for backdating) as one comment at
  the fixed term. `GardenRepository.kt:268`'s comment points at `RecurringPayees.fixedPayments`
  instead of the deleted function.
- [ ] **Step 3: Run.** The Step 1 command. Expected: `Finished 8 tests`, `BUILD SUCCESSFUL`. Full JVM
  → `400 tests, 0 failed`.
- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/main/java/com/expensegarden/app/data/GardenRepository.kt app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt
git commit -m "fix: count a recurring payee's payment as fixed only when it is the month's first"
```

---

## Task 3: Every suite, then the counts

- [ ] **Step 1: Every suite.** `./gradlew testDebugUnitTest --rerun-tasks`, then `testcount Test` →
  `400 tests, 0 failed` (result files from this run);
  `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest` → `Finished 93 tests`;
  `git status --short app/schemas` → empty. Stop the emulator.
- [ ] **Step 2: Docs.** `CLAUDE.md`: `# JVM unit tests (400)` and `# instrumented (93)`.
  `docs/superpowers/plans/2026-10-11-phone-e2e.md` Step 0: `Run the 93 instrumented tests` and
  `` `tests 93 failures 0 errors 0` ``. Commit both: `docs: test counts for the first-payment rule`
  (his go on this plan covers this docs commit, as before; this plan file stays uncommitted).
- [ ] **Step 3: Report.** Before/after for the three places the rule lands, the suite counts and the
  commits. No push.

## Out of scope

The amount check that would close ruling 7 (a first payment far above last month's to the same
payee) · requiring two complete look-back months · the deferred minors from the fixed-cost review
(the `houseLevel` KDoc, `fold`'s defaulted `recurringPayeeIds`) · R5, R7, R8–R22.
