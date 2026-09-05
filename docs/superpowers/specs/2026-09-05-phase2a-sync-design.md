# Phase 2A — Sync Core: Design

**Status:** approved through §2 (scope, data model) on 2026-09-05; §3 onward written while
the user was away and awaits review.

**Goal:** Wipe the app, restore from the server, and get everything back — every rupee and
the whole garden. Nothing else.

**Parent spec:** `docs/superpowers/specs/2026-07-03-expense-garden-design.md` §7 (backend
architecture), §7.3 (sync model), §7.5 (free-tier risk).

---

## 1. Scope

Parent §13's Phase 2 acceptance line bundles seven things: Postgres and migrations, a Go
core-api, an Android sync module, a hosted VM with TLS, nightly backups *verified
restorable*, GitHub Actions deploys, and Sentry. That is not one plan. It splits three ways:

| Slice | Delivers | Needs a cloud account? |
|---|---|---|
| **2A — sync core** (this spec) | Schema, core-api push/snapshot, Android `sync/`, restore round-trip | **No** |
| 2B — deploy | Hosting, TLS, DNS, secrets, CI | Yes |
| 2C — durability | `pg_dump` → object storage, restore drill, Sentry | Yes |

**2A runs entirely on the developer's Mac**: Postgres in docker-compose, the emulator
reaching the host at `10.0.2.2`. This is deliberate, not a shortcut. Free-tier terms drift —
Phase 1D shipped against a Gemini model that Google retired before the phase was verified —
so the hosting decision is made in 2B against facts that are current on the day it is made,
not against a note written months earlier. Parent §7.5 already anticipates this and names
Neon + Koyeb as the fallback if Oracle signup fails; 2B will re-verify whatever it picks.

**Done when:** populate the app, sync, `pm clear` it, restore, and the garden renders
identically.

### 1.1 Explicitly out of scope for 2A

- Hosting, TLS, DNS, CI, backups, Sentry — 2B and 2C.
- Server-side stats endpoints. Phase 4's dashboard can ask for them when it exists; building
  them now is speculative API surface.
- `ai-svc` (Phase 3), whose existence is itself an open question now that 1D does persona
  and digests on-device at ₹0.
- Multi-device conflict handling. The chosen purpose is backup and restore with a single
  writer, which is what makes last-write-wins genuinely correct rather than a compromise.
- Syncing `quip` and `digest`. They are LLM output, regenerable from a key, and excluding
  them keeps AI-layer schema churn out of the sync protocol entirely. Note the churn is real:
  `quip` gained a `tone` column and `digest` was created outright, both in 1D alone.
- Realtime, websockets, push notifications.

---

## 2. Data model

### 2.1 What syncs

| Table | Sync key | Mutable? | Deletes? |
|---|---|---|---|
| `category` | `id` | Seed-only today | No |
| `payee` | `id` | Yes (`defaultCategoryId`) | No |
| `txn` | `uuid` (client-generated) | Yes (`status`, `regret`) | No |
| `budget` | **`(categoryId, month)`** — natural key | Yes | **Yes** |
| `game_event` | `id` | **Never** (append-only) | Never |

`budget` keys on its natural key rather than its autoincrement `id` for a specific reason:
`setBudget` is implemented as delete-then-insert inside one transaction, so an *edit*
produces a new `id`. Keyed on `id`, that edit would emit a tombstone for the old row racing
an insert of the new one, and the server's ordering would decide whether the budget survived.
Keyed on `(categoryId, month)`, an edit is a plain upsert and a tombstone is written only
when a budget is genuinely cleared.

**The key has a NULL in it, and that matters.** `categoryId` is null for the overall budget,
and *neither* SQLite nor Postgres treats NULLs as equal in a unique index — verified, not
assumed: inserting two rows with `categoryId = NULL, month = '2026-09'` against the app's
existing `Index(value = ["categoryId","month"], unique = true)` succeeds and yields two rows.
The app is safe today only because `setBudget` deletes before it inserts. Two consequences:

- Postgres declares `UNIQUE NULLS NOT DISTINCT (category_id, month)` (Postgres 15+), so the
  overall budget is genuinely unique server-side and `ON CONFLICT` has a constraint to target.
- The tombstone `rowKey` encodes the key as `"<categoryId or *>|<month>"` — `"3|2026-09"` for
  a category budget, `"*|2026-09"` for the overall one. An explicit sentinel, because an
  empty segment would be indistinguishable from a malformed key.

`game_event` is the exception to everything. It is append-only — nothing in the codebase
updates or deletes it — so it needs no `updatedAt` and no tombstones. It syncs by an `id`
watermark, which is precisely the pattern built and hardened in 1D for the digest cursor.

### 2.2 Room v4

Four columns and one table:

- `updatedAt: Long` (NOT NULL) on `category`, `payee`, `txn`, `budget`.
- `sync_tombstone(tableName TEXT, rowKey TEXT, deletedAt INTEGER)`, PK `(tableName, rowKey)`.

The tombstone is a **separate table**, not a soft-delete flag on `budget`. A flag would mean
adding `WHERE deleted = 0` to every existing budget query — `allForMonth`,
`observeAllForMonth`, `overallForMonth` — and one missed filter silently corrupts the
dashboard and the gate. Additive beats invasive.

The migration stamps existing rows with the migration timestamp and leaves the push cursor at
0, so the first sync uploads the entire history as one batch.

`game_event`, `quip` and `digest` are untouched by the migration.

### 2.3 The monotonic stamp

Every `updatedAt` comes from one app-wide logical clock, not from `System.currentTimeMillis()`
directly:

```
next = max(System.currentTimeMillis(), lastStamp + 1)
```

`lastStamp` persists in `SyncPrefs`. This does two jobs:

1. **Removes the clock hazard.** A phone whose clock jumps backwards — timezone change, NTP
   correction, manual edit — cannot produce a stamp that makes a newer row look older and
   lose a last-write-wins comparison.
2. **Removes ties.** Because every stamp is strictly greater than the last, the dirty-row
   predicate `updatedAt > lastPushedAt` is exact. Without it, two rows written in the same
   millisecond could straddle a batch boundary and the second would never be pushed.

Point 2 is the same defect class 1D hit twice: `runReconciler` stamps a whole batch of events
with one `System.currentTimeMillis()`, so timestamps collide and cannot order the batch. A
timestamp cursor over colliding timestamps loses rows. The logical clock is what makes
§7.3's chosen `updated_at` approach sound.

### 2.4 Where `updatedAt` gets stamped

Inserts are compiler-enforced: a non-null constructor field cannot be omitted. The risk is
confined to statements that update or delete without going through an entity — **five of
them**, all enumerable:

| Statement | Location |
|---|---|
| `payee.setDefaultCategory` | `PayeeDao` |
| `txn.setStatus` | `TransactionDao` |
| `txn.setRegret` | `TransactionDao` |
| `budget.deleteForCategory` | `BudgetDao` |
| `budget.deleteOverallForMonth` | `BudgetDao` |

The three UPDATEs gain `updatedAt = :updatedAt`. The two DELETEs additionally write a
tombstone, which requires them to move behind a repository — see §2.5.

### 2.5 The budget repository refactor

`DashboardViewModel` currently calls `budgetDao().insert / deleteForCategory /
deleteOverallForMonth` directly, inside its own `withTransaction`. It is the only ViewModel
in the codebase that reaches into a DAO to write, and it owns two of the five risky
statements above.

2A moves that logic into `BudgetRepository.setBudget(categoryId, amountPaise)`, which also
draws the distinction the sync protocol needs:

- `amountPaise > 0` → delete + insert as today, stamped, **no tombstone** (it is an edit)
- `amountPaise` null or 0 → delete, **write a tombstone** (it is a clear)

This is a targeted improvement to code the phase is already modifying, not opportunistic
refactoring.

### 2.6 Postgres

Mirrors Room with real foreign keys, per parent §11. Enums are stored as `TEXT` on the
client (via `EnumConverters`), so they map to `text` with `CHECK` constraints rather than
Postgres enum types — a CHECK is trivially altered when a variant is added, an enum type is
not.

No `server_seq` column. An earlier draft added one to give a future incremental pull a
clock-independent cursor, but 2A has no incremental pull, and a column that nothing reads is
a column whose maintenance rule — bump it on every update, not just on insert — is untested
and will quietly be wrong by the time something depends on it. `ALTER TABLE ADD COLUMN` is
cheap; a silently stale cursor is not.

Schema is numbered SQL files applied by a small embedded runner, not a migration library.

---

## 3. The protocol

Three endpoints. All JSON. All but health require `Authorization: Bearer <token>`.

```
GET  /v1/health              → 200 {"ok":true}          (no auth)
POST /v1/sync/push           → 200 {"eventId": <n>}
GET  /v1/sync/snapshot       → 200 {full database}
```

Push is the workhorse; snapshot exists for restore. There is no incremental pull in 2A —
with a single writer, the phone is always ahead of or equal to the server, so the only pull
that matters is "give me everything back."

### 3.1 Push

Request carries only what has changed:

```json
{
  "categories": [{"id":1,"name":"Food & Drinks","parentId":null,"isNecessity":false,"updatedAt":1788604648434}],
  "payees":     [{"id":7,"name":"Chaiwala","vpa":"chai@ybl","defaultCategoryId":103,"updatedAt":...}],
  "txns":       [{"uuid":"...","amountPaise":2000,"payeeId":7,"categoryId":103,"source":"QR_GATE",
                  "status":"LOGGED","regret":"UNRATED","breachedAtLogging":false,"note":null,
                  "occurredAt":...,"createdAt":...,"updatedAt":...}],
  "budgets":    [{"categoryId":null,"month":"2026-09","amountPaise":1000000,"updatedAt":...}],
  "tombstones": [{"tableName":"budget","rowKey":"3|2026-09","deletedAt":...}],
  "events":     [{"id":42,"type":"gate.dodged","payloadJson":"{}","transactionUuid":null,"createdAt":...}]
}
```

Server applies the whole batch in **one transaction**:

- Rows upsert on their sync key with last-write-wins:
  `ON CONFLICT (key) DO UPDATE ... WHERE excluded.updated_at >= t.updated_at`
- Tombstones delete by natural key, but only if `deletedAt` is newer than the row's
  `updated_at` — otherwise a stale tombstone would erase a budget the user has since re-set.
- Events insert `ON CONFLICT (id) DO NOTHING`. Append-only means re-sending is free, which
  makes the whole push idempotent and lets the client retry without bookkeeping.

Ordering within the transaction respects foreign keys: categories → payees → txns → budgets
→ events → tombstones.

### 3.2 Cursors

The client keeps three values in `SyncPrefs`:

| Cursor | Advanced when | Used for |
|---|---|---|
| `lastPushedAt` | push succeeds | `WHERE updatedAt > ?` on the four mutable tables |
| `lastPushedEventId` | push succeeds | `WHERE id > ?` on `game_event` |
| `lastStamp` | every write | the monotonic clock (§2.3) |

Cursors advance **only on a 2xx**, and to values captured *before* the request was built —
the same head-first discipline 1D's `window()` uses, so a row written during the round trip
stays dirty rather than being skipped.

### 3.3 Restore

`GET /v1/sync/snapshot` returns every table in full. The client then, in one Room
transaction:

1. Deletes all rows from `game_event`, `budget`, `txn`, `payee` (reverse FK order).
   `category` is left alone — the seed already created it and the ids are stable.
2. Inserts in FK order with **explicit ids preserved**: `category` (upsert), `payee`, `txn`,
   `budget`, `game_event`.
3. Sets `lastPushedAt` and `lastPushedEventId` to the maxima received, so a restored phone
   does not immediately re-upload everything it was just given.

Preserving `payee.id` and `game_event.id` is mandatory, not cosmetic: `txn.payeeId` and
`game_event.transactionUuid` are real foreign keys, and the garden's month markers depend on
event ordering.

`quip` and `digest` are not restored and stay empty.

> **A designed consequence worth naming.** After a restore, `digest` is empty, so
> `DigestRepository.latestSnapshot()` returns null, which puts `window()` into its first-run
> floor and clamps the window to today's start. The restored phone therefore speaks about
> today rather than narrating three years of restored history in one card. That floor was
> added in 1D Task 11 for a different reason; it happens to be exactly right here, and 2A
> depends on it deliberately rather than by luck.

Restore is **explicit and guarded**: a button in Settings, refusing to run against a
non-empty ledger unless the user confirms replacement.

---

## 4. Client module

A new `sync/` package, shaped like `ai/` because the constraints are identical — no new
dependencies, so `HttpURLConnection` and `org.json`.

| File | Responsibility |
|---|---|
| `SyncPrefs` | server URL, bearer token, the three cursors. New `sync_secrets.xml`. |
| `SyncClient` | the only file touching the network. `withContext(Dispatchers.IO)` inside. |
| `SyncPayload` | pure Kotlin data classes + the dirty-row selection and cursor math |
| `SyncRepository` | collects dirty rows, calls the client, advances cursors, owns restore |
| `SyncScheduler` | debounces change signals into at most one in-flight push |

`SyncPrefs` uses a **new** prefs file, `sync_secrets.xml`, added to both
`res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`. It is not merged into
`ai_secrets.xml`: 1D documented that filename as load-bearing, and widening its meaning
invites exactly the rename that silently re-exposes a key.

`SyncPayload` stays JSON-free. `org.json` is an Android stub that throws "not mocked" in JVM
tests, so keeping the selection predicates and cursor arithmetic in pure Kotlin is what makes
them unit-testable — the same boundary discipline `DigestTrigger` and `DigestRepository` use.

**Triggering without coupling.** Repositories must not know sync exists. `LedgerRepository`
and `BudgetRepository` take an injected `onChanged: () -> Unit`, defaulting to a no-op so
every existing test constructs them unchanged. `AppContainer` wires that to
`SyncScheduler.signal()`, which debounces (~2s) and coalesces into one push. `MainActivity`
signals once on open.

---

## 5. Failure modes

| Failure | Behaviour |
|---|---|
| No URL or token | Sync disabled. App fully functional. |
| Network down / DNS / timeout | Push fails, cursors do not advance, retried on next signal |
| 401 / 403 | Token wrong — **surfaced in Settings**, see below |
| 5xx | Same as network down |
| Malformed response | Treated as failure; cursors do not advance |
| Restore against a non-empty ledger | Refused unless explicitly confirmed |

**Sync status is visible, and this is a deliberate departure from 1D.** The AI layer routes
every failure to silence because nothing waits on it and a quiet persona costs nothing. A
silently dead *backup* is the opposite: it costs you everything precisely when you need it,
and it looks identical to a working one until the day it matters. Task 15 made this concrete
— a retired model and a short timeout both rendered as "no digest today," and only a manual
device gate could tell working from wired-to-nothing.

So Settings shows a real status line — "Last backed up 2 minutes ago", the pending-change
count, and a visible warning once the last success is over 24 hours old. It never blocks or
interrupts; it just refuses to let a dead backup look healthy.

---

## 6. Testing

**JVM (pure, no Room, no `org.json`):** the monotonic clock never goes backwards and never
repeats across a simulated clock jump; cursor arithmetic advances to the highest stamp in a
batch, counts a tombstone's `deletedAt`, and never regresses.

Last-write-wins and tombstone-versus-row precedence are deliberately **not** here. That rule
lives in `ON CONFLICT ... WHERE` clauses, so a Kotlin or Go reimplementation of it would be
duplicate logic pretending to be a test — it would prove only that the copy agrees with
itself. It is tested where it actually executes, below.

**Instrumented (Room on device):** migration 3→4 preserves every row and stamps `updatedAt`;
each of the five risky statements in §2.4 actually stamps; clearing a budget writes a
tombstone while editing one does not; restore inserts in FK order with ids preserved and no
constraint violations; and — verified in isolation before being relied on — an explicit-id
insert advances SQLite's AUTOINCREMENT sequence, so a restored phone's next new payee or
event cannot collide with a restored id.

**Go:** table-driven unit tests for merge and LWW, including the stale-tombstone case;
integration tests against a real Postgres, since the whole point is `ON CONFLICT` semantics
that a mock cannot exercise.

**End-to-end, the acceptance gate:** populate the app, sync, `adb shell pm clear`, restore,
and assert the garden fold is byte-identical — same plants, same house level, same month
markers.

---

## 7. Bring-up findings (first device run, 2026-09-05)

Two defects that only a real device against a real server could surface. Both rendered
identically to "nothing to sync", which is precisely the hazard §5 exists to name.

| # | Finding | Fix |
|---|---|---|
| 1 | **Seeded categories could never sync.** `SeedCallback` writes them with raw SQL — `INSERT INTO category (id, name, parentId, isNecessity)` — which bypasses the entity constructor, so §2.4's compiler enforcement does not reach it. They took the column default of 0 and the `updatedAt > cursor` predicate selected none of them, ever. Not cosmetic: `txn.category_id` is a real FK on the server, so the first transaction push was rejected with a foreign-key violation, which the client turns into silence. `MIGRATION_3_4` stamps existing rows, so **only fresh installs were affected** — exactly the case a restore-to-a-new-phone exercises, and one no upgrade test would ever cover. | Seed stamps `updatedAt`; regression test `seeded_categories_are_dirty_against_a_fresh_cursor` |
| 2 | **`targetSdk 35` blocks cleartext HTTP.** `http://10.0.2.2:8080` threw `IOException: Cleartext HTTP traffic to 10.0.2.2 not permitted` before a packet left the device, and `SyncClient`'s catch swallowed it — no request at the server, nothing on screen, nothing in logcat. | `network_security_config.xml`: strict in `main`, and a `debug`-only override permitting cleartext to `10.0.2.2`, `localhost` and `127.0.0.1` only. Release builds stay HTTPS-only. |

**Two diagnosability changes came out of this, and both are deliberate departures from 1D.**
`core-api` now logs one line per request with its status, because "the client never called" and
"the call was rejected" are otherwise indistinguishable. `SyncClient` now logs one line per
failure with the exception class and message — the bearer token travels in a header, so it
cannot appear in either. 1D's total silence is right for a persona nobody is waiting on; it is
wrong for a backup, where the failure mode is looking healthy while protecting nothing.

### Acceptance gate result (2026-09-05)

Passed on emulator-5554 against core-api on the host. Populated through the real UI — a ₹250
manual transaction to a new payee, and a ₹10,000 overall budget — each of which synced on its
own write with no prompting. Then `adb shell pm clear`, which left `shared_prefs` and
`databases` genuinely absent, followed by a restore through the Settings button.

Every count matched: 1 txn, 1 payee, 1 budget, 1 event, 21 categories, 25000 paise. The event
came back as `1:transaction.logged` and the payee as id 1 — **ids preserved**, which is what
keeps `txn.payeeId` and `game_event.transactionUuid` pointing at real rows. The garden
rendered identically: same plant, same SEP marker, same house, and the strip read
"₹250.00 · ₹10000.00 · on pace · 🌱4d".

Two designed behaviours confirmed in passing: `digest` came back empty, putting
`DigestRepository.window()` into its first-run floor exactly as §3.3 predicts, so the restored
phone did not narrate its restored history; and a relaunch after the restore pushed **nothing**
— the cursors were set from the snapshot, so the phone did not re-upload what it had just been
given.

**One part of the plan's Step 4 was not exercised:** a `gate.dodged` event, which needs the QR
payment flow and therefore a QR poster in the emulator's virtual scene. The event log round-trip
is proven by `transaction.logged`; what remains unproven on-device is specifically an event with
a NULL `transactionUuid`, whose restore path is otherwise identical and is covered by
`TestSnapshotRoundTripsEveryTable` on the server side.

## 8. Open questions

1. **Server URL: build config or Settings field?** Recommendation: a Settings field beside
   the token, because 2B may move hosts and a rebuild to change a URL is friction with no
   benefit.
2. **Repo layout: monorepo or separate?** Recommendation: `backend/core-api/` in this repo.
   One clone, one CI config, and the portfolio story reads better as a single coherent
   system.
3. **Does Phase 3 (`ai-svc`) still exist?** 1D already delivers persona and digests
   on-device at ₹0, with the LLM structurally outside every read path — which is what makes
   the gate work offline. Moving it server-side buys richer context and spends that
   guarantee. This does not block 2A, but it should be settled before 2B fixes the API shape.
4. **1E import and `category`.** The importer may add categories beyond the seeded 21, which
   would make `category` genuinely mutable. 2A syncs it already, so nothing breaks — but the
   "seed-only, stable ids" assumption in §2.1 should be revisited when 1E is specced.
