# Users on core-api, and the Fortune City history under his account

Date: 2026-11-13. Status: signed off by him at Gate 1, 2026-11-13.

## Why

core-api (Phase 2A) mirrors one phone behind one shared `SYNC_TOKEN`. No table records whose
rows are whose (`backend/core-api/internal/migrations/001_init.sql`: category, payee, txn,
budget, game_event, nothing else). His Fortune City history (1,779 expenses, ₹16,72,161.72,
2024-01-05 → 2026-11-10, scraped and verified 2026-11-13, export at
`~/Documents/fortune-city-export/fortune_city_2024-01_to_2026-11.json`) must land under **his own
account**, separate from the test data already in the database, and **exactly as recorded**.

Signup, login screens and multi-device are out of scope (later, R22). This spec adds the users
table and per-user scoping on the server, an admin command to create a user, and a one-off importer.

## Rulings from chat (2026-11-13)

1. A users table now; signup and the rest of R22 later.
2. Existing rows in the database are test data. They move under a user named `test`; nothing is deleted.
3. His history goes under a new user; nothing in it is modified: amount, note and date are FC's exactly.
4. Category names: the FC name is used. Where the app already has a near-identical category,
   that row is **renamed** to FC's name (id unchanged); otherwise a custom category is created
   with FC's name. His exceptions are listed explicitly.
   - Transport (id 3) → **Transportation**; Health (id 5) → **Medical**.
   - Housing (4), Shopping (7), Entertainment (6) already match.
   - FC's **Food** and **Drinks** both go into the app's **Food & Drinks** (id 1), name unchanged
     (his call: "keep it food n drinks only", "ya merge drinks").
   - FC's **Misc.** maps to the app's **Misc** (id 11), name unchanged (his call: "misc is fine").
   - Custom, new: **GYM, EMI, bae, ph recharge, Electronics, gift, Accessories** (ids 1001–1007).
   - FC's screen cut off two names; the real ones (his answer): "ph recharg" → **ph recharge**,
     "Accessorie" → **Accessories**. No other name was cut off.
5. Necessities: **Food & Drinks, GYM, Medical, ph recharge, Transportation, Housing** (his list;
   Housing confirmed). Food & Drinks becomes a necessity; its subcategories Restaurants, Delivery
   and Chai & Snacks do not. The other seed flags stay as `SeedCallback` has them (Groceries,
   Family, Investments, Fuel, Metro & Bus, Rent, Utilities are necessities), and EMI is not one —
   he reviewed the full list on 2026-11-13 and kept it.
5a. **The one deliberate change to the data (his call):** every FC **gift** entry dated 2026
   (70 entries, ₹14,003) goes into **bae**. Notes, amounts and dates stay FC's. gift keeps its one
   2025 entry (₹653); bae becomes 93 entries, ₹23,022.
6. Payee: one cash payee, **"Offline payment history"** (no VPA).
7. History grows no plants: no `game_event` rows are written for it.
8. Data lives in the Mac's local Postgres until the 2B deploy; at deploy the same migrations run
   on Neon and his user's rows are copied across (that copy is 2B's work, not this spec's).
9. No overlap handling: the app goes live at the end of November. **The rest of November is
   imported before go-live** (same command, longer export), because pushes are one-way: rows added
   on the server after his phone restores never reach the phone without a second wipe-and-restore
   (`RestorePlan.kt:32-33`). (Design critic finding 5; his OK 2026-11-13.)
10. Process: the company delivery loop (design → plan + fresh plan review → TDD in two commits →
    local gate → review round 1 → his PR go). No ticket ids: this is the personal repo, so commits
    stay plain (`feat:`/`test:`), and nothing is pushed before his go.

## Design

### Schema: `002_users.sql`

- `app_user (id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL UNIQUE, token_sha256 BYTEA UNIQUE,
  created_at BIGINT NOT NULL)`. Only a SHA-256 of the token is stored. A random 32-byte token has
  256 bits of entropy, so a fast hash is enough; a slow KDF buys nothing here. A NULL hash means
  the user exists but cannot sign in.
- Every synced table gains `user_id BIGINT NOT NULL REFERENCES app_user(id)`.
- Keys become per user, because the phone assigns ids itself and every phone assigns the same
  ones (categories 1–602 from `AppDatabase.SeedCallback`, payee and event ids from SQLite rowids):
  - category PK `(user_id, id)`; payee PK `(user_id, id)`; txn PK `(user_id, uuid)`;
    game_event PK `(user_id, id)`; budget unique `NULLS NOT DISTINCT (user_id, category_id, month)`;
    payee VPA unique `(user_id, vpa)`.
  - Every foreign key includes `user_id`, so one user's row can never point at another's:
    category.parent → category, payee.default_category → category, txn → payee and category,
    budget → category, game_event.transaction_uuid → txn.
  - The two `ON DELETE SET NULL` foreign keys use Postgres's column list
    (`SET NULL (default_category_id)`, `SET NULL (transaction_uuid)`), so `user_id` is never nulled.
    Needs Postgres ≥ 15; local is 17.11, Neon's default is 17.
- Backfill inside the migration: insert user `test` (token NULL), set every existing row's
  `user_id` to it, then add the NOT NULL, keys and foreign keys. One transaction (the migration
  runner already wraps each file in one, `migrations.go:49`).
- Indexes: the composite PKs cover per-user lookups; `idx_txn_occurred_at` becomes
  `(user_id, occurred_at)`; `idx_game_event_txn` becomes `(user_id, transaction_uuid)`.

### Server code

- Auth (`httpapi/auth.go`): hash the bearer token, look up `app_user` by `token_sha256`, put the
  user id on the request context. Unknown or missing token → 401 `{"error":"unauthorized"}`, the
  same answer as today, so the phone's `SyncFailure.UNAUTHORIZED` copy is unchanged.
- Store: `ApplyBatch(ctx, userID, batch)` and `Snapshot(ctx, userID)`. Every statement carries
  `user_id`; every `ON CONFLICT` targets the per-user key. The last-write-wins rules are unchanged.
- Config: `SYNC_TOKEN` is removed; tokens live in `app_user`. `DATABASE_URL` stays required.
  The README, CLAUDE.md's run command and the 2B runbook are updated to match.
- Auth trims the bearer token before hashing (the phone trims what is pasted, `SyncPrefs.kt:25`;
  today's server compares it untrimmed, `auth.go:21`).
- Wire format: unchanged. No JSON field is added or renamed; the phone never sends a user id.

### Android: Restore before anything syncs (design critic findings 1–2)

Today Restore is enabled only after Save (`SettingsScreen.kt:221`), and Save's check is a real
push whenever rows are waiting (`SyncRepository.kt:76-77`). On a phone that has never synced
(`lastPushedAt` = 0, `SyncPrefs.kt:28-30`) that push sends every test payee, txn and event into his
account before Restore can run. The server never deletes a txn, so they would stay, the phone's
payee 1 would overwrite or attach to "Offline payment history", and the test plants would come
back on Restore. A freshly installed phone would also push its 21 seed categories stamped later
than the import, undoing the renames and the Food & Drinks necessity.

- Restore uses the url and token **typed in the fields**, without Save first; it is enabled when
  both are non-blank. On success it saves them, exactly as Save would.
- Nothing pushes before that: the scheduled push and Save's check already do nothing while
  `syncPrefs.isConfigured` is false, so a phone that restores first never pushes beforehand.
- Save keeps today's behaviour (it is the "start backing up this phone" path).

### Admin command: `cmd/admin`

- `admin create-user <name> <token-file>`: creates the user, generates a 32-byte random token,
  writes it base64 to `<token-file>` with mode 0600, stores only its hash. Refuses if the name
  exists or the file already exists. Never prints the token.
- `admin set-token <name> <token-file>`: same, for an existing user (used once, for `test`, so
  the emulator can sync again).
- `admin import-fc <name> <export.json>`: the importer, below.

### Importer

- Reads the export's JSON (fields `date`, `category`, `note`, `amount_inr`, `order_in_day`).
- One transaction:
  1. The user's categories: the app's 21 seed rows (ids and parents as in `SeedCallback`) with the
     two renames and the necessity change, plus the seven custom ones at ids 1001–1007. All 21
     seed rows go in, not just the used ones: after a Restore the phone sets its push cursor to the
     newest restored stamp (`SyncRepository.kt:124`), so a seed row the server lacks would never be
     pushed, and the first payment in that category would fail the server's foreign key.
     Existing rows are left alone (`ON CONFLICT DO NOTHING`). Their `updated_at` is the import's
     start time, so after a Restore the phone's cursor starts past them.
  2. The payee "Offline payment history" (id 1, no VPA, no default category).
  3. One txn per entry: `amount_paise` parsed from the decimal string exactly (no floats; at most
     two decimals, else refuse), `note` = FC's text byte for byte, `category_id` by the mapping in
     rulings 4 and 5a, `source = IMPORT`, `status = LOGGED`, `regret = UNRATED`,
     `breached_at_logging = false`, `occurred_at` = 12:00 Asia/Kolkata on FC's date plus
     (the row's position within its date in the export's file order − 1) minutes. FC's
     `order_in_day` counts within a (date, category), not a day: 954 rows share a (date, order)
     with another row, so it cannot order a day on its own (design critic finding 4). `created_at = updated_at` = the import's start time in millis.
  4. `uuid` is derived, not random: a UUIDv5 (stdlib `crypto/sha1`, no new dependency) of FC's **raw**
     `date|category|order_in_day|amount|note` — the export's own strings ("ph recharg", "gift"
     before the move to bae), never the mapped ones, so re-running the same or a longer export adds only
     the new entries (`ON CONFLICT DO NOTHING`).
- An unknown FC category name refuses the whole import, naming it. Nothing is guessed.
- Prints counts and the rupee total per category so the result can be checked against FC.

### Callers, walked

- **The emulator (test user).** After the migration its stored token no longer matches anything:
  push and Save's check get 401 → "Token rejected" in Settings. Fix: `admin set-token test`, paste
  the new token on the emulator. Its rows are all still there under `test`.
- **His phone at go-live.** Settings → type url + his token → Restore (no Save first). `RestorePlan` shows the counts
  (1,779 payments). Restore wipes the phone's payees, txns, budgets and events and upserts the
  categories (`SyncRepository.kt:106–120`), so his test entries on the phone go and the history
  arrives. Custom categories appear in the picker and stats; the two renamed ones show FC's names.
  The garden is empty (no events) and grows from his first real payment.
- **A stranger with no or a wrong token.** 401, as today.
- **Two users with the same ids.** Each sees only their own rows; neither can overwrite the other.

## Acceptance criteria

- **AC-1** Migration `002_users.sql` applies on a database holding 001's data, and on an empty one;
  every pre-existing row ends under user `test`; no row is lost (counts per table equal before and after).
- **AC-2** A push and a snapshot touch only the caller's rows: user B's snapshot contains none of
  user A's; B pushing A's category id, payee id, txn uuid, event id or budget key changes none of A's rows.
- **AC-3** A missing, malformed or unknown token gets 401 on push and snapshot; a token whose user
  has a NULL hash cannot sign in.
- **AC-4** A row can't reference another user's category, payee or txn (the foreign keys refuse it).
- **AC-5** `create-user` and `set-token` write a 0600 file holding a token that authenticates as
  that user; the database holds only its SHA-256; the token never reaches stdout or logs.
- **AC-6** The import of the full export under his user gives exactly 1,779 txns totalling
  167,216,172 paise; per-category counts and totals equal to the export's after the mapping
  (Food + Drinks → Food & Drinks, Misc. → Misc, gift 2026 → bae; so gift = 1 / ₹653 and
  bae = 93 / ₹23,022); every note identical byte for byte; zero game_event rows.
- **AC-7** Re-running the import adds nothing; an unknown category or an amount with more than two
  decimals refuses the whole import and leaves the database unchanged.
- **AC-8** His user's categories are the 21 seed rows with Transportation and Medical renamed and
  Food & Drinks a necessity, plus the seven custom ones; among the categories the history uses, the
  necessities are exactly Food & Drinks, GYM, Medical, ph recharge, Transportation and Housing.
- **AC-9** The existing behaviour is unchanged per user: every current store test passes,
  rewritten only to pass a user id.
- **AC-10** End to end on the emulator, against a **rehearsal** user (`create-user rehearsal`,
  `import-fc rehearsal`), never his own — the server has no txn delete, so a test payment would stay
  in his account: core-api started locally; the emulator, holding test data and never synced, types
  the url and token and restores without Save; the server's rehearsal user gains no rows before the
  restore; the device's database matches AC-6's count and total; then a new payment pushes. His own
  user is imported only after AC-10 passes.
- **AC-11** Restore works from the typed url and token with nothing saved; it saves them on success;
  and no push reaches the server before it (Android test against a fake server).
- **AC-12** A token with surrounding whitespace authenticates as the same user.

## Out of scope

Signup, login and account screens; per-user rate limits; token rotation UI; the Neon copy (2B);
PromptFacts reading custom or renamed category names (the AI layer will call GYM, bae, the other
customs **and Transportation and Medical** "Misc", `PromptFacts.kt:33,50-55`; design critic finding 6); how the album shows months with
spending but no garden events (checked during AC-10, findings reported, not fixed here).
