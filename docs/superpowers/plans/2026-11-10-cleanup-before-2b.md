# Cleanup Before 2B Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans (his standing preference
> for this repo). Steps use checkbox (`- [ ]`) syntax. If a step's output doesn't match its Expected
> line: stop and report.

**Goal:** Close the settings review's deferred minors before the backup server goes live:
- no token in logcat;
- every failure named for what he'd fix;
- a saved key or voice takes effect at once;
- restore's counts mean "payments";
- the release build's "http:// blocked" label proven on that build.

**Architecture:** Decisions stay in pure, JVM-tested functions:
- `SyncFailure.classify`;
- a new `syncLogLine`;
- `KeyCheck.classify`;
- `RestorePlan`.

Lifecycle ordering changes in `AiViewModel`, `GardenViewModel` and `MainActivity`; no test reaches
those. The cleartext label is proven by hand, on a release build against a local core-api. No Room
schema change, and no server change.

**Tech Stack:** Kotlin 2.0.20, Compose, Room 4 (one new DAO count query, no schema change),
HttpURLConnection.

**Spec:** the deferred minors of the settings review (2026-11-10, recorded in the ledger and in memory),
plus the daily-use review's deferred ordering minor. He said "okk go ahead" to this plan's order on
2026-11-10.

| Ref | Finding |
|---|---|
| M-2 | Save compares and checks the Gemini key untrimmed (SettingsScreen.kt:230,239), while `AiPrefs.apiKey` stores it trimmed (AiPrefs.kt:24). |
| M-3 | A changed key's `onAiChanged()` runs only after the key and connection checks finish (SettingsScreen.kt:236-250). Leaving Settings cancels it. |
| M-4 | `AiViewModel.onForeground` drops a call while a job runs (AiViewModel.kt:52), so a voice tapped mid-job waits for the next foreground. |
| Daily-use minor | `MainActivity.onStart` starts the garden and AI jobs together (MainActivity.kt:71-72), so on the 1st the AI job can miss `month.closed` and the postcard waits a day. |
| M-5 | A 3xx and a `ftp://` url read "server error"; a bad character in the token reads "that isn't a url"; a Gemini 429 or 404 reads "couldn't reach" (SyncFailure.kt:20-28, KeyCheck.kt:10-14). |
| M-6 | `logFailure` logs `e.message` (SyncClient.kt:112-114). For an IllegalArgumentException from a header setter, that may hold the token (unverified). |
| M-7 | "1 payments" / "1 changes" (RestorePlan.kt:24-34). Counts include DISCARDED rows on both sides (`txnCount`, Daos.kt:347; `snap.txns.size`), so a server holding only discarded rows isn't "empty". |
| Unverified | Whether a release build's blocked cleartext really surfaces as `UnknownServiceException`. |

## Rulings (defaults — each is his to overturn)

| # | Question | Default | Why | Cost if wrong |
|---|---|---|---|---|
| 0 | What a log line may carry | **The verb, the HTTP code or the exception class, and an IOException's message. Any other exception's message is dropped.** | IOException messages name hosts and timeouts, which is what debugging needs. Header and argument exceptions can quote the header, and the header is "Bearer <token>". | Less detail for non-IO failures; the class name still says which. |
| 1 | New failure names | **`REDIRECTED` (any 3xx: "the server redirected — use its final url, likely https") and `INVALID_CHARACTER` (`IllegalArgumentException`: "the url or token has a character it can't send").** `ClassCastException` (a non-http scheme) joins `BAD_URL`. | Each needs a different fix from him. | None. |
| 2 | Key check codes | **429 → `WORKS`** (a rate limit proves the key was accepted). **404 → new `MODEL_MISSING`** ("Key works, but this app's Gemini model is gone — update the app"). | 429 and 404 aren't network failures. | A 404 from a mistyped base url would also read MODEL_MISSING, but the base url is a constant. |
| 3 | When a saved key or voice takes effect | **`onAiChanged()` runs in Save's `onClick`, before the checks launch.** `AiViewModel.onForeground` called during a run queues exactly one more run. | Ruling 6 of the settings plan promised "at once"; leaving Settings must not cancel it. | One extra AI run when he taps twice quickly; `DigestTrigger` keeps it silent. |
| 4 | Garden then AI on foreground | **`GardenViewModel.onForeground()` returns its `Job`. `MainActivity.onStart` launches `{ gardenJob.join(); aiVm.onForeground() }` on `lifecycleScope`.** | The digest then always sees `month.closed` and `carryForward`. | The AI job starts after the reconciler, a few hundred ms later. |
| 5 | What restore counts | **Payments = LOGGED and PENDING_CONFIRM rows, on the phone and in the snapshot. DISCARDED rows don't count.** The empty-server guard uses the same count. The copy gets singulars. | Matches what the ledger shows him. A server with only discarded rows is empty in every sense that matters. | None. |

## Global Constraints

As in the settings plan:
- JDK 17 on every Gradle command.
- `ANDROID_SERIAL=emulator-5554`, with no phone attached.
- No new dependencies, no schema change.
- Never print the token or the key; the smoke token goes in a 600 scratchpad file.
- Go tests only against `expense_garden_test`.
- snake_case instrumented test names.
- Stage named paths only. Plain commits. No push unless asked.

**Done when:**
- 427 JVM tests (423 + 4) and 105 instrumented tests (104 + 1) pass.
- `app/schemas` is untouched.
- The release-build smoke shows the cleartext label.

## Review Focus

1. **No token in a log line**, for a bad-header IllegalArgumentException whose message contains the
   token. Pinned by `a log line never carries a non-IO exception's message`.
2. **Every failure named**: 3xx, non-http scheme, bad character. Pinned by `a redirect, a non-http
   scheme and a bad character get their own names`.
3. **The key check**: a 429 means the key works, and a 404 is named. Pinned by `a rate-limited key
   works and a retired model is named`.
4. **Restore counts discarded rows as nothing**, and the copy has singulars. Pinned by `restore copy
   counts in singulars` (JVM) and `restore_refuses_a_snapshot_holding_only_discarded_rows`
   (instrumented).
5. **Ordering and "at once"** (rulings 3–4) have no test harness. They are checked by reading the
   diff and by the release smoke (Task 5).

---

## Task 1: Log lines and failure names (M-5 sync part, M-6)

**Files:** `sync/SyncFailure.kt`, `sync/SyncClient.kt`. Test: `SyncFailureTest` (3 → 5).

**Interfaces — produces:**
- `SyncFailure.REDIRECTED` and `SyncFailure.INVALID_CHARACTER`, with ruling 1's labels.
- `internal fun syncLogLine(verb: String, code: Int?, error: Exception?): String` in
  `SyncFailure.kt`.

- [ ] **Step 1: Failing tests.** Add to `SyncFailureTest`:

```kotlin
@Test fun `a redirect, a non-http scheme and a bad character get their own names`() {
    assertEquals(SyncFailure.REDIRECTED, SyncFailure.classify(301, null))
    assertEquals(SyncFailure.REDIRECTED, SyncFailure.classify(308, null))
    assertEquals(SyncFailure.BAD_URL, SyncFailure.classify(null, ClassCastException()))
    assertEquals(SyncFailure.INVALID_CHARACTER, SyncFailure.classify(null, IllegalArgumentException()))
}

@Test fun `a log line never carries a non-IO exception's message`() {
    val leaky = IllegalArgumentException("Unexpected char 0x0a in header value: Bearer s3cr3t-token")
    assertEquals("push failed: IllegalArgumentException", syncLogLine("push", null, leaky))
    assertEquals("push failed: HTTP 401", syncLogLine("push", 401, null))
    assertEquals("snapshot failed: ConnectException: refused", syncLogLine("snapshot", null, java.net.ConnectException("refused")))
}
```

  Run: `--tests 'com.expensegarden.app.sync.SyncFailureTest'`. Expected: `BUILD FAILED`, with
  compilation errors for `REDIRECTED`, `INVALID_CHARACTER` and `syncLogLine`.
- [ ] **Step 2: Implement.**
  - `classify`: 300–399 → `REDIRECTED`; `ClassCastException` → `BAD_URL`;
    `IllegalArgumentException` → `INVALID_CHARACTER`.
  - `syncLogLine` per ruling 0.
  - `SyncClient` logs only through `syncLogLine`. Its KDoc says why.
- [ ] **Step 3: Run.** Class: `5 tests, 0 failed`. Full JVM: `425 tests, 0 failed`.
- [ ] **Step 4: Commit.** `fix: keep exception messages that may hold the token out of logcat, and name more failures`.

## Task 2: Key checks and "at once" (M-2, M-3, M-5 key part, M-4, the ordering minor)

**Files:** `ai/KeyCheck.kt`, `ui/SettingsScreen.kt`, `ui/AiViewModel.kt`, `ui/GardenViewModel.kt`,
`MainActivity.kt`. Test: `KeyCheckTest` (1 → 2).

- [ ] **Step 1: Failing test.**

```kotlin
@Test fun `a rate-limited key works and a retired model is named`() {
    assertEquals(KeyCheck.WORKS, KeyCheck.classify(429, null))
    assertEquals(KeyCheck.MODEL_MISSING, KeyCheck.classify(404, null))
}
```

  Expected: `BUILD FAILED`, with a compilation error for `MODEL_MISSING`.
- [ ] **Step 2: Implement.**
  - `KeyCheck.classify` per ruling 2. Settings shows `MODEL_MISSING` as ruling 2's copy.
  - **Save:**
    - `val entered = key.trim()`;
    - `keyChanged = entered != aiPrefs.apiKey`;
    - the check uses `entered`;
    - `if (keyChanged) onAiChanged()` runs in `onClick`, before `scope.launch`.
  - **`AiViewModel.onForeground`:** if a job is active, set `rerun = true` and return. When a job
    ends, it starts one more if `rerun` was set, then clears it.
  - **`GardenViewModel.onForeground(): Job`** returns the active or new job.
  - **`MainActivity.onStart`:**
    `lifecycleScope.launch { gardenVm.onForeground().join(); aiVm.onForeground() }`. The comment
    there says why (ruling 4).
- [ ] **Step 3: Run.** Class: `2 tests, 0 failed`. Full JVM: `426 tests, 0 failed`. `assembleDebug`
  succeeds, with no new warnings from these files.
- [ ] **Step 4: Commit.** `fix: apply a saved key or voice at once, and run the AI job after the garden's`.

## Task 3: Restore counts payments (M-7)

**Files:**
- `sync/RestorePlan.kt`;
- `sync/SyncRepository.kt` (`localCounts`, the guard in `restore`);
- `data/Daos.kt` (`SyncDao.paymentCount()`: `SELECT COUNT(*) FROM txn WHERE status IN ('LOGGED','PENDING_CONFIRM')`);
- `ui/SettingsScreen.kt` (the snapshot's count passed in the same way).

Tests: `RestorePlanTest` (2 → 3); `SyncRestoreTest` (4 → 5).

- [ ] **Step 1: Failing tests.**

```kotlin
@Test fun `restore copy counts in singulars`() {
    assertEquals(
        "The server has 1 payment. This phone has 1, and 1 change isn't backed up yet — it will be lost. " +
            "Restoring replaces this phone's ledger and garden.",
        RestorePlan.of(phoneTxns = 1, unsynced = 1, serverTxns = 1, serverLatestAt = null, zone = zone).message,
    )
    assertEquals(
        "The server has no payments. Restoring would erase this phone's 1 payment, so it won't.",
        RestorePlan.of(1, 0, 0, null, zone).message,
    )
}
```

  The first test's copy changes: "this phone's 42" becomes "this phone's 42 payments". Update
  `an empty server is never restored over a ledger` to match, in this same RED step.

  `restore_refuses_a_snapshot_holding_only_discarded_rows` (instrumented): log one payment, then
  restore a snapshot whose single transaction is DISCARDED. Expect `false`, and the phone's payment
  is still there.

  JVM: `BUILD FAILED`, with 2 `ComparisonFailure`s. Instrumented: 1 failure, `expected:<false> but
  was:<true>`.
- [ ] **Step 2: Implement.**
  - `RestorePlan` copy with singulars.
  - `localCounts()` uses `paymentCount()`.
  - The `restore` guard checks
    `snap.txns.none { it.status != TxnStatus.DISCARDED } && db.syncDao().paymentCount() > 0`.
  - Settings passes `snap.txns.count { it.status != TxnStatus.DISCARDED }` and that subset's latest
    `occurredAt`.
- [ ] **Step 3: Run.** JVM class `3 tests, 0 failed`; full JVM `427 tests, 0 failed`.
  `SyncRestoreTest`: `Finished 5 tests`.
- [ ] **Step 4: Commit.** `fix: count only payments in restore's guard and copy`.

## Task 4: Every suite

- [ ] JVM `--rerun-tasks`: `427 tests, 0 failed`.
- [ ] Instrumented, full: `Finished 105 tests`.
- [ ] `git status --short app/schemas` is empty.

## Task 5: The release build against http (the unverified label)

- [ ] Start a local core-api on a throwaway database `expense_garden_smoke`, with the token in a
  600 scratchpad file, as in the settings smoke. Then run
  `ANDROID_SERIAL=emulator-5554 ./gradlew installRelease`. It runs after the instrumented run,
  which uninstalls the app, so the install is clean.
- [ ] In Settings, enter `http://10.0.2.2:8080` and the token, then Save. Expected status:
  `last try failed: http:// is blocked on this build — use https`.
  - If it reads anything else, stop and report the label plus the logcat line (which carries no
    token after Task 1).
  - Then fix `classify` test-first in its own commit, and ledger it as a ruling.
- [ ] Save with a bogus key and tap a voice. Expected: `Key rejected — check it` (or `Key works` for
  a real one), and the voice kept.
- [ ] Clean up: stop the server, drop the database, delete the token file, `adb uninstall` the
  release app (the emulator only), and stop the emulator.
- [ ] **Docs.**
  - CLAUDE.md: `(427)` and `(105)`.
  - Phone script Step 0: `105`.
  - Commit with the message `docs: counts for the cleanup before 2B`. This plan is committed with
    it, since he asked for plans to be committed.
- [ ] **Report.** Before and after per finding, the smoke result and the counts. No push.

## Out of scope

- R22 (sync identity): next.
- The other deferred minors:
  - `GateScopeTest`'s helper copies;
  - `foldAllTime`'s caps test and the 4/5 pin;
  - the `houseLevel` KDoc;
  - the undo bar re-show;
  - `carryForward`'s JSON guard;
  - "₹0 over" under 50 paise;
  - `emitCrossings` ignoring fixed costs.

  These go to the final polish plan.
