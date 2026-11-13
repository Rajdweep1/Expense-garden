# Users on core-api + Fortune City history — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline, his loop:
> two commits per task — tests + stubs red, then code green). Steps use `- [ ]`.

**Goal:** per-user scoping on core-api, an admin CLI to create users and import the FC export, and
a phone Restore that works before anything syncs — so his history lands untouched under his account.

**Architecture:** migration 002 adds `app_user` and puts `user_id` into every key and foreign key;
auth resolves the bearer token's SHA-256 to a user id carried on the request context; the store
takes that id on every call. A new `cmd/admin` drives `internal/admin` (users) and
`internal/fcimport` (the importer). On Android, Restore reads the typed url and token instead of
the saved ones.

**Tech stack:** Go 1.27.1, pgx v5.10.0, Postgres 17.11 (local); Kotlin 2.0.20 / Room v4 (no schema
change). No new dependency on either side.

**Spec:** `docs/superpowers/specs/2026-11-13-users-and-fc-history-design.md` (signed off 2026-11-13).

## Global constraints

- Go: no new module; UUIDv5 by hand from `crypto/sha1`. Android: no dependency or version change.
- Money is integer paise, parsed from the decimal string; never a float.
- Tokens: 32 random bytes (`crypto/rand`), base64 std encoding, written to a 0600 file with no
  trailing newline (`O_CREATE|O_EXCL`); only `sha256(TrimSpace(token))` is stored; never printed or logged.
- Store tests point at `TEST_DATABASE_URL=postgres://$(whoami)@localhost:5432/expense_garden_test?sslmode=disable`,
  never `expense_garden` (they `DELETE` every row). Every DB test command runs with **`-p 1`**:
  four packages share that database and `go test ./...` runs packages in parallel.
- One shared DB test helper, `internal/testdb` (`func Store(t *testing.T) *store.Store`: connect,
  `migrations.Apply`, delete every row incl. `app_user`), used by store, admin and fcimport tests;
  the unexported `newTestStore` in `store_test.go` is replaced by it.
- Gradle: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`;
  instrumented runs only with `ANDROID_SERIAL=emulator-5554`.
- Commits plain (`test:` / `feat:` / `docs:`), no attribution lines, named paths only, no push.
- Personal data: the export stays in `~/Documents/fortune-city-export/`; tests use a small
  synthetic fixture under `backend/core-api/internal/fcimport/testdata/`, never real entries.

## Rulings (made in this plan)

- R-P1: Real data import happens in Task 7 after the gate, against `rehearsal` first, then his
  user (name **`rajdweep`**). Cost if wrong: one `UPDATE app_user SET name`.
- R-P2: `README.md` and `CLAUDE.md` command changes ship in the code commit of the task that makes
  them true (they describe how to run the code, not a plan); the 2B runbook (a plan doc) is edited
  but committed only with his say-so. Cost if wrong: one extra docs commit.
- R-P3: The migration test lives in `internal/migrations` and calls an unexported
  `applyThrough(ctx, pool, last string)`; `Apply` becomes `applyThrough(ctx, pool, "")`. No
  test-only export. Cost if wrong: none.
- R-P5 (plan review): `HashToken` lives in `store` (`func HashToken(token string) []byte`, sha256 of
  `strings.TrimSpace`): httpapi already imports store (`router.go:11`), and admin needs it too.
- R-P6 (plan review): the importer writes through a new store method that `INSERT … ON CONFLICT DO
  NOTHING` (`store.ImportHistory`), never `ApplyBatch`, whose last-write-wins upserts would restamp
  and rename on a re-import (`store.go:92-95`). store.go stays the home of every SQL statement.
- R-P7 (plan review): AC-11's Settings half (the button enabled from typed fields) has no automated
  test; it is proven by AC-10's on-device step in Task 7. Cost if wrong: a revert is caught only on device.
- R-P4: Auth depends on an interface, not the store, so `auth_test.go` keeps running without a DB:
  `type TokenLookup interface{ UserByToken(ctx context.Context, token string) (int64, bool, error) }`.
  A lookup error answers 500 `{"error":"auth failed"}` (logged), not 401 — a DB outage must not
  read on the phone as "token rejected".

## Review focus (inputs no task's happy path exercises)

1. A push whose txn references a category the user doesn't have but another user does → refused (FK), whole batch rolls back. (Task 1 test `TestARowCannotPointAtAnotherUsersCategory`.)
2. Migration 002 over a 001 database holding a budget with NULL category and an event with NULL txn → backfilled, not rejected. (Task 1 `TestMigrationBackfillsExistingRowsToTest`.)
3. Header `Bearer  <token> ` with extra spaces, and a token whose user's hash is NULL. (Task 2.)
4. An export row with category not in the map, amount `"12.345"`, `"-5"`, `""` → whole import refused, DB unchanged. (Task 4.)
5. Restore tapped with only the url typed, or with a stale saved token but a new typed one → button disabled / typed one used. (Task 5.)

## Blast radius

- `store.ApplyBatch`, `store.Snapshot`: called by `httpapi/push.go:33`, `httpapi/snapshot.go:6`, `store_test.go`.
- `httpapi.Server{Token}`: `cmd/api/main.go:39`, `auth_test.go`.
- `config.Config.Token`, `SYNC_TOKEN`: `config.go`, `config_test.go`, `main.go`, `README.md`,
  `CLAUDE.md:79`, `docs/superpowers/plans/2026-09-05-phase2b-deploy.md:12,132`.
- Tables: all five synced tables + `schema_migrations`; local DBs `expense_garden` (test data) and `expense_garden_test`.
- Android: `SyncClient(prefs)` at `GardenApp.kt:44` and `SyncRestoreTest.kt:39`;
  `SyncRepository.fetchSnapshot/restore` at `SettingsScreen.kt:207,276`; wire format unchanged.

---

### Task 1: Migration 002 and a per-user store (AC-1, AC-2, AC-4, AC-9)

**Files:** create `internal/migrations/002_users.sql`, `internal/migrations/migrations_test.go`;
modify `internal/migrations/migrations.go`, `internal/store/store.go`, `internal/store/store_test.go`.

**Interfaces — produces:**
- `func (s *Store) CreateUser(ctx context.Context, name string, tokenHash []byte) (int64, error)` (nil hash allowed)
- `func (s *Store) SetTokenHash(ctx context.Context, name string, tokenHash []byte) error` (error `ErrNoSuchUser` if absent)
- `func (s *Store) UserByTokenHash(ctx context.Context, h []byte) (int64, bool, error)`
- `func (s *Store) ApplyBatch(ctx context.Context, userID int64, b Batch) error`
- `func (s *Store) Snapshot(ctx context.Context, userID int64) (Batch, error)`
- `var ErrNoSuchUser = errors.New("store: no such user")`

- [ ] **Step 1: tests + stubs.** Stubs return `errors.New("not implemented")`. **No `002_users.sql`
  in this commit** (a stub would be recorded as applied in `schema_migrations` and the real one never
  run; the tests fail on the missing `user_id` column instead). Create `internal/testdb` (Global
  constraints) and move the store tests onto it. Helper `newUser(t, s, name) int64` → `CreateUser(ctx, name, nil)`.
  Existing tests change only by passing a user id. New tests:
  - `TestUsersWithTheSameIdsDoNotCollide`: A and B each push category 3, payee 7, txn `u1`, budget
    (nil, "2026-09"), event 1 with different names/amounts; each snapshot returns its own values, 1 row per table.
  - `TestOneUserCannotOverwriteAnother`: A pushes txn `u1` amount 100 at stamp 5; B pushes `u1` amount 999 at stamp 9; A's snapshot still 100. Same for A's overall budget `*|2026-09`: B's tombstone for it at a later stamp leaves A's budget in place.
  - `TestARowCannotPointAtAnotherUsersCategory`: A has category 3; B pushes a txn with category 3 and its own payee → `ApplyBatch` errors and B's snapshot is empty (rollback).
  - `TestARowCannotPointAtAnotherUsersPayeeOrTxn`: B's txn → A's payee id refused; B's event → A's txn uuid refused.
  - `TestSnapshotHoldsOnlyTheCallersRows`.
  - `TestUserByTokenHash`: found for a set hash; `(0,false,nil)` for unknown; a user with NULL hash is never found by `nil` or empty.
  - migrations: `TestMigrationBackfillsExistingRowsToTest` — drop schema objects, `applyThrough(…, "001_init.sql")`,
    insert one row per table (incl. NULL-category budget, NULL-txn event), `Apply`, assert every row's
    `user_id` = the `test` user's id and counts unchanged; `TestMigrationsApplyOnAnEmptyDatabase`.
  Run: `cd backend/core-api && TEST_DATABASE_URL=… go test -p 1 ./internal/store ./internal/migrations`
  Expected: FAIL — "not implemented" / missing `user_id` column. Commit `test: per-user store and users migration`.
- [ ] **Step 2: code.** `002_users.sql` per spec §Schema, in this order (names read from
  `pg_constraint` on 2026-11-13):
  1. create `app_user`; insert `test`; add nullable `user_id` to the five tables; backfill; set NOT NULL;
  2. drop the 6 FKs: `category_parent_id_fkey`, `payee_default_category_id_fkey`, `txn_category_id_fkey`,
     `txn_payee_id_fkey`, `budget_category_id_fkey`, `game_event_transaction_uuid_fkey`;
  3. drop the 6 keys: `category_pkey`, `payee_pkey`, `payee_vpa_key`, `txn_pkey`,
     `budget_category_id_month_key`, `game_event_pkey`; drop `idx_txn_occurred_at`, `idx_game_event_txn`;
  4. add composite PKs and uniques; then the composite FKs (`ON DELETE SET NULL (col)` for payee's
     default category and the event's txn; `ON DELETE CASCADE` kept for budget → category); recreate
     the two indexes with `user_id` first. The `txn_*_check` constraints stay. Store: every statement takes `user_id`; `ON CONFLICT` targets the composite keys.
  Run the same command. Expected: PASS, all store + migration tests. Commit `feat: users table and per-user sync store`.

### Task 2: Token auth per user; SYNC_TOKEN goes (AC-3, AC-12)

**Files:** modify `internal/httpapi/{auth,router,push,snapshot}.go`, `internal/httpapi/auth_test.go`,
`internal/config/{config,config_test}.go`, `cmd/api/main.go`, `README.md`, `CLAUDE.md`.

**Interfaces — consumes** Task 1's store. **Produces:** `type TokenLookup interface{…}` (R-P4);
`Server{Store *store.Store; Users TokenLookup}`; `store.HashToken` (R-P5); `store.Store` gets
`UserByToken(ctx, token) (int64, bool, error)` = `UserByTokenHash(ctx, HashToken(token))`.

- [ ] **Step 1: tests + stubs.** `auth_test.go` uses a fake `TokenLookup` (map). Keep every
  existing "refused" case; change "token with a trailing space" to expect success (AC-12); add
  `TestTwoSpacesAfterBearerStillAuthenticate`, `TestALookupErrorIs500NotUnauthorized`,
  `TestTheHandlerSeesTheCallersUserID` (wrap a recording `http.HandlerFunc` in `requireUser`
  and assert `userIDFrom(r.Context())` equals the fake's id; no test-only route in the router). `config_test.go`: drop SYNC_TOKEN tests; `TestOnlyTheDatabaseURLIsRequired`.
  Run `go test ./internal/httpapi ./internal/config` → FAIL. Commit `test: per-user token auth`.
- [ ] **Step 2: code.** `requireUser` strips `"Bearer "`, trims, refuses empty → 401, looks up,
  stores id under an unexported context key. Push/snapshot read it. Config drops `Token`, `ErrWeakToken`.
  README + `CLAUDE.md:79` run commands lose `SYNC_TOKEN` and gain the `admin` steps.
  Run `go vet ./... && go test -p 1 ./...` (with TEST_DATABASE_URL) → PASS. Commit `feat: authenticate each request as a user`.

### Task 3: `admin create-user` / `set-token` (AC-5)

**Files:** create `internal/admin/users.go`, `internal/admin/users_test.go`, `cmd/admin/main.go`.

**Produces:** `func CreateUser(ctx, s *store.Store, name, tokenFile string) error`;
`func SetToken(ctx, s *store.Store, name, tokenFile string) error`;
`func writeNewToken(path string) (string, error)` (unexported).

- [ ] **Step 1: tests + stubs.** `TestCreateUserWritesA0600TokenThatAuthenticates` (file mode
  0600, no trailing newline, `UserByToken` finds the new id, DB `token_sha256` ≠ token bytes);
  `TestCreateUserRefusesAnExistingFile` (file untouched, no user created);
  `TestCreateUserRefusesAnExistingName`; `TestSetTokenReplacesTheOldToken` (old fails, new works);
  `TestSetTokenOnAMissingUserFails` (and leaves no file behind). Run → FAIL. Commit `test: admin user commands`.
- [ ] **Step 2: code.** Write the file before the DB change; on DB error remove the file. `cmd/admin`:
  `admin create-user <name> <file>`, `set-token <name> <file>`, `import-fc <name> <export.json>`
  (wired in Task 4), reads `DATABASE_URL`, runs migrations, prints only "created user <name> (id N);
  token written to <file>". Run `go test -p 1 ./...` → PASS. Commit `feat: admin commands to create users and set tokens`.

### Task 4: The importer (AC-6, AC-7, AC-8)

**Files:** create `internal/fcimport/{fcimport.go,fcimport_test.go,testdata/sample.json}`; modify `cmd/admin/main.go`.

**Produces:**
- `type Entry struct{ Date, Category, Note, AmountINR, Type, Source string; OrderInDay int }` (export field names via json tags)
- `func Parse(r io.Reader) ([]Entry, error)` — `DisallowUnknownFields`; refuses `type != "expense"`
- store (R-P6): `func (s *Store) ImportHistory(ctx, userID int64, cats []Category, payee Payee, txns []Txn) (inserted int, err error)` — one transaction, every insert `ON CONFLICT DO NOTHING`
- `func Paise(s string) (int64, error)` — `^\d+(\.\d{1,2})?$`, > 0, else error
- `func CategoryFor(e Entry) (int64, error)` — map below; unknown → error naming it
- `func Plan(entries []Entry, now int64, zone *time.Location) ([]store.Txn, error)` — all-or-nothing
- `func Import(ctx, s *store.Store, userID int64, entries []Entry, now int64) (Summary, error)`
- `type Summary struct{ Inserted, Skipped int; ByCategory map[int64]CategoryTotal }`

Mapping (raw FC → id): Food 1, Drinks 1, Transportation 3, Housing 4, Medical 5, Entertainment 6,
Shopping 7, Misc. 11, GYM 1001, EMI 1002, bae 1003, ph recharg 1004, Electronics 1005, gift 1006
(**1003 when Date starts "2026-"**), Accessorie 1007. Categories written: the 21 seed rows
(names/parents/flags copied from `AppDatabase.kt:73-82`) with id 3 "Transportation", id 5
"Medical", id 1 `is_necessity=true`; plus 1001 GYM (nec), 1002 EMI, 1003 bae, 1004 ph recharge (nec),
1005 Electronics, 1006 gift, 1007 Accessories — all `updated_at = now`, `ON CONFLICT DO NOTHING`.
Payee id 1 "Offline payment history". Txn: uuid = UUIDv5(namespace = sha1 of "expense-garden/fc",
name = `date|category|order_in_day|amount_inr|note` raw); `occurred_at` = 12:00 Asia/Kolkata on
date + (index within that date in file order) minutes; `ON CONFLICT DO NOTHING`; no events.

- [ ] **Step 1: tests + stubs.** Fixture (with `type` and `source` fields like the real export): ~8 synthetic rows covering two rows sharing (date,
  order) in different categories, a 2025 and a 2026 gift, Drinks, Misc., a `.5` amount.
  `TestPaise` table (`"10"`→1000, `"286414.5"`→28641450, `"0.01"`→1, refusals `"12.345"`, `"-5"`, `""`, `"1e3"`, `"0"`);
  `TestGift2026GoesToBae`; `TestEveryExportNameMaps` (the 15 raw names);
  `TestUnknownCategoryRefusesThePlan`; `TestTimesFollowFileOrderWithinADate` (distinct, ascending, 12:00 IST base);
  `TestUUIDIsStableAndRaw` (same entry → same uuid; differs when note differs);
  DB: `TestImportWritesExactlyTheFixture` (counts, sum, notes byte-equal, 0 events, categories per AC-8),
  `TestReimportAddsNothing` (inserted 0, and every txn's `updated_at` and every category name and
  stamp unchanged — including after a test-side rename of category 1001 between the two runs),
  `TestABadRowLeavesTheDatabaseUnchanged`, `TestANonExpenseRowIsRefused`. Run → FAIL. Commit `test: Fortune City importer`.
- [ ] **Step 2: code**, then wire `import-fc` (prints Summary per category name in rupees). Run `go test -p 1 ./...` → PASS. Commit `feat: import Fortune City history under a user`.

### Task 5: Restore from the typed url and token (AC-11)

**Files:** modify `sync/SyncClient.kt`, `sync/SyncRepository.kt`, `ui/SettingsScreen.kt`; create `sync/SyncEndpoint.kt`; test `androidTest/…/sync/SyncRestoreTest.kt`.

**Produces:** `data class SyncEndpoint(val url: String, val token: String)` (trims both, url
`trimEnd('/')` like `SyncPrefs`); `SyncClient.snapshot(endpoint: SyncEndpoint)` — the constructor and
`push` are unchanged, `open()` takes the endpoint; `SyncRepository.fetchSnapshot(endpoint: SyncEndpoint)`
(fails BAD_URL when either is blank);
`SyncRepository.restore(snap, endpoint: SyncEndpoint)` saves `prefs.serverUrl/token` only when it returns true.

- [ ] **Step 1: tests + stubs.** In `SyncRestoreTest`: a recording fake server (extend the existing
  ServerSocket helper) that serves a snapshot JSON on GET and records every request line.
  `restore_reads_the_typed_endpoint_without_saving_it` (prefs empty, phone holds a logged test
  payment; fetch with endpoint → snapshot arrives, prefs still unconfigured, server saw only `GET /v1/sync/snapshot`);
  `a_successful_restore_saves_the_endpoint`; `a_refused_restore_saves_nothing`; existing tests pass an endpoint.
  Run `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.sync.SyncRestoreTest` → FAIL. Commit `test: restore from the typed server`.
- [ ] **Step 2: code.** Settings: Restore `enabled = url.isNotBlank() && token.isNotBlank()`; the
  endpoint built at fetch time is kept in `restoreOffer` with the snapshot and passed to `restore`, so a
  field edited while the dialog is open can't be saved in its place; after a true restore set `saved = true`.
  Run the same command, then `./gradlew testDebugUnitTest` (expect 428 pass) and the full
  instrumented suite on the emulator (expect 105 + 3 new = 108). Commit `feat: restore before anything syncs`.

### Task 6: Local gate

- [ ] `cd backend/core-api && gofmt -l . && go vet ./... && TEST_DATABASE_URL=… go test -p 1 ./... -count=1` → no files, no findings, PASS.
- [ ] `wc -l` every changed `.go`/`.kt` file < 800.
- [ ] JVM 428/428; instrumented 108/108 on `emulator-5554` only. Update CLAUDE.md counts in Task 5's code commit if they moved.
- [ ] `pg_dump expense_garden > ~/Documents/fortune-city-export/expense_garden-before-002.sql` (0600), then boot core-api on `expense_garden`: migration applies; per-table counts before = after under `test`.

### Task 7: Rehearsal, then his import (AC-10, AC-6 on real data)

- [ ] `admin set-token test …`, `create-user rehearsal …`, `import-fc rehearsal <export>` → summary equals AC-6.
- [ ] Emulator (test data, never synced): type url `http://10.0.2.2:8080` + rehearsal token → Restore without Save.
  Server log shows only `GET /v1/sync/snapshot` before the restore; device DB (`run-as … sqlite3`) has
  1,779 txns summing 167216172; screenshot the history in the dashboard; log one payment → push 200.
  Note how the album shows 2024–2025 months (report only).
- [ ] `create-user rajdweep ~/Documents/fortune-city-export/rajdweep.token`, `import-fc rajdweep <export>`
  → AC-6 numbers; SQL check per category against the spec's table; 0 game_event rows for him.
- [ ] Then review round 1 (code + spec/AC reviewers, Opus) and the readiness summary for his PR go.
