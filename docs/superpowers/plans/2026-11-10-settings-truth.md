# Settings Tell the Truth Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans (his standing preference
> for this repo). Steps use checkbox (`- [ ]`) syntax. If a step's output doesn't match its Expected
> line: stop and report.

**Goal:** Before the backup server goes live (2B), Settings stops hiding failures and stops being able
to wipe the phone:
- Restore shows what it would replace, and refuses an empty server;
- backup status names what went wrong;
- a Gemini key and a voice change take effect at once, and a bad key says so.

**Architecture:** Every decision is a pure, JVM-tested function in `sync/` or `ai/`:
- `RestorePlan` decides whether a restore may run, and with what warning;
- `SyncFailure.classify` names a failed request;
- `syncStatusLine` writes the status;
- `KeyCheck.classify` judges a Gemini key.

The network files stay thin. `SyncClient` returns a typed `SyncResult` instead of null, and
`GeminiClient` gains one cheap `checkKey` call. `SyncRepository.restore` takes the snapshot the
dialog already fetched, and refuses an empty one over a ledger itself, so the UI isn't the only
guard. Settings wires all of this, and persists the voice on tap.

No Room change. One Go test pins the server behaviour the connection check relies on, with no
server change.

**Tech Stack:** Kotlin 2.0.20, Compose, Room 4 (no schema change), HttpURLConnection + org.json,
Go 1.x core-api (tests only).

**Spec:** `docs/superpowers/reviews/2026-10-18-functional-ux-review.md`, items R5, R15 and R16. He
said "write the settings plan" on 2026-11-10. It also folds in the deferred minor "release build
can't reach http core-api (undocumented)", because R15's status is where that failure shows.

Today's code:
- **R5.** `SyncRepository.restore()` wipes events, budgets, transactions and payees, then inserts
  whatever came back, even an empty snapshot (SyncRepository.kt:67-83). `isLedgerEmpty()` is never
  called, and the dialog shows no counts (SettingsScreen.kt:200-220).
- **R15.** `SyncClient.post`/`get` return null for every failure, and non-2xx responses aren't
  logged (SyncClient.kt:76-100). Settings shows only "Backed up Nh ago", and Save tests nothing
  (SettingsScreen.kt:182-191, 229-240).
- **R16.**
  - The AI job runs on a foreground (af6b0b8), but returning from Settings isn't one.
  - The quip refresh is throttled to once a day (`AiViewModel.runAiJob`), so a new voice's quips
    wait a day.
  - `GeminiClient.complete` turns a bad key into the same silence as no network
    (GeminiClient.kt:24-48).
  - The voice is held in screen state until Save (SettingsScreen.kt:59,127,185).

## Rulings (defaults — each is his to overturn)

| # | Question | Default | Why | Cost if wrong |
|---|---|---|---|---|
| 0 | R5: when does Restore refuse? | **Only when the server has no transactions and the phone has some.** Then the dialog says so, offers only OK, and `restore()` itself refuses too. Otherwise the dialog shows both sides' counts and the date of the server's latest payment. When the phone has changes not yet backed up, it says how many will be lost. | An empty snapshot over a real ledger is never what he means (pointing at 2B's fresh Neon database and tapping Restore to "test" it). Every other case is a judgement only he can make, so he gets the numbers instead of a wall. | A server holding an older but non-empty ledger can still replace a newer phone, after a dialog that shows the gap. |
| 1 | R5: fetch the snapshot once or twice? | **Once.** Tapping Restore fetches it ("Checking the server…"), the dialog shows its counts, and confirming applies that same snapshot. | Counts that describe a different download than the one applied would be a lie, and it halves the traffic. | A server that changes between the preview and the tap isn't re-read; the gap is seconds. |
| 2 | R15: how to test the connection on Save | **An empty push** (`POST /v1/sync/push` with empty arrays) when the URL and token are set. It authenticates, writes nothing, and needs no server change. A new Go store test pins that an empty batch applies. | A dedicated ping endpoint would be new API surface for one caller. | If the server ever rejects empty batches, the check reports a server error. The Go test catches that first. |
| 3 | R15: the failure names | **`SyncFailure`:** `UNAUTHORIZED` (401/403: "token rejected"), `NOT_FOUND` (404: "nothing at that url"), `SERVER` (other non-2xx: "server error N"), `UNREACHABLE` (connect, timeout or DNS: "can't reach the server"), `CLEARTEXT_BLOCKED` (`UnknownServiceException`: "http:// is blocked on this build — use https"), `BAD_URL` (malformed: "that isn't a url"). The last failure and its time go in `SyncPrefs`, next to `lastSuccessAt`. | Each needs a different fix from him, and today they all read as the same nothing. `CLEARTEXT_BLOCKED` is the release build's undocumented trap. | DNS failure can't tell a typo'd host from no network; it says "can't reach". |
| 4 | R15: the status line | **"Backup off"** without a URL and token. Otherwise last success as today, plus "· last try failed: <name>, <age>" when the latest failure is newer than the latest success. | "Never backed up" with no URL read like a broken backup, not an absent one. A failure older than the last success is history. | None. |
| 5 | R16: checking the key | **On Save, when the key changed and isn't blank:** one `GET …/models/<MODEL>?key=` (metadata, no generation). 200 → "Key works"; 400/401/403 → "Key rejected"; anything else or no network → "Couldn't reach Gemini — saved anyway". | The cheapest call that proves the key without spending quota, inside the one file that talks to Gemini. | A key with a model-level restriction passes the metadata call and still fails to generate. |
| 6 | R16: when a change takes effect | **At once.** The voice is saved on tap, and resets `lastQuipRefreshAt` so the next run refreshes quips in that voice. A saved key or voice calls `aiVm.onForeground()`. | Settings sits in the same activity, so returning from it never triggers `onStart`. The one-day throttle meant a new voice waited a day. | One extra quip refresh (a few Gemini calls) per voice change. |
| 7 | Secrets | **Unchanged:** masked, `remember` not `rememberSaveable`, never logged. The key check logs nothing, as `complete` doesn't. | The existing rules (SettingsScreen.kt:44-48, 56-57). | None. |

## Global Constraints

- Every Gradle command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- Device runs use the emulator only: no phone attached, `ANDROID_SERIAL=emulator-5554`. Boot it
  with CLAUDE.md's command; it's ready when `getprop sys.boot_completed` prints `1`.
- No new dependencies, no version bumps, no deprecation "fixes". No Room entity changes:
  `git status --short app/schemas` stays empty.
- Never print the sync token or the Gemini key. The smoke's server token is generated into a 600
  file in the scratchpad and read from there.
- Go tests DELETE every row in the database they point at: use `expense_garden_test` only (CLAUDE.md).
- Instrumented test names are snake_case; JVM test names use backticks.
- `testcount` as in the previous plans. Stage named paths only; plain commits; no push unless asked.

**Done when:**
- 423 JVM tests (417 + 6) and 102 instrumented tests (100 + 2) pass.
- The new Go store test passes.
- The Task 5 smoke shows each status and the refused restore.

## Review Focus

1. **An empty server over a full phone** refuses, in the plan and in `restore()` itself. Pinned
   by `an empty server is never restored over a ledger` (JVM) and
   `restore_refuses_an_empty_snapshot_over_a_ledger` (instrumented).
2. **Unsynced changes** are counted in the restore dialog before they'd be lost. Pinned by
   `the restore warning counts what isn't backed up yet`.
3. **Each failure gets its own name**: 401, 403, 404, 500, connect refused, timeout, unknown host,
   cleartext blocked, malformed URL. Pinned by `each failure gets a name`.
4. **A failure older than the last success** doesn't show, and an unconfigured backup reads
   "Backup off". Pinned by `the status names the last failure only while it is current` and
   `an unconfigured backup is off, not broken`.
5. **A bad Gemini key** reads "Key rejected", not silence. Pinned by `a key check tells rejected
   from unreachable`.

## File structure

All app paths are under `app/src/main/java/com/expensegarden/app/`.

| File | Change |
|---|---|
| `sync/RestorePlan.kt` (new) | pure: `RestorePlan.of(…)` → `Refuse(message)` or `Confirm(message)` |
| `sync/SyncFailure.kt` (new) | pure: `enum SyncFailure` with `label` and `classify(code: Int?, error: Exception?)`; `syncStatusLine(…)` |
| `sync/SyncClient.kt` | `SyncResult` from `push`/`snapshot`; the empty push serves as the check; every failure logged |
| `sync/SyncPrefs.kt` | `lastFailure: SyncFailure?`, `lastFailureAt: Long` |
| `sync/SyncRepository.kt` | `pushOnce` records failures; `fetchSnapshot()`; `restore(snapshot)` refuses empty-over-ledger; `checkConnection()` |
| `ai/GeminiClient.kt`, `ai/KeyCheck.kt` (new) | `checkKey(key): KeyCheck`; pure `KeyCheck.classify(code, error)` |
| `data/AiPrefs.kt` | none; Settings resets `lastQuipRefreshAt` through its existing setter |
| `ui/SettingsScreen.kt` | restore preview dialog; status line; key check result; voice saved on tap; `onAiChanged` callback |
| `MainActivity.kt` | passes `onAiChanged = aiVm::onForeground` |
| `backend/core-api/internal/store/store_test.go` | `TestAnEmptyBatchApplies` |

---

## Task 1: Restore refuses an empty server and shows what it replaces (R5)

**Files:** create `sync/RestorePlan.kt`; modify `sync/SyncRepository.kt`. Tests:
`app/src/test/java/com/expensegarden/app/sync/RestorePlanTest.kt` (new, 2) and
`app/src/androidTest/java/com/expensegarden/app/sync/SyncRestoreTest.kt` (new, 2).

**Interfaces — produces:**
- `sealed interface RestorePlan { val message: String; data class Refuse(...); data class Confirm(...) }`.
- `fun RestorePlan.Companion.of(phoneTxns: Int, unsynced: Int, serverTxns: Int, serverLatestAt: Long?, zone: ZoneId): RestorePlan`.
  If a companion is awkward, a top-level `restorePlan(…)` with the same parameters is fine.
- `SyncRepository.fetchSnapshot(): SyncResult<SyncSnapshot>` (Task 2 defines `SyncResult`; Task 1
  can return `SyncSnapshot?` and Task 2 switches it).
- `SyncRepository.restore(snapshot: SyncSnapshot): Boolean`, false without touching Room when the
  snapshot has no transactions and the ledger has some.
- `SyncRepository.localCounts(): Pair<Int, Int>` (transactions, unsynced rows).

- [ ] **Step 1: Failing tests.**

```kotlin
class RestorePlanTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun `an empty server is never restored over a ledger`() {
        val plan = RestorePlan.of(phoneTxns = 42, unsynced = 0, serverTxns = 0, serverLatestAt = null, zone = zone)
        assertTrue(plan is RestorePlan.Refuse)
        assertEquals("The server has no payments. Restoring would erase this phone's 42, so it won't.", plan.message)
    }

    @Test fun `the restore warning counts what isn't backed up yet`() {
        val at = LocalDate.of(2026, 11, 8).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val plan = RestorePlan.of(phoneTxns = 42, unsynced = 3, serverTxns = 40, serverLatestAt = at, zone = zone)
        assertTrue(plan is RestorePlan.Confirm)
        assertEquals(
            "The server has 40 payments, the latest on 8 Nov 2026. This phone has 42, and 3 changes " +
                "aren't backed up yet — they will be lost. Restoring replaces this phone's ledger and garden.",
            plan.message,
        )
        // An empty phone is the normal restore: nothing to lose, so no warning clause.
        assertEquals(
            "The server has 40 payments, the latest on 8 Nov 2026. This phone has none. Restoring replaces " +
                "this phone's ledger and garden.",
            RestorePlan.of(0, 0, 40, at, zone).message,
        )
    }
}
```

  `SyncRestoreTest` (instrumented, in-memory Room, `SyncRepository(db, SyncClient(prefs), prefs)`
  with `prefs = SyncPrefs(context)`):
  - `restore_refuses_an_empty_snapshot_over_a_ledger`: log one manual payment through
    `LedgerRepository`, call `restore(` an all-empty `SyncSnapshot` `)`. Expect `false`, and
    `db.syncDao().txnCount()` is still 1.
  - `restore_replaces_the_ledger_with_a_snapshot`: log one payment, restore a snapshot holding one
    different payment (with its payee and the seeded categories). Expect `true`, the count is 1,
    and `byUuid` finds the snapshot's uuid and not the logged one.

  Run the JVM class: `BUILD FAILED`, with a compilation error for `RestorePlan`. Run the
  instrumented class: `BUILD FAILED`, with a compilation error for `restore(` taking a snapshot.
- [ ] **Step 2: Implement.**
  - Write `RestorePlan.of`, with the exact copy above. The date format is `d MMM yyyy`.
  - In `SyncRepository`:
    - split `restore()` into `fetchSnapshot()` and `restore(snapshot)`;
    - add the empty-over-ledger guard at the top of `restore(snapshot)`, before the transaction;
    - add `localCounts()` (`txnCount()`, `pendingCount()`);
    - replace the stale "Guarded by the caller, not here" KDoc.
  - `SettingsScreen` keeps compiling by calling `fetchSnapshot()` then `restore(it)`; the dialog
    itself changes in Task 4.
- [ ] **Step 3: Run.**
  - JVM class: `2 tests, 0 failed`.
  - Instrumented `SyncRestoreTest`: `Finished 2 tests`.
  - Full JVM: `419 tests, 0 failed`.
- [ ] **Step 4: Commit.** `fix: refuse to restore an empty server over the phone's ledger`.

---

## Task 2: Every sync failure has a name (R15, data side)

**Files:** create `sync/SyncFailure.kt`; modify `sync/SyncClient.kt`, `sync/SyncPrefs.kt` and
`sync/SyncRepository.kt`. Tests: `app/src/test/java/com/expensegarden/app/sync/SyncFailureTest.kt`
(new, 3); Go: `backend/core-api/internal/store/store_test.go` (+1).

**Interfaces — produces:**
- `enum class SyncFailure(val label: String) { UNAUTHORIZED, NOT_FOUND, SERVER, UNREACHABLE, CLEARTEXT_BLOCKED, BAD_URL }`.
  The labels are ruling 3's.
- `companion fun classify(code: Int?, error: Exception?): SyncFailure`.
- `fun syncStatusLine(configured: Boolean, lastSuccessAt: Long, lastFailure: SyncFailure?, lastFailureAt: Long, pending: Int?, nowMillis: Long): String`.
  It replaces SettingsScreen's private `syncStatus`.
- `sealed interface SyncResult<out T> { data class Ok<T>(val value: T); data class Failed(val failure: SyncFailure, val code: Int?) }`.
- `SyncClient.push(batch): SyncResult<Unit>` and `SyncClient.snapshot(): SyncResult<SyncSnapshot>`.
  A body that won't parse is `Failed(SERVER, code)`.
- `SyncRepository.checkConnection(): SyncResult<Unit>`, an empty push. It records success or
  failure in prefs, like `pushOnce`, without moving cursors.

- [ ] **Step 1: Failing tests.**

```kotlin
class SyncFailureTest {
    @Test fun `each failure gets a name`() {
        assertEquals(SyncFailure.UNAUTHORIZED, SyncFailure.classify(401, null))
        assertEquals(SyncFailure.UNAUTHORIZED, SyncFailure.classify(403, null))
        assertEquals(SyncFailure.NOT_FOUND, SyncFailure.classify(404, null))
        assertEquals(SyncFailure.SERVER, SyncFailure.classify(500, null))
        assertEquals(SyncFailure.UNREACHABLE, SyncFailure.classify(null, java.net.ConnectException()))
        assertEquals(SyncFailure.UNREACHABLE, SyncFailure.classify(null, java.net.SocketTimeoutException()))
        assertEquals(SyncFailure.UNREACHABLE, SyncFailure.classify(null, java.net.UnknownHostException()))
        assertEquals(SyncFailure.CLEARTEXT_BLOCKED, SyncFailure.classify(null, java.net.UnknownServiceException()))
        assertEquals(SyncFailure.BAD_URL, SyncFailure.classify(null, java.net.MalformedURLException()))
    }

    @Test fun `the status names the last failure only while it is current`() {
        val now = 10 * 3_600_000L
        assertEquals("Backed up 2h ago · last try failed: token rejected, 1h ago",
            syncStatusLine(true, now - 2 * 3_600_000, SyncFailure.UNAUTHORIZED, now - 3_600_000, 0, now))
        assertEquals("Backed up 1h ago",
            syncStatusLine(true, now - 3_600_000, SyncFailure.UNAUTHORIZED, now - 2 * 3_600_000, 0, now))
        assertEquals("Never backed up · 3 waiting · last try failed: can't reach the server, just now",
            syncStatusLine(true, 0L, SyncFailure.UNREACHABLE, now - 1_000, 3, now))
    }

    @Test fun `an unconfigured backup is off, not broken`() {
        assertEquals("Backup off", syncStatusLine(false, 0L, null, 0L, 5, 10_000L))
    }
}
```

  Go (store_test.go), using the file's existing test-database helper:

```go
// The phone's "test connection" is an empty push (settings plan, ruling 2): it must succeed and write nothing.
func TestAnEmptyBatchApplies(t *testing.T) { /* ApplyBatch(ctx, store.Batch{}) returns nil; Snapshot is still empty */ }
```

  - JVM: `BUILD FAILED`, with compilation errors for `SyncFailure` and `syncStatusLine`.
  - Go: `TEST_DATABASE_URL=… go test ./internal/store -run TestAnEmptyBatchApplies` passes. It is
    a guard that pins today's behaviour (ruling 2).
- [ ] **Step 2: Implement.**
  - Write `SyncFailure` and `syncStatusLine`. Keep today's age wording (`just now`, `Nm ago`,
    `Nh ago`, the `⚠ not backed up in Nd` stale marker) for the success part. Use the same age
    wording for the failure.
  - `SyncClient` returns `SyncResult`:
    - every non-2xx and every exception goes through `classify`;
    - `logFailure` also logs non-2xx, with the code and never the token.
  - Add `SyncPrefs.lastFailure` (stored by name; an unknown name reads as null) and
    `lastFailureAt`.
  - `pushOnce` keeps its Boolean, and records a failure when the push fails.
    `SyncScheduler` is unchanged.
  - `fetchSnapshot` returns `SyncResult<SyncSnapshot>`, and Settings' Task 1 call adapts.
- [ ] **Step 3: Run.** JVM class: `3 tests, 0 failed`. Full JVM: `422 tests, 0 failed`. Instrumented
  `SyncRestoreTest`: `Finished 2 tests`.
- [ ] **Step 4: Commit.** Stage `backend/core-api/internal/store/store_test.go` with the Android files:
  `fix: name every backup failure and remember the last one`.

---

## Task 3: A Gemini key is checked, and a bad one says so (R16, data side)

**Files:** create `ai/KeyCheck.kt`; modify `ai/GeminiClient.kt`. Test:
`app/src/test/java/com/expensegarden/app/ai/KeyCheckTest.kt` (new, 1).

**Interfaces — produces:**
- `enum class KeyCheck { WORKS, REJECTED, UNREACHABLE }`, with
  `companion fun classify(code: Int?, error: Exception?): KeyCheck`.
- `suspend fun GeminiClient.checkKey(key: String): KeyCheck`. It does
  `GET $BASE/models/$MODEL?key=…` on `Dispatchers.IO`, catches all exceptions, logs nothing, and
  returns `classify`.

- [ ] **Step 1: Failing test.**

```kotlin
@Test fun `a key check tells rejected from unreachable`() {
    assertEquals(KeyCheck.WORKS, KeyCheck.classify(200, null))
    assertEquals(KeyCheck.REJECTED, KeyCheck.classify(400, null))   // Gemini answers a bad key with 400 API_KEY_INVALID
    assertEquals(KeyCheck.REJECTED, KeyCheck.classify(401, null))
    assertEquals(KeyCheck.REJECTED, KeyCheck.classify(403, null))
    assertEquals(KeyCheck.UNREACHABLE, KeyCheck.classify(500, null))
    assertEquals(KeyCheck.UNREACHABLE, KeyCheck.classify(null, java.net.UnknownHostException()))
}
```

  Expected: `BUILD FAILED`, with a compilation error for `KeyCheck`.
- [ ] **Step 2: Implement.**
  - Write `KeyCheck.classify`.
  - In `GeminiClient`, split `ENDPOINT` into a `BASE` constant plus the model, and add
    `checkKey`. The class KDoc's "only file that touches the network" stays true.
- [ ] **Step 3: Run.** JVM class: `1 tests, 0 failed`. Full JVM: `423 tests, 0 failed`.
- [ ] **Step 4: Commit.** `fix: let Settings check a Gemini key`.

---

## Task 4: Settings shows all of it (R5, R15, R16, UI)

**Files:** modify `ui/SettingsScreen.kt` and `MainActivity.kt:139-145`.

No test reaches a Composable here. The pure pieces above carry the decisions, and Task 5's smoke
checks the wiring.

- [ ] **Step 1: Implement.**
  - `SettingsScreen` gains a parameter, `onAiChanged: () -> Unit`, which MainActivity passes as
    `aiVm::onForeground`.
  - **Voice:**
    - tapping an option writes `aiPrefs.tone` at once;
    - if the voice changed, set `aiPrefs.lastQuipRefreshAt = 0` and call `onAiChanged()`;
    - `tone` leaves the Save button's writes.
  - **Save:**
    - Write the key, URL and token as today.
    - If the key changed and isn't blank, show "Checking the key…", then the `checkKey` result as
      ruling 5's copy, under the key field.
    - If the backup is configured, run `checkConnection()` and refresh the status line.
    - Call `onAiChanged()` when the key changed.
    - Delete the "The garden will speak on your next visit home" line, which is no longer true.
  - **Status line:** `syncStatusLine(syncPrefs.isConfigured, …)`. Delete the private `syncStatus`.
  - **Restore:**
    - The button fetches the snapshot ("Checking the server…").
    - On `Failed`, show "Couldn't read the server: <label>".
    - On `Ok`, build `RestorePlan.of` from `localCounts()` and the snapshot's transaction count and
      latest `occurredAt`.
    - `Refuse` shows its message, with OK only.
    - `Confirm` shows its message, with Restore and Cancel. Restore calls `restore(snapshot)` with
      the snapshot it fetched (ruling 1).
- [ ] **Step 2: Run.**
  - Full JVM: `423 tests, 0 failed`.
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL`, with no new warnings from these files.
  - `wc -l` SettingsScreen.kt: under 800.
- [ ] **Step 3: Commit.** `fix: show restore counts, backup failures and key checks in Settings`.

---

## Task 5: Every suite, the smoke against a local server, then the counts

- [ ] **Step 1: Every suite.**
  - JVM with `--rerun-tasks`: `423 tests, 0 failed`.
  - Instrumented, full: `Finished 102 tests`.
  - `git status --short app/schemas` is empty.
  - Go: `go vet ./... && go test ./...` against `expense_garden_test`, passing.
- [ ] **Step 2: Smoke.** On the emulator, with `installDebug` after the instrumented run, against
  core-api run locally on a fresh database `expense_garden_smoke` (createdb, then dropdb at the
  end).
  - Generate the token into a 600 scratchpad file and pass it by env var; never print it. The
    emulator reaches the host at `http://10.0.2.2:8080` (the debug-only cleartext config).
  - Read every expectation from `uiautomator dump`:
    1. **Wrong token, Save.** Expected: the status contains `last try failed: token rejected`.
    2. **Right token, Save.** Expected: `Backed up just now`.
    3. **Restore against an empty server.** Log one manual payment on the phone. Stop the server,
       then drop and recreate `expense_garden_smoke` so the server holds nothing, and start the
       server again. Tap Restore. Expected: the refusal copy naming this phone's count, with OK
       only. The phone's payment is still there.
    4. **Server stopped, Save.** Expected: `can't reach the server`.
    5. **A bogus Gemini key, Save.** Expected: `Key rejected` (one metadata call to Google; no
       quota).
    6. **Tap a voice, leave and reopen Settings.** Expected: that voice is selected without
       pressing Save.

  Stop the server, drop the smoke database, and stop the emulator.
- [ ] **Step 3: Docs.**
  - CLAUDE.md: `# JVM unit tests (423)`, `# instrumented (102)`.
  - Phone script Step 0: `Run the 102 instrumented tests` and `tests 102 failures 0 errors 0`.
  - Commit with the message `docs: counts for the settings fixes`; this plan stays uncommitted.
- [ ] **Step 4: Report.** Before and after per item, the smoke results, the suite counts and the
  commits. No push.

## Out of scope

- R22 (sync identity): the next plan.
- Restoring a server older than the phone: the dialog shows the gap (ruling 0), and merging is R22's design.
- A Settings ViewModel. The screen stays a Composable over prefs and the repository, as today.
