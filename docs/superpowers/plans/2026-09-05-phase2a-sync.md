# Phase 2A — Sync Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wipe the app, restore from the server, get everything back — every rupee and the whole garden.

**Architecture:** Room stays the source of truth; Postgres is a replica that never sits in any read path, let alone the payment path. The phone stamps every mutable row with a monotonic logical clock, pushes only what has changed, and advances its cursor only on a 2xx. `game_event` is append-only and syncs by id watermark — the same pattern 1D hardened for the digest cursor. Restore is one full snapshot download applied in foreign-key order with ids preserved.

**Tech Stack:** Kotlin, Room (v3→v4), `java.net.HttpURLConnection` + `org.json` (framework — zero new Android dependencies). Go (stdlib `net/http` + `pgx/v5`), Postgres 17.

**Spec:** `docs/superpowers/specs/2026-09-05-phase2a-sync-design.md`

---

## Guardrails (read before Task 1)

- **Do NOT add Android dependencies.** `gradle/libs.versions.toml` is deliberately pinned. Every "you'd normally use Retrofit / OkHttp / DataStore / WorkManager here" instinct is wrong for this repo. The sync client is `HttpURLConnection` + `org.json`, exactly like `ai/GeminiClient.kt`. The Go module is a separate program and may take dependencies; that freeze does not apply to it.

- **Every Gradle command needs the Studio JBR.** The default `java` on this machine is 11; AGP 8.5.2 requires 17, and each shell invocation starts fresh, so the export must be on *every* command — not once per session.

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```

- **Gradle task names matter.** Use `testDebugUnitTest --tests '<pattern>'` for JVM tests (plain `:app:test` is a lifecycle task with no filter option), and `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.ClassName>` for instrumented ones.

- **`connectedDebugAndroidTest` UNINSTALLS both APKs when it finishes.** Chaining `installDebug` *before* it in one command leaves the device with no app, and `am start` then fails with nothing in logcat. Always install last, in its own invocation.

- **Instrumented test names must be snake_case, not backticks-with-spaces.** `minSdk = 26` means D8 targets a DEX version below 040, which rejects spaces in method names. Backtick names are fine in `app/src/test` (JVM), fatal in `app/src/androidTest`.

- **Room schema hazard.** Running any Gradle build with modified entities but the OLD `version =` silently overwrites the PREVIOUS version's schema JSON. Order is always: edit entities → bump version → build → read the generated SQL → write the migration. Recovery is `git checkout -- app/schemas/.../3.json`.

- **A column's SQL default and its Kotlin default are different things, and this plan depends on the difference.** `updatedAt` gets `@ColumnInfo(defaultValue = "0")` so the migration's `ADD COLUMN ... NOT NULL DEFAULT 0` matches what Room validates, but it gets **no Kotlin default value**, so every construction site must supply a stamp and the compiler enforces it. Do not "tidy" this by adding `= 0L`; that silently reopens the hole spec §2.4 closes.

- **Postgres on this machine is shared.** A Homebrew `postgresql@17` service is already running on 5432 and serves the user's SiteRecon work. Create and use the `expense_garden` database only. Never `DROP DATABASE` anything else, never touch `postgres`, and never run `psql` without an explicit `-d expense_garden`.

- **`SeedCallback` is the one category write the compiler cannot police.** It inserts with raw SQL, bypassing the entity constructor, so it must set `updatedAt` explicitly. Left at the column default of 0, every seeded category is invisible to the dirty predicate forever — and since `txn.category_id` is a real FK on the server, the first transaction push is rejected and degrades to silence. Fresh installs only; the migration covers upgrades, which is why no upgrade test catches it.

- **`targetSdk 35` blocks cleartext HTTP, so `http://10.0.2.2:8080` fails before a packet leaves the device.** The exception is swallowed by `SyncClient` and looks exactly like "nothing to sync". A `network_security_config.xml` is required: strict in `src/main`, with a `src/debug` override permitting cleartext to `10.0.2.2` / `localhost` / `127.0.0.1` only, so no shipped build is weakened.

- **If a step's output doesn't match its Expected line: STOP and report.** Do not improvise.

- **Task 14 is the acceptance gate.** No task is "done" until wipe-and-restore round-trips.

- Commits: plain messages, no `Co-Authored-By` / Claude / AI attribution lines. Never `git push`. Never commit files under `docs/`.

## File structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/.../data/Entities.kt` | modify | `updatedAt` on 4 entities; new `SyncTombstoneEntity` |
| `app/src/main/java/.../data/Migrations.kt` | modify | `MIGRATION_3_4` |
| `app/src/main/java/.../data/AppDatabase.kt` | modify | version 4, new entity, new DAO |
| `app/src/main/java/.../data/Daos.kt` | modify | stamp 3 UPDATEs; `SyncDao` for dirty-row reads |
| `app/src/main/java/.../sync/SyncPrefs.kt` | **create** | server URL, token, 3 cursors. `sync_secrets.xml` |
| `app/src/main/java/.../sync/SyncClock.kt` | **create** | the monotonic logical clock (pure, JVM-tested) |
| `app/src/main/java/.../sync/SyncModel.kt` | **create** | payload data classes + cursor math (pure, no JSON) |
| `app/src/main/java/.../sync/SyncClient.kt` | **create** | the only file touching the network |
| `app/src/main/java/.../sync/SyncRepository.kt` | **create** | collect dirty rows, push, advance cursors, restore |
| `app/src/main/java/.../sync/SyncScheduler.kt` | **create** | debounce change signals into one in-flight push |
| `app/src/main/java/.../data/BudgetRepository.kt` | **create** | `setBudget`, tombstone on clear |
| `app/src/main/java/.../ui/DashboardViewModel.kt` | modify | delegate to `BudgetRepository` |
| `app/src/main/java/.../ui/SettingsScreen.kt` | modify | server URL, token, sync status line |
| `app/src/main/res/xml/backup_rules.xml` | modify | exclude `sync_secrets.xml` |
| `app/src/main/res/xml/data_extraction_rules.xml` | modify | exclude `sync_secrets.xml` |
| `backend/core-api/go.mod` | **create** | Go module, one dependency (`pgx/v5`) |
| `backend/core-api/cmd/api/main.go` | **create** | wiring and listen |
| `backend/core-api/internal/migrations/001_init.sql` | **create** | schema mirroring Room, with real FKs |
| `backend/core-api/internal/store/store.go` | **create** | pgx queries, one transaction per push |
| `backend/core-api/internal/store/migrate.go` | **create** | embedded numbered-SQL runner |
| `backend/core-api/internal/httpapi/*.go` | **create** | router, bearer auth, push, snapshot handlers |
| `backend/core-api/internal/store/store_test.go` | **create** | integration tests — LWW is SQL, so it is tested in SQL |

---

## Task 0: Environment (needs Rajdweep)

**Files:** none

- [ ] **Step 1: Install Go**

Go is not installed on this machine — verified: `go version` returns "command not found" and there is no `/usr/local/go` or Homebrew prefix.

```bash
brew install go
```

Expected: `go version` prints `go1.xx.x darwin/arm64`.

- [ ] **Step 2: Confirm Postgres and create the database**

A Homebrew `postgresql@17` service is already running on port 5432. **It also serves the user's work projects — create only the new database, and touch nothing else.**

```bash
psql -d postgres -c "CREATE DATABASE expense_garden;"
```

Expected: `CREATE DATABASE`. If it already exists, `psql -lqt | cut -d\| -f1 | grep -w expense_garden` prints the name and that is equally fine.

- [ ] **Step 3: Verify the version supports NULLS NOT DISTINCT**

```bash
psql -d expense_garden -tAc "SHOW server_version;"
```

Expected: `17.x` (anything ≥ 15 works). Spec §2.1 depends on `UNIQUE NULLS NOT DISTINCT`, which does not exist before 15.

- [ ] **Step 4: Record the DSN**

The plan uses this connection string throughout. `$(whoami)` is the Postgres superuser under a Homebrew install with trust auth on localhost.

```bash
echo "postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable"
```

Expected: a URL. No password — local trust auth only. **This is a local-development DSN and must never be committed**; it is passed via the `DATABASE_URL` environment variable.

---

## Task 1: Room v4 — `updatedAt` and the tombstone table

Spec §2.2. Additive only: four columns and one table. Nothing existing changes shape.

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/Entities.kt`
- Modify: `app/src/main/java/com/expensegarden/app/data/Migrations.kt`
- Modify: `app/src/main/java/com/expensegarden/app/data/AppDatabase.kt`
- Test: `app/src/androidTest/java/com/expensegarden/app/data/MigrationTest.kt`

- [ ] **Step 1: Add `updatedAt` to the four mutable entities**

In `Entities.kt`, add this as the **last** field of `CategoryEntity`, `PayeeEntity`, `TransactionEntity` and `BudgetEntity`. Note the `@ColumnInfo` default with **no Kotlin default** — see the guardrail on why that asymmetry is load-bearing.

```kotlin
    /** Sync stamp (Phase 2A). Written from SyncClock, never from System.currentTimeMillis()
     *  directly — the logical clock guarantees it is strictly increasing, which is what makes
     *  the `updatedAt > lastPushedAt` dirty-row predicate exact. The column carries a SQL
     *  default so MIGRATION_3_4's ADD COLUMN matches Room's schema validation; the Kotlin
     *  field deliberately has NO default, so every construction site must supply a stamp. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long
```

Add the import if absent: `import androidx.room.ColumnInfo`.

- [ ] **Step 2: Add the tombstone entity**

Append to `Entities.kt`:

```kotlin
/** A row that was deleted locally and must be deleted on the server too (spec §2.2).
 *
 *  A separate table rather than a soft-delete flag on `budget`: a flag would mean adding
 *  `WHERE deleted = 0` to every existing budget query, and one missed filter silently
 *  corrupts both the dashboard and the gate. `budget` is the only synced table with deletes.
 *
 *  `rowKey` encodes the sync key as "<categoryId or *>|<month>" — "3|2026-09" for a category
 *  budget, "*|2026-09" for the overall one. The sentinel is explicit because an empty segment
 *  would be indistinguishable from a malformed key. */
@Entity(tableName = "sync_tombstone", primaryKeys = ["tableName", "rowKey"])
data class SyncTombstoneEntity(
    val tableName: String,
    val rowKey: String,
    val deletedAt: Long,
)
```

- [ ] **Step 3: Register the entity and bump the version**

In `AppDatabase.kt`, add `SyncTombstoneEntity::class,` to the `entities` list, change `version = 3` to `version = 4`, and add the DAO accessor:

```kotlin
    abstract fun syncDao(): SyncDao
```

Do **not** add `MIGRATION_3_4` to `addMigrations(...)` yet — Step 5 needs the generated schema first.

- [ ] **Step 4: Build to generate the v4 schema JSON**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL, and `app/schemas/com.expensegarden.app.data.AppDatabase/4.json` now exists.

Verify 3.json was not clobbered: `git status --short app/schemas` must show **only** `4.json` as untracked. If `3.json` shows as modified, run `git checkout -- app/schemas` and re-read the Room schema guardrail before retrying.

- [ ] **Step 5: Read the generated SQL for the new table**

```bash
python3 -c "
import json
d = json.load(open('app/schemas/com.expensegarden.app.data.AppDatabase/4.json'))
for e in d['database']['entities']:
    if e['tableName'] == 'sync_tombstone':
        print(e['createSql'].replace('\${TABLE_NAME}', 'sync_tombstone'))
"
```

Expected: a `CREATE TABLE IF NOT EXISTS sync_tombstone (...)` statement. The migration below must match it **exactly** — Room validates the schema on open and a mismatch throws `IllegalStateException: Migration didn't properly handle`.

- [ ] **Step 6: Write the migration**

Append to `Migrations.kt`:

```kotlin
/**
 * v3→v4 (Phase 2A): sync stamps and the tombstone table.
 *
 * Each ADD COLUMN carries `NOT NULL DEFAULT 0` to match the entities' @ColumnInfo default —
 * Room compares the two and refuses to open on a mismatch. The follow-up UPDATE then stamps
 * every pre-existing row with the migration time, which is what makes them all dirty against
 * a cursor starting at 0: the first sync uploads the entire history as one batch.
 *
 * The CREATE below must match 4.json's createSql exactly (MigrationTest validates it).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        for (table in listOf("category", "payee", "txn", "budget")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `$table` SET `updatedAt` = $now")
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_tombstone` (" +
                "`tableName` TEXT NOT NULL, `rowKey` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`tableName`, `rowKey`))"
        )
    }
}
```

If Step 5's output differs from the CREATE above in any character, use Step 5's version.

- [ ] **Step 7: Register the migration**

In `AppDatabase.kt`, change `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)` to `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`.

- [ ] **Step 8: Write the failing migration test**

Append inside the class in `MigrationTest.kt` (snake_case — backticks with spaces are fatal in androidTest):

```kotlin
    @Test fun migrate3To4_stamps_existing_rows_and_adds_the_tombstone_table() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL("INSERT INTO category (id, name, parentId, isNecessity) VALUES (1,'Food',NULL,0)")
            execSQL(
                "INSERT INTO txn (uuid, amountPaise, payeeId, categoryId, source, status, regret," +
                    " breachedAtLogging, note, occurredAt, createdAt) " +
                    "VALUES ('u1', 500, 1, 1, 'MANUAL', 'LOGGED', 'UNRATED', 0, NULL, 100, 100)"
            )
            execSQL("INSERT INTO payee (id, name, vpa, defaultCategoryId) VALUES (1,'Chaiwala',NULL,NULL)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        // Every pre-existing row is stamped, so all of it is dirty against a cursor of 0.
        db.query("SELECT updatedAt FROM txn WHERE uuid = 'u1'").use {
            assertTrue(it.moveToFirst())
            assertTrue("txn should be stamped, was ${it.getLong(0)}", it.getLong(0) > 0)
        }
        db.query("SELECT updatedAt FROM payee WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getLong(0) > 0)
        }
        // The tombstone table exists and accepts a composite-key row.
        db.execSQL("INSERT INTO sync_tombstone (tableName, rowKey, deletedAt) VALUES ('budget','*|2026-09',7)")
        db.query("SELECT deletedAt FROM sync_tombstone WHERE tableName='budget' AND rowKey='*|2026-09'").use {
            assertTrue(it.moveToFirst())
            assertEquals(7L, it.getLong(0))
        }
    }
```

Add `import org.junit.Assert.assertTrue` if it is not already present.

- [ ] **Step 9: Run the migration test**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.MigrationTest`

Expected: 3 tests, 0 failures.

This will **not** compile yet if any construction site is missing `updatedAt` — that is the compiler doing its job. Fix each by threading a stamp through; Task 2 provides the clock, so until then pass `0L` **only in test fixtures**, never in production code.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/ app/schemas/ app/src/androidTest/
git commit -m "feat: room v4 with sync stamps and a tombstone table"
```

---

## Task 2: The monotonic clock and the sync prefs

Spec §2.3. The clock is pure and JVM-tested; the prefs mirror `AiPrefs` exactly.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/sync/SyncClock.kt`
- Create: `app/src/main/java/com/expensegarden/app/sync/SyncPrefs.kt`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Test: `app/src/test/java/com/expensegarden/app/sync/SyncClockTest.kt`

- [ ] **Step 1: Write the failing clock test**

Create `app/src/test/java/com/expensegarden/app/sync/SyncClockTest.kt`:

```kotlin
package com.expensegarden.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClockTest {
    private class FakeStore(override var lastStamp: Long = 0L) : SyncClock.Store

    @Test fun `stamps follow the wall clock when it moves forward`() {
        var now = 1_000L
        val clock = SyncClock({ now }, FakeStore())
        assertEquals(1_000L, clock.next())
        now = 2_000L
        assertEquals(2_000L, clock.next())
    }

    @Test fun `two stamps in the same millisecond are still strictly increasing`() {
        val clock = SyncClock({ 5_000L }, FakeStore())
        assertEquals(5_000L, clock.next())
        assertEquals(5_001L, clock.next())
        assertEquals(5_002L, clock.next())
    }

    @Test fun `a backwards clock jump cannot produce a smaller stamp`() {
        var now = 9_000L
        val clock = SyncClock({ now }, FakeStore())
        assertEquals(9_000L, clock.next())
        now = 3_000L                       // NTP correction, timezone edit, manual change
        assertEquals(9_001L, clock.next())
    }

    @Test fun `the stamp survives a restart because it is read back from the store`() {
        val store = FakeStore()
        SyncClock({ 4_000L }, store).next()
        val afterRestart = SyncClock({ 1_000L }, store)   // clock wrong on boot
        assertTrue(afterRestart.next() > 4_000L)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.sync.SyncClockTest'`

Expected: BUILD FAILED — `Unresolved reference 'SyncClock'`.

- [ ] **Step 3: Write the clock**

Create `app/src/main/java/com/expensegarden/app/sync/SyncClock.kt`:

```kotlin
package com.expensegarden.app.sync

/** The app-wide logical clock every `updatedAt` comes from (spec §2.3).
 *
 *  Two jobs, both of which a bare System.currentTimeMillis() fails at:
 *
 *  1. A phone whose clock jumps backwards — NTP correction, timezone edit, manual change —
 *     must not produce a stamp that makes a newer row look older and lose a last-write-wins
 *     comparison on the server.
 *  2. Two rows written inside the same millisecond must not share a stamp. The dirty-row
 *     predicate is `updatedAt > lastPushedAt`, so tied stamps straddling a batch boundary
 *     would leave the second row permanently unpushed.
 *
 *  Point 2 is the defect class 1D hit twice: `runReconciler` stamps a whole batch of events
 *  with one currentTimeMillis(), so timestamps collide and cannot order the batch. This is
 *  what makes the spec's chosen `updated_at` cursor sound rather than merely conventional. */
class SyncClock(
    private val now: () -> Long,
    private val store: Store,
) {
    /** Persistence seam. Production passes SyncPrefs; tests pass a field holder. */
    interface Store {
        var lastStamp: Long
    }

    @Synchronized
    fun next(): Long {
        val stamp = maxOf(now(), store.lastStamp + 1)
        store.lastStamp = stamp
        return stamp
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.sync.SyncClockTest'`

Expected: BUILD SUCCESSFUL, 4 tests passing.

- [ ] **Step 5: Write the prefs**

Create `app/src/main/java/com/expensegarden/app/sync/SyncPrefs.kt`:

```kotlin
package com.expensegarden.app.sync

import android.content.Context

/** Device-local sync settings and the bearer token (spec §4).
 *
 *  A NEW prefs file rather than a field in `ai_secrets.xml`. 1D documented that filename as
 *  load-bearing — `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude
 *  it by exact name so the Gemini key never reaches Google's cloud backup. Widening that
 *  file's meaning invites precisely the rename that silently re-exposes a secret. This file
 *  is excluded by both XMLs under its own name, `sync_secrets`.
 *
 *  Plain SharedPreferences, not EncryptedSharedPreferences: that needs androidx.security.crypto
 *  and the dependency matrix is pinned. Same proportionality call as AiPrefs. */
class SyncPrefs(context: Context) : SyncClock.Store {
    private val prefs = context.getSharedPreferences("sync_secrets", Context.MODE_PRIVATE)

    /** Base URL of core-api, e.g. "http://10.0.2.2:8080". Blank = sync disabled. */
    var serverUrl: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** High-water mark of `updatedAt` successfully pushed. */
    var lastPushedAt: Long
        get() = prefs.getLong(KEY_PUSHED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_PUSHED_AT, value).apply()

    /** High-water mark of `game_event.id` successfully pushed. */
    var lastPushedEventId: Long
        get() = prefs.getLong(KEY_PUSHED_EVENT, 0L)
        set(value) = prefs.edit().putLong(KEY_PUSHED_EVENT, value).apply()

    /** Epoch millis of the last 2xx. 0 = never. Drives the Settings status line. */
    var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_OK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_OK, value).apply()

    /** SyncClock.Store — the logical clock's persisted high-water mark. */
    override var lastStamp: Long
        get() = prefs.getLong(KEY_LAST_STAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_STAMP, value).apply()

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    private companion object {
        const val KEY_URL = "serverUrl"
        const val KEY_TOKEN = "token"
        const val KEY_PUSHED_AT = "lastPushedAt"
        const val KEY_PUSHED_EVENT = "lastPushedEventId"
        const val KEY_LAST_OK = "lastSuccessAt"
        const val KEY_LAST_STAMP = "lastStamp"
    }
}
```

- [ ] **Step 6: Exclude the new prefs file from cloud backup**

In `app/src/main/res/xml/backup_rules.xml`, add beside the existing `ai_secrets` line:

```xml
    <exclude domain="sharedpref" path="sync_secrets.xml" />
```

Add the identical line to `app/src/main/res/xml/data_extraction_rules.xml`, inside **both** the `<cloud-backup>` and `<device-transfer>` elements if both are present.

- [ ] **Step 7: Verify both exclusions are in place**

```bash
grep -c "sync_secrets.xml" app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml
```

Expected: `backup_rules.xml:1` and `data_extraction_rules.xml:1` or `:2`. A zero anywhere means the token would be uploaded to Google Drive — stop and fix before continuing.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/sync/ app/src/main/res/xml/ app/src/test/
git commit -m "feat: monotonic sync clock and the sync prefs store"
```

---

## Task 3: Stamp the three UPDATE statements

Spec §2.4. Inserts are compiler-enforced; these three are not.

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/Daos.kt`
- Modify: `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt`
- Test: `app/src/androidTest/java/com/expensegarden/app/data/SyncStampTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/expensegarden/app/data/SyncStampTest.kt`:

```kotlin
package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Spec §2.4: the three UPDATE statements that can silently forget to stamp. An unstamped
 *  row is invisible to the dirty-row predicate forever, which surfaces only as a restore
 *  that comes up short — so each one gets a test rather than a code review. */
@RunWith(AndroidJUnit4::class)
class SyncStampTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private suspend fun insertTxn(uuid: String, payeeId: Long): Unit {
        db.transactionDao().insert(
            TransactionEntity(
                uuid = uuid, amountPaise = 100, payeeId = payeeId, categoryId = 103,
                source = TxnSource.MANUAL, status = TxnStatus.PENDING_CONFIRM,
                breachedAtLogging = false, note = null, occurredAt = 1L, createdAt = 1L,
                updatedAt = 10L,
            )
        )
    }

    @Test fun set_status_advances_the_sync_stamp() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "P", vpa = null, defaultCategoryId = null, updatedAt = 10L))
        insertTxn("u1", payeeId)

        db.transactionDao().setStatus("u1", TxnStatus.LOGGED, updatedAt = 77L)

        assertEquals(77L, db.transactionDao().byUuid("u1")!!.updatedAt)
    }

    @Test fun set_regret_advances_the_sync_stamp() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "P", vpa = null, defaultCategoryId = null, updatedAt = 10L))
        insertTxn("u2", payeeId)

        db.transactionDao().setRegret("u2", Regret.REGRET, updatedAt = 88L)

        assertEquals(88L, db.transactionDao().byUuid("u2")!!.updatedAt)
    }

    @Test fun set_default_category_advances_the_payee_stamp() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "Q", vpa = "q@ybl", defaultCategoryId = null, updatedAt = 10L))

        db.payeeDao().setDefaultCategory(payeeId, 103, updatedAt = 99L)

        val back = db.payeeDao().byVpa("q@ybl")!!
        assertEquals(103L, back.defaultCategoryId)
        assertEquals(99L, back.updatedAt)
    }

    @Test fun an_unstamped_row_would_be_invisible_to_the_dirty_predicate() = runBlocking {
        // Guards the reason the three tests above exist: the cursor is exclusive, so a row
        // left at its old stamp is never selected again.
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "R", vpa = null, defaultCategoryId = null, updatedAt = 10L))
        insertTxn("u3", payeeId)
        db.transactionDao().setStatus("u3", TxnStatus.LOGGED, updatedAt = 50L)

        assertTrue(db.syncDao().txnsChangedSince(49L).any { it.uuid == "u3" })
        assertTrue(db.syncDao().txnsChangedSince(50L).none { it.uuid == "u3" })
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew compileDebugAndroidTestKotlin`

Expected: BUILD FAILED — `No value passed for parameter 'updatedAt'` on `setStatus`, and `Unresolved reference 'syncDao'`.

- [ ] **Step 3: Add the stamp parameter to the three statements**

In `Daos.kt`, replace the three statements. `TransactionDao`:

```kotlin
    @Query("UPDATE txn SET status = :status, updatedAt = :updatedAt WHERE uuid = :uuid")
    suspend fun setStatus(uuid: String, status: TxnStatus, updatedAt: Long)

    @Query("UPDATE txn SET regret = :regret, updatedAt = :updatedAt WHERE uuid = :uuid")
    suspend fun setRegret(uuid: String, regret: Regret, updatedAt: Long)
```

`PayeeDao`:

```kotlin
    @Query("UPDATE payee SET defaultCategoryId = :categoryId, updatedAt = :updatedAt WHERE id = :payeeId")
    suspend fun setDefaultCategory(payeeId: Long, categoryId: Long, updatedAt: Long)
```

- [ ] **Step 4: Add the SyncDao**

Append to `Daos.kt`:

```kotlin
/** Reads for the push batch (spec §3.2). Every predicate is `> cursor`, never `>=`: cursors
 *  hold the highest value already accepted by the server, so re-sending it would be waste. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM category WHERE updatedAt > :since ORDER BY updatedAt")
    suspend fun categoriesChangedSince(since: Long): List<CategoryEntity>

    @Query("SELECT * FROM payee WHERE updatedAt > :since ORDER BY updatedAt")
    suspend fun payeesChangedSince(since: Long): List<PayeeEntity>

    @Query("SELECT * FROM txn WHERE updatedAt > :since ORDER BY updatedAt")
    suspend fun txnsChangedSince(since: Long): List<TransactionEntity>

    @Query("SELECT * FROM budget WHERE updatedAt > :since ORDER BY updatedAt")
    suspend fun budgetsChangedSince(since: Long): List<BudgetEntity>

    @Query("SELECT * FROM game_event WHERE id > :sinceId ORDER BY id")
    suspend fun eventsAfter(sinceId: Long): List<GameEventEntity>

    @Query("SELECT * FROM sync_tombstone ORDER BY deletedAt")
    suspend fun tombstones(): List<SyncTombstoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTombstone(tombstone: SyncTombstoneEntity)

    @Query("DELETE FROM sync_tombstone WHERE tableName = :tableName AND rowKey = :rowKey")
    suspend fun deleteTombstone(tableName: String, rowKey: String)

    /** Called after a successful push: a tombstone the server has accepted has done its job.
     *  Bounded by the stamp that was actually sent, so a clear that happened during the round
     *  trip carries a higher stamp and survives to be pushed next time. */
    @Query("DELETE FROM sync_tombstone WHERE deletedAt <= :upTo")
    suspend fun clearTombstonesUpTo(upTo: Long)
}
```

- [ ] **Step 5: Thread the clock through LedgerRepository**

`LedgerRepository` already has a private `now()` returning `System.currentTimeMillis()`. Add a constructor parameter and use the clock for sync stamps, leaving `occurredAt`/`createdAt` on the wall clock — those are user-facing times, not sync bookkeeping.

Change the constructor to `class LedgerRepository(private val db: AppDatabase, private val clock: SyncClock)` and update the three call sites:

```kotlin
    suspend fun confirm(uuid: String) {
        db.withTransaction {
            db.transactionDao().setStatus(uuid, TxnStatus.LOGGED, updatedAt = clock.next())
            // ... existing event emission unchanged
        }
    }

    suspend fun discard(uuid: String) =
        db.transactionDao().setStatus(uuid, TxnStatus.DISCARDED, updatedAt = clock.next())
```

`setRegret` and the `setDefaultCategory` call in `resolvePayee` take `updatedAt = clock.next()` the same way, and every `TransactionEntity(...)` / `PayeeEntity(...)` construction in this file gains `updatedAt = clock.next()`.

- [ ] **Step 6: Update every remaining construction site**

Run the build and fix each error the compiler reports. In **test fixtures** pass a literal (`updatedAt = 1L`); in **production code** always pass `clock.next()`.

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug assembleDebugAndroidTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the stamp tests**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.SyncStampTest`

Expected: 4 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ app/src/androidTest/ app/src/test/
git commit -m "feat: sync stamps on every mutating statement, and the sync read dao"
```

---

## Task 4: BudgetRepository and tombstones

Spec §2.5. Moves the only DAO-writing ViewModel behind a repository, and draws the edit-versus-clear distinction the protocol needs.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/data/BudgetRepository.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/GardenApp.kt`
- Test: `app/src/androidTest/java/com/expensegarden/app/data/BudgetRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/expensegarden/app/data/BudgetRepositoryTest.kt`:

```kotlin
package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.sync.SyncClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BudgetRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: BudgetRepository
    private class Store(override var lastStamp: Long = 0L) : SyncClock.Store

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback)
            .allowMainThreadQueries()
            .build()
        repo = BudgetRepository(db, SyncClock({ 1_000L }, Store()))
    }

    @After fun tearDown() = db.close()

    @Test fun setting_a_budget_stamps_it_and_writes_no_tombstone() = runBlocking {
        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = 50_000)

        val row = db.budgetDao().allForMonth("2026-09").single { it.categoryId == 3L }
        assertEquals(50_000L, row.amountPaise)
        assertTrue(row.updatedAt > 0)
        assertTrue(db.syncDao().tombstones().isEmpty())
    }

    @Test fun editing_a_budget_is_an_upsert_not_a_delete_then_insert() = runBlocking {
        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = 50_000)
        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = 70_000)

        assertEquals(70_000L, db.budgetDao().allForMonth("2026-09").single { it.categoryId == 3L }.amountPaise)
        // Crucially: no tombstone. A tombstone here would race the new row on the server and
        // could delete the budget the user just set.
        assertTrue(db.syncDao().tombstones().isEmpty())
    }

    @Test fun clearing_a_budget_writes_a_tombstone_with_the_sentinel_row_key() = runBlocking {
        repo.setBudget(categoryId = null, month = "2026-09", amountPaise = 1_000_000)
        repo.setBudget(categoryId = null, month = "2026-09", amountPaise = null)

        assertNull(db.budgetDao().overallForMonth("2026-09"))
        val tomb = db.syncDao().tombstones().single()
        assertEquals("budget", tomb.tableName)
        assertEquals("*|2026-09", tomb.rowKey)      // sentinel for the overall budget
    }

    @Test fun clearing_a_category_budget_uses_its_id_in_the_row_key() = runBlocking {
        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = 500)
        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = 0)

        assertEquals("3|2026-09", db.syncDao().tombstones().single().rowKey)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew compileDebugAndroidTestKotlin`

Expected: BUILD FAILED — `Unresolved reference 'BudgetRepository'`.

- [ ] **Step 3: Write the repository**

Create `app/src/main/java/com/expensegarden/app/data/BudgetRepository.kt`:

```kotlin
package com.expensegarden.app.data

import androidx.room.withTransaction
import com.expensegarden.app.sync.SyncClock

/** Budget writes (spec §2.5).
 *
 *  This logic used to live in DashboardViewModel, which was the only ViewModel in the app
 *  reaching into a DAO to write. It moves here because sync needs one choke point, and
 *  because two of the five statements that can silently forget a stamp lived there.
 *
 *  The edit-versus-clear distinction is the part that matters for the protocol. `setBudget`
 *  is still delete-then-insert underneath — that is how the unique index is respected — but
 *  an edit must NOT emit a tombstone. Budgets sync on the natural key (categoryId, month),
 *  so an edit is a plain upsert; a tombstone would race the new row on the server and could
 *  delete the budget the user just set. */
class BudgetRepository(
    private val db: AppDatabase,
    private val clock: SyncClock,
    private val onChanged: () -> Unit = {},
) {
    suspend fun setBudget(categoryId: Long?, month: String, amountPaise: Long?) {
        db.withTransaction {
            if (categoryId == null) db.budgetDao().deleteOverallForMonth(month)
            else db.budgetDao().deleteForCategory(categoryId, month)

            if (amountPaise != null && amountPaise > 0) {
                db.budgetDao().insert(
                    BudgetEntity(
                        categoryId = categoryId,
                        month = month,
                        amountPaise = amountPaise,
                        updatedAt = clock.next(),
                    )
                )
                // An edit, not a clear: drop any tombstone left by an earlier clear of this
                // same key, or an unpushed one would delete the row we just wrote.
                db.syncDao().deleteTombstone("budget", rowKey(categoryId, month))
            } else {
                db.syncDao().putTombstone(
                    SyncTombstoneEntity("budget", rowKey(categoryId, month), deletedAt = clock.next())
                )
            }
        }
        onChanged()
    }

    companion object {
        /** Spec §2.1: "<categoryId or *>|<month>". The sentinel is explicit because an empty
         *  segment could not be told apart from a malformed key. */
        fun rowKey(categoryId: Long?, month: String): String = "${categoryId ?: "*"}|$month"
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.BudgetRepositoryTest`

Expected: 4 tests, 0 failures.

- [ ] **Step 5: Delegate from the ViewModel**

In `GardenApp.kt`, add to `AppContainer`:

```kotlin
    val budgets: BudgetRepository = BudgetRepository(db, clock)
```

(`clock` is added to the container in Task 8; until then construct it inline as
`SyncClock({ System.currentTimeMillis() }, syncPrefs)`.)

In `DashboardViewModel.kt`, replace the body of the budget-setting function with a delegation, deleting the inline `withTransaction` block and the three `budgetDao()` calls:

```kotlin
    fun setBudget(categoryId: Long?, amountPaise: Long?) {
        viewModelScope.launch {
            container.budgets.setBudget(categoryId, ledger.currentMonthKey(), amountPaise)
        }
    }
```

- [ ] **Step 6: Verify nothing else regressed**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest assembleDebug`

Expected: BUILD SUCCESSFUL, 171 JVM tests still green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ app/src/androidTest/
git commit -m "feat: budget writes move behind a repository and tombstone on clear"
```

---

## Task 5: The payload types and cursor arithmetic

Spec §3.2. Pure Kotlin, JVM-tested. No `org.json` — that class is an Android stub which throws "not mocked" in JVM unit tests, so keeping the arithmetic away from it is what makes it testable at all. Same boundary discipline as `DigestTrigger` in 1D.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/sync/SyncModel.kt`
- Test: `app/src/test/java/com/expensegarden/app/sync/CursorMathTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/sync/CursorMathTest.kt`:

```kotlin
package com.expensegarden.app.sync

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.SyncTombstoneEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorMathTest {
    private fun budget(stamp: Long) = BudgetEntity(categoryId = 1, month = "2026-09", amountPaise = 1, updatedAt = stamp)
    private fun event(id: Long) = GameEventEntity(id = id, type = "gate.dodged", payloadJson = "{}", transactionUuid = null, createdAt = 0L)
    private fun batch(
        budgets: List<BudgetEntity> = emptyList(),
        tombstones: List<SyncTombstoneEntity> = emptyList(),
        events: List<GameEventEntity> = emptyList(),
    ) = SyncBatch(emptyList(), emptyList(), emptyList(), budgets, tombstones, events)

    @Test fun `an empty batch leaves the cursors untouched`() {
        val before = Cursors(lastPushedAt = 50L, lastPushedEventId = 7L)
        assertEquals(before, CursorMath.advanced(before, batch()))
    }

    @Test fun `the row cursor advances to the highest stamp in the batch`() {
        val after = CursorMath.advanced(Cursors(0L, 0L), batch(budgets = listOf(budget(10), budget(30), budget(20))))
        assertEquals(30L, after.lastPushedAt)
    }

    @Test fun `a tombstone's deletedAt counts toward the row cursor`() {
        // Otherwise a batch of pure deletions would advance nothing and resend forever.
        val after = CursorMath.advanced(Cursors(0L, 0L), batch(tombstones = listOf(SyncTombstoneEntity("budget", "*|2026-09", 99L))))
        assertEquals(99L, after.lastPushedAt)
    }

    @Test fun `the event cursor advances to the highest id`() {
        val after = CursorMath.advanced(Cursors(0L, 0L), batch(events = listOf(event(3), event(9), event(5))))
        assertEquals(9L, after.lastPushedEventId)
    }

    @Test fun `cursors never regress`() {
        val before = Cursors(lastPushedAt = 100L, lastPushedEventId = 40L)
        val after = CursorMath.advanced(before, batch(budgets = listOf(budget(5)), events = listOf(event(2))))
        assertTrue(after.lastPushedAt >= before.lastPushedAt)
        assertTrue(after.lastPushedEventId >= before.lastPushedEventId)
    }

    @Test fun `an empty batch is reported as empty and a populated one is not`() {
        assertTrue(batch().isEmpty)
        assertTrue(!batch(events = listOf(event(1))).isEmpty)
        assertEquals(2, batch(budgets = listOf(budget(1)), events = listOf(event(1))).rowCount)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.sync.CursorMathTest'`

Expected: BUILD FAILED — `Unresolved reference 'SyncBatch'`.

- [ ] **Step 3: Write the model**

Create `app/src/main/java/com/expensegarden/app/sync/SyncModel.kt`:

```kotlin
package com.expensegarden.app.sync

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.PayeeEntity
import com.expensegarden.app.data.SyncTombstoneEntity
import com.expensegarden.app.data.TransactionEntity

/** Everything the phone has that the server does not (spec §3.1).
 *
 *  Deliberately free of org.json: that class is an Android stub which throws "not mocked" in
 *  JVM unit tests, so any arithmetic entangled with it becomes untestable off-device.
 *  Serialization lives at the boundary in SyncClient — the same split DigestTrigger and
 *  DigestRepository use in 1D. */
data class SyncBatch(
    val categories: List<CategoryEntity>,
    val payees: List<PayeeEntity>,
    val txns: List<TransactionEntity>,
    val budgets: List<BudgetEntity>,
    val tombstones: List<SyncTombstoneEntity>,
    val events: List<GameEventEntity>,
) {
    val isEmpty: Boolean
        get() = categories.isEmpty() && payees.isEmpty() && txns.isEmpty() &&
            budgets.isEmpty() && tombstones.isEmpty() && events.isEmpty()

    val rowCount: Int
        get() = categories.size + payees.size + txns.size + budgets.size +
            tombstones.size + events.size
}

/** Everything the server has, for a restore. */
data class SyncSnapshot(
    val categories: List<CategoryEntity>,
    val payees: List<PayeeEntity>,
    val txns: List<TransactionEntity>,
    val budgets: List<BudgetEntity>,
    val events: List<GameEventEntity>,
)

data class Cursors(val lastPushedAt: Long, val lastPushedEventId: Long)

object CursorMath {
    /** The cursors a batch earns IF the server accepts it.
     *
     *  Computed from the batch that was actually built, never from "now": a row written during
     *  the network round trip carries a strictly higher stamp than anything in this batch (the
     *  logical clock guarantees it), so it stays dirty and is caught next time. That is the
     *  same head-first ordering 1D's DigestRepository.window() uses to close its race. */
    fun advanced(current: Cursors, batch: SyncBatch): Cursors {
        val maxStamp = maxOf(
            batch.categories.maxOfOrNull { it.updatedAt } ?: 0L,
            batch.payees.maxOfOrNull { it.updatedAt } ?: 0L,
            batch.txns.maxOfOrNull { it.updatedAt } ?: 0L,
            batch.budgets.maxOfOrNull { it.updatedAt } ?: 0L,
            batch.tombstones.maxOfOrNull { it.deletedAt } ?: 0L,
        )
        return Cursors(
            lastPushedAt = maxOf(current.lastPushedAt, maxStamp),
            lastPushedEventId = maxOf(current.lastPushedEventId, batch.events.maxOfOrNull { it.id } ?: 0L),
        )
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.sync.CursorMathTest'`

Expected: BUILD SUCCESSFUL, 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/sync/ app/src/test/
git commit -m "feat: sync payload types and cursor arithmetic"
```

---

## Task 6: SyncClient — the only file that touches the network

Spec §4. Mirrors `ai/GeminiClient.kt` exactly: `HttpURLConnection`, `org.json`, `withContext(Dispatchers.IO)` as the outermost construct so no caller can get the dispatcher wrong.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/sync/SyncClient.kt`

- [ ] **Step 1: Write the client**

Create `app/src/main/java/com/expensegarden/app/sync/SyncClient.kt`:

```kotlin
package com.expensegarden.app.sync

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.PayeeEntity
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** The only file in the sync layer that touches the network (spec §4).
 *
 *  Same shape as ai/GeminiClient.kt, for the same reasons: HttpURLConnection and org.json are
 *  framework, and the dependency matrix is pinned. The withContext(Dispatchers.IO) is the
 *  outermost construct of every method rather than something call sites remember, because the
 *  scheduler runs on viewModelScope — which is Dispatchers.Main, where HttpURLConnection
 *  throws NetworkOnMainThreadException.
 *
 *  Never logs. The bearer token must not reach logcat, not even truncated. */
class SyncClient(private val prefs: SyncPrefs) {

    /** True when the server accepted the whole batch. */
    suspend fun push(batch: SyncBatch): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("categories", JSONArray(batch.categories.map(::categoryJson)))
            .put("payees", JSONArray(batch.payees.map(::payeeJson)))
            .put("txns", JSONArray(batch.txns.map(::txnJson)))
            .put("budgets", JSONArray(batch.budgets.map(::budgetJson)))
            .put("tombstones", JSONArray(batch.tombstones.map {
                JSONObject().put("tableName", it.tableName).put("rowKey", it.rowKey).put("deletedAt", it.deletedAt)
            }))
            .put("events", JSONArray(batch.events.map(::eventJson)))
        post("/v1/sync/push", body) != null
    }

    suspend fun snapshot(): SyncSnapshot? = withContext(Dispatchers.IO) {
        val body = get("/v1/sync/snapshot") ?: return@withContext null
        runCatching {
            SyncSnapshot(
                categories = body.getJSONArray("categories").map { readCategory(it) },
                payees = body.getJSONArray("payees").map { readPayee(it) },
                txns = body.getJSONArray("txns").map { readTxn(it) },
                budgets = body.getJSONArray("budgets").map { readBudget(it) },
                events = body.getJSONArray("events").map { readEvent(it) },
            )
        }.getOrNull()
    }

    // ---------- transport ----------

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(prefs.serverUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer ${prefs.token}")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

    private fun post(path: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(path, "POST").apply { doOutput = true }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun get(path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(path, "GET")
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---------- writers ----------

    private fun categoryJson(c: CategoryEntity) = JSONObject()
        .put("id", c.id).put("name", c.name)
        .put("parentId", c.parentId ?: JSONObject.NULL)
        .put("isNecessity", c.isNecessity).put("updatedAt", c.updatedAt)

    private fun payeeJson(p: PayeeEntity) = JSONObject()
        .put("id", p.id).put("name", p.name)
        .put("vpa", p.vpa ?: JSONObject.NULL)
        .put("defaultCategoryId", p.defaultCategoryId ?: JSONObject.NULL)
        .put("updatedAt", p.updatedAt)

    private fun txnJson(t: TransactionEntity) = JSONObject()
        .put("uuid", t.uuid).put("amountPaise", t.amountPaise)
        .put("payeeId", t.payeeId).put("categoryId", t.categoryId)
        .put("source", t.source.name).put("status", t.status.name).put("regret", t.regret.name)
        .put("breachedAtLogging", t.breachedAtLogging)
        .put("note", t.note ?: JSONObject.NULL)
        .put("occurredAt", t.occurredAt).put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)

    private fun budgetJson(b: BudgetEntity) = JSONObject()
        .put("categoryId", b.categoryId ?: JSONObject.NULL)
        .put("month", b.month).put("amountPaise", b.amountPaise).put("updatedAt", b.updatedAt)

    private fun eventJson(e: GameEventEntity) = JSONObject()
        .put("id", e.id).put("type", e.type).put("payloadJson", e.payloadJson)
        .put("transactionUuid", e.transactionUuid ?: JSONObject.NULL)
        .put("createdAt", e.createdAt)

    // ---------- readers ----------

    private fun readCategory(o: JSONObject) = CategoryEntity(
        id = o.getLong("id"), name = o.getString("name"),
        parentId = o.optLongOrNull("parentId"), isNecessity = o.getBoolean("isNecessity"),
        updatedAt = o.getLong("updatedAt"),
    )

    private fun readPayee(o: JSONObject) = PayeeEntity(
        id = o.getLong("id"), name = o.getString("name"),
        vpa = o.optStringOrNull("vpa"), defaultCategoryId = o.optLongOrNull("defaultCategoryId"),
        updatedAt = o.getLong("updatedAt"),
    )

    private fun readTxn(o: JSONObject) = TransactionEntity(
        uuid = o.getString("uuid"), amountPaise = o.getLong("amountPaise"),
        payeeId = o.getLong("payeeId"), categoryId = o.getLong("categoryId"),
        source = TxnSource.valueOf(o.getString("source")),
        status = TxnStatus.valueOf(o.getString("status")),
        regret = Regret.valueOf(o.getString("regret")),
        breachedAtLogging = o.getBoolean("breachedAtLogging"),
        note = o.optStringOrNull("note"),
        occurredAt = o.getLong("occurredAt"), createdAt = o.getLong("createdAt"),
        updatedAt = o.getLong("updatedAt"),
    )

    private fun readBudget(o: JSONObject) = BudgetEntity(
        categoryId = o.optLongOrNull("categoryId"), month = o.getString("month"),
        amountPaise = o.getLong("amountPaise"), updatedAt = o.getLong("updatedAt"),
    )

    private fun readEvent(o: JSONObject) = GameEventEntity(
        id = o.getLong("id"), type = o.getString("type"), payloadJson = o.getString("payloadJson"),
        transactionUuid = o.optStringOrNull("transactionUuid"), createdAt = o.getLong("createdAt"),
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

private inline fun <T> JSONArray.map(read: (JSONObject) -> T): List<T> =
    (0 until length()).map { read(getJSONObject(it)) }

/** org.json returns the STRING "null" from optString for a JSON null, which would sail
 *  straight into a non-null Kotlin field. These two helpers are the only safe readers. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else getLong(key)
```

- [ ] **Step 2: Verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL.

There is no unit test here by design — this file is pure IO and serialization against a server that does not exist yet. Its behaviour is proven by Task 14's round-trip, exactly as `GeminiClient` was proven by 1D's Task 15 rather than by a mock.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/sync/SyncClient.kt
git commit -m "feat: sync client over HttpURLConnection with json at the boundary"
```

---

## Task 7: SyncRepository — collect, push, advance, restore

Spec §3.2 and §3.3.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/sync/SyncRepository.kt`
- Modify: `app/src/main/java/com/expensegarden/app/data/Daos.kt` (restore deletes)

- [ ] **Step 1: Add the restore-side deletes**

Append to `SyncDao` in `Daos.kt`:

```kotlin
    /** Restore only (spec §3.3). Reverse foreign-key order; `category` is deliberately absent
     *  because the seed already created it with stable ids and the snapshot upserts over it. */
    @Query("DELETE FROM game_event") suspend fun wipeEvents()
    @Query("DELETE FROM budget") suspend fun wipeBudgets()
    @Query("DELETE FROM txn") suspend fun wipeTxns()
    @Query("DELETE FROM payee") suspend fun wipePayees()

    @Query("SELECT COUNT(*) FROM txn") suspend fun txnCount(): Int
```

Room needs explicit inserts that preserve ids for restore. Add to the existing DAOs:

```kotlin
    // CategoryDao
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    // PayeeDao
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(payees: List<PayeeEntity>)

    // TransactionDao
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(txns: List<TransactionEntity>)

    // BudgetDao
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(budgets: List<BudgetEntity>)

    // GameEventDao
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<GameEventEntity>)
```

- [ ] **Step 2: Write the repository**

Create `app/src/main/java/com/expensegarden/app/sync/SyncRepository.kt`:

```kotlin
package com.expensegarden.app.sync

import androidx.room.withTransaction
import com.expensegarden.app.data.AppDatabase

/** Drives one push and owns restore (spec §3.2, §3.3).
 *
 *  Nothing here is a read path. Screens read Room; this only ever writes to the server, or —
 *  on an explicit restore — replaces Room wholesale from it. */
class SyncRepository(
    private val db: AppDatabase,
    private val client: SyncClient,
    private val prefs: SyncPrefs,
) {
    /** Collect everything dirty. Cursors are read ONCE, before the reads, so a row written
     *  during collection carries a higher stamp and is simply caught next time. */
    suspend fun collect(): SyncBatch {
        val since = prefs.lastPushedAt
        val sinceEvent = prefs.lastPushedEventId
        val dao = db.syncDao()
        return SyncBatch(
            categories = dao.categoriesChangedSince(since),
            payees = dao.payeesChangedSince(since),
            txns = dao.txnsChangedSince(since),
            budgets = dao.budgetsChangedSince(since),
            tombstones = dao.tombstones(),
            events = dao.eventsAfter(sinceEvent),
        )
    }

    /** One push. Returns true if anything was sent AND accepted.
     *
     *  Cursors advance only on success, and only to values derived from the batch that was
     *  actually sent — never to "now". A failure leaves every cursor untouched, so the same
     *  rows are simply re-sent next time; the server's upserts are idempotent, which is what
     *  makes blind retry safe. */
    suspend fun pushOnce(): Boolean {
        if (!prefs.isConfigured) return false
        val batch = collect()
        if (batch.isEmpty) return false

        if (!client.push(batch)) return false

        val advanced = CursorMath.advanced(Cursors(prefs.lastPushedAt, prefs.lastPushedEventId), batch)
        prefs.lastPushedAt = advanced.lastPushedAt
        prefs.lastPushedEventId = advanced.lastPushedEventId
        prefs.lastSuccessAt = System.currentTimeMillis()
        // Tombstones the server has accepted have done their job. Bounded by what was sent, so
        // a clear that happened during the round trip survives to be pushed next time.
        db.syncDao().clearTombstonesUpTo(batch.tombstones.maxOfOrNull { it.deletedAt } ?: 0L)
        return true
    }

    /** How many rows are waiting. Drives the Settings status line (spec §5). */
    suspend fun pendingCount(): Int = collect().rowCount

    /** Replace local data with the server's (spec §3.3).
     *
     *  Guarded by the caller, not here: the UI refuses to offer this against a non-empty
     *  ledger without an explicit confirmation.
     *
     *  Ids are preserved, which is mandatory rather than cosmetic — txn.payeeId and
     *  game_event.transactionUuid are real foreign keys, and the garden's month markers
     *  depend on event ordering. Verified separately: an explicit-id insert also advances
     *  SQLite's AUTOINCREMENT sequence, so the next new payee cannot collide with a
     *  restored one. */
    suspend fun restore(): Boolean {
        if (!prefs.isConfigured) return false
        val snap = client.snapshot() ?: return false

        db.withTransaction {
            val dao = db.syncDao()
            dao.wipeEvents(); dao.wipeBudgets(); dao.wipeTxns(); dao.wipePayees()

            db.categoryDao().upsertAll(snap.categories)
            db.payeeDao().insertAll(snap.payees)
            db.transactionDao().insertAll(snap.txns)
            db.budgetDao().insertAll(snap.budgets)
            db.gameEventDao().insertAll(snap.events)
        }

        // Do not immediately re-upload what we were just given.
        prefs.lastPushedAt = maxOf(
            snap.categories.maxOfOrNull { it.updatedAt } ?: 0L,
            snap.payees.maxOfOrNull { it.updatedAt } ?: 0L,
            snap.txns.maxOfOrNull { it.updatedAt } ?: 0L,
            snap.budgets.maxOfOrNull { it.updatedAt } ?: 0L,
        )
        prefs.lastPushedEventId = snap.events.maxOfOrNull { it.id } ?: 0L
        prefs.lastSuccessAt = System.currentTimeMillis()
        return true
    }

    suspend fun isLedgerEmpty(): Boolean = db.syncDao().txnCount() == 0
}
```

- [ ] **Step 3: Verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/
git commit -m "feat: sync repository with cursor advance on success and full restore"
```

---

## Task 8: The scheduler and the wiring

Spec §4. Repositories must not know sync exists; they emit a signal and the container decides what it means.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/sync/SyncScheduler.kt`
- Modify: `app/src/main/java/com/expensegarden/app/GardenApp.kt`
- Modify: `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt`
- Modify: `app/src/main/java/com/expensegarden/app/MainActivity.kt`

- [ ] **Step 1: Write the scheduler**

Create `app/src/main/java/com/expensegarden/app/sync/SyncScheduler.kt`:

```kotlin
package com.expensegarden.app.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Coalesces change signals into at most one in-flight push (spec §4).
 *
 *  A CONFLATED channel is the whole design: logging a transaction fires several writes in
 *  quick succession, and each one signals. Conflation means the burst becomes one push
 *  carrying all of it, rather than four pushes racing each other.
 *
 *  Runs on its own scope rather than a viewModelScope, because a push must survive the
 *  screen that triggered it going away. Failures are silent and simply retried on the next
 *  signal — the same self-healing shape as 1D's job. */
class SyncScheduler(private val repo: SyncRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in signals) {
                runCatching { repo.pushOnce() }   // never let a push crash the app
            }
        }
    }

    /** Cheap and non-blocking: safe to call from inside a Room transaction's caller. */
    fun signal() {
        signals.trySend(Unit)
    }
}
```

- [ ] **Step 2: Give the repositories a change callback**

`LedgerRepository` takes a third constructor parameter with a no-op default, so every existing test constructs it unchanged:

```kotlin
class LedgerRepository(
    private val db: AppDatabase,
    private val clock: SyncClock,
    private val onChanged: () -> Unit = {},
) {
```

Call `onChanged()` at the end of each of these — after the transaction commits, never inside it: `save`, `confirm`, `discard`, `setRegret`, `recordGateDodge`.

- [ ] **Step 3: Wire the container**

In `GardenApp.kt`, `AppContainer` gains:

```kotlin
    val syncPrefs: SyncPrefs = SyncPrefs(app)
    val clock: SyncClock = SyncClock({ System.currentTimeMillis() }, syncPrefs)
    val syncClient: SyncClient = SyncClient(syncPrefs)
    val sync: SyncRepository = SyncRepository(db, syncClient, syncPrefs)
    val scheduler: SyncScheduler = SyncScheduler(sync)
```

and the existing repositories are constructed with the callback:

```kotlin
    val ledger: LedgerRepository = LedgerRepository(db, clock) { scheduler.signal() }
    val budgets: BudgetRepository = BudgetRepository(db, clock) { scheduler.signal() }
```

Kotlin initialises properties in declaration order, so `syncPrefs`, `clock`, `syncClient`, `sync` and `scheduler` must all be declared **above** `ledger` and `budgets`. Declaring them below throws `NullPointerException` at app start.

- [ ] **Step 4: Signal once on app open**

In `MainActivity.onCreate`, before `setContent`:

```kotlin
        (application as GardenApp).container.scheduler.signal()
```

- [ ] **Step 5: Verify**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest assembleDebug`

Expected: BUILD SUCCESSFUL, all JVM tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/
git commit -m "feat: debounced sync scheduler signalled by every ledger write"
```

---

## Task 9: Settings — URL, token, and a status line that tells the truth

Spec §5. This is the one place 2A deliberately departs from 1D's silence-is-fine philosophy.

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/SettingsScreen.kt`

- [ ] **Step 1: Add the sync section**

In `SettingsScreen.kt`, add `syncPrefs: SyncPrefs` and `sync: SyncRepository` parameters, and append a card below the existing voice card:

```kotlin
        var url by remember { mutableStateOf(syncPrefs.serverUrl) }
        var token by remember { mutableStateOf(syncPrefs.token) }
        var pending by remember { mutableStateOf<Int?>(null) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { pending = runCatching { sync.pendingCount() }.getOrNull() }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your phone stays the source of truth. This copies the ledger and the " +
                        "garden's event log to your own server so a lost phone is recoverable.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; syncPrefs.serverUrl = it },
                    label = { Text("server url") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; syncPrefs.token = it },
                    label = { Text("token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(syncStatus(syncPrefs.lastSuccessAt, pending), style = MaterialTheme.typography.labelMedium)
            }
        }
```

- [ ] **Step 2: Add the status helper**

At file level in `SettingsScreen.kt`:

```kotlin
/** Spec §5. The AI layer routes every failure to silence because nothing waits on it. A
 *  backup is the opposite: a dead one looks exactly like a healthy one until the day it
 *  matters, so this refuses to let that happen quietly. It never blocks or interrupts —
 *  it just tells the truth. */
private fun syncStatus(lastSuccessAt: Long, pending: Int?): String {
    val waiting = pending?.let { if (it == 0) "" else " · $it waiting" } ?: ""
    if (lastSuccessAt == 0L) return "Never backed up$waiting"
    val ageMs = System.currentTimeMillis() - lastSuccessAt
    val hours = ageMs / 3_600_000
    val stale = if (hours >= 24) "  ⚠ not backed up in ${hours / 24}d" else ""
    return when {
        ageMs < 60_000 -> "Backed up just now$waiting$stale"
        ageMs < 3_600_000 -> "Backed up ${ageMs / 60_000}m ago$waiting$stale"
        else -> "Backed up ${hours}h ago$waiting$stale"
    }
}
```

- [ ] **Step 3: Add the restore button**

Below the status line, inside the same `Column`:

```kotlin
                var confirming by remember { mutableStateOf(false) }
                TextButton(onClick = { confirming = true }, enabled = syncPrefs.isConfigured) {
                    Text("Restore from backup")
                }
                if (confirming) {
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text("Replace everything on this phone?") },
                        text = { Text("This deletes the local ledger and garden, then rebuilds them from the server. It cannot be undone.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirming = false
                                scope.launch { sync.restore() }
                            }) { Text("Restore") }
                        },
                        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
                    )
                }
```

- [ ] **Step 4: Pass the new parameters**

In `MainActivity.kt`, the `settings` composable gains:

```kotlin
                syncPrefs = (context.applicationContext as GardenApp).container.syncPrefs,
                sync = (context.applicationContext as GardenApp).container.sync,
```

- [ ] **Step 5: Verify on device**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew installDebug`

Then open Settings on the device. Expected: a Backup card with two fields and "Never backed up". The screen still scrolls with the keyboard open (the `imePadding().verticalScroll()` from 1D Task 13 covers the new fields too — verify by focusing the token field in landscape).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/
git commit -m "feat: backup settings with an honest status line and guarded restore"
```

---

## Task 10: The Go module, config, and a health endpoint

**Files:**
- Create: `backend/core-api/go.mod`, `backend/core-api/cmd/api/main.go`
- Create: `backend/core-api/internal/config/config.go`
- Create: `backend/core-api/internal/httpapi/router.go`
- Create: `backend/core-api/.gitignore`

- [ ] **Step 1: Initialise the module**

```bash
mkdir -p backend/core-api && cd backend/core-api && go mod init expensegarden/core-api && go get github.com/jackc/pgx/v5@latest && cd "$(git rev-parse --show-toplevel)"
```

Expected: `go.mod` and `go.sum` exist and `go.mod` declares `github.com/jackc/pgx/v5`. `pgx` is the only dependency; routing uses the standard library, which since Go 1.22 supports method-and-path patterns in `ServeMux`.

- [ ] **Step 2: Write the config**

Create `backend/core-api/internal/config/config.go`:

```go
// Package config reads the handful of settings core-api needs, all from the environment.
// Nothing is defaulted that would be dangerous if wrong: a missing token is a startup
// failure rather than an open server.
package config

import (
	"errors"
	"os"
)

type Config struct {
	DatabaseURL string
	Token       string
	Addr        string
}

var ErrMissing = errors.New("config: DATABASE_URL and SYNC_TOKEN are both required")

func Load() (Config, error) {
	c := Config{
		DatabaseURL: os.Getenv("DATABASE_URL"),
		Token:       os.Getenv("SYNC_TOKEN"),
		Addr:        os.Getenv("ADDR"),
	}
	if c.Addr == "" {
		c.Addr = ":8080"
	}
	if c.DatabaseURL == "" || c.Token == "" {
		return c, ErrMissing
	}
	return c, nil
}
```

- [ ] **Step 3: Write the router with health**

Create `backend/core-api/internal/httpapi/router.go`:

```go
// Package httpapi is the HTTP surface: three routes, one of which needs no auth.
package httpapi

import (
	"encoding/json"
	"net/http"
)

type Server struct {
	Token string
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()
	// Unauthenticated on purpose: an uptime check should not need a credential, and it
	// reveals nothing but liveness.
	mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
	return mux
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
```

- [ ] **Step 4: Write main**

Create `backend/core-api/cmd/api/main.go`:

```go
package main

import (
	"log"
	"net/http"

	"expensegarden/core-api/internal/config"
	"expensegarden/core-api/internal/httpapi"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("startup: %v", err)
	}
	srv := &httpapi.Server{Token: cfg.Token}
	log.Printf("core-api listening on %s", cfg.Addr)
	if err := http.ListenAndServe(cfg.Addr, srv.Routes()); err != nil {
		log.Fatalf("listen: %v", err)
	}
}
```

- [ ] **Step 5: Ignore build output**

Create `backend/core-api/.gitignore`:

```
/api
/core-api
```

- [ ] **Step 6: Run it and hit health**

```bash
cd backend/core-api && DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" SYNC_TOKEN=dev-token-not-secret go run ./cmd/api &
sleep 2 && curl -s -w "\n%{http_code}\n" http://localhost:8080/v1/health
```

Expected: `{"ok":true}` and `200`. Stop the server with `kill %1` when done.

- [ ] **Step 7: Commit**

```bash
git add backend/
git commit -m "feat: core-api skeleton with config and a health endpoint"
```

---

## Task 11: The Postgres schema and the store

Spec §2.6. Numbered SQL applied by a small embedded runner — no migration library, because "apply the files in order and record which ran" is twenty lines and a dependency is not.

**Files:**
- Create: `backend/core-api/internal/migrations/migrations.go`
- Create: `backend/core-api/internal/migrations/001_init.sql`
- Create: `backend/core-api/internal/store/store.go`
- Test: `backend/core-api/internal/store/store_test.go`

- [ ] **Step 1: Write the schema**

Create `backend/core-api/internal/migrations/001_init.sql`:

```sql
-- Phase 2A. Mirrors Room with real foreign keys (parent spec §11).
-- Enums are TEXT with CHECK constraints rather than Postgres enum types: a CHECK is trivially
-- altered when a variant is added, an enum type is not.

CREATE TABLE IF NOT EXISTS category (
    id           BIGINT PRIMARY KEY,
    name         TEXT   NOT NULL,
    parent_id    BIGINT REFERENCES category(id),
    is_necessity BOOLEAN NOT NULL,
    updated_at   BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS payee (
    id                  BIGINT PRIMARY KEY,
    name                TEXT   NOT NULL,
    vpa                 TEXT   UNIQUE,
    default_category_id BIGINT REFERENCES category(id) ON DELETE SET NULL,
    updated_at          BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS txn (
    uuid                TEXT   PRIMARY KEY,
    amount_paise        BIGINT NOT NULL,
    payee_id            BIGINT NOT NULL REFERENCES payee(id),
    category_id         BIGINT NOT NULL REFERENCES category(id),
    source              TEXT   NOT NULL CHECK (source IN ('QR_GATE','MANUAL','IMPORT')),
    status              TEXT   NOT NULL CHECK (status IN ('PENDING_CONFIRM','LOGGED','DISCARDED')),
    regret              TEXT   NOT NULL CHECK (regret IN ('UNRATED','WORTH_IT','REGRET')),
    breached_at_logging BOOLEAN NOT NULL,
    note                TEXT,
    occurred_at         BIGINT NOT NULL,
    created_at          BIGINT NOT NULL,
    updated_at          BIGINT NOT NULL
);

-- NULLS NOT DISTINCT is load-bearing (spec §2.1): category_id is NULL for the overall budget,
-- and without this clause Postgres would treat every overall budget for a month as a distinct
-- row, so ON CONFLICT would never fire and duplicates would accumulate silently.
CREATE TABLE IF NOT EXISTS budget (
    category_id  BIGINT REFERENCES category(id) ON DELETE CASCADE,
    month        TEXT   NOT NULL,
    amount_paise BIGINT NOT NULL,
    updated_at   BIGINT NOT NULL,
    UNIQUE NULLS NOT DISTINCT (category_id, month)
);

-- No CHECK on `type`. The server is a replica, not the authority on the game's vocabulary:
-- constraining it here would mean every new event type in a future phase silently fails to
-- sync until Postgres is migrated first.
CREATE TABLE IF NOT EXISTS game_event (
    id               BIGINT PRIMARY KEY,
    type             TEXT   NOT NULL,
    payload_json     TEXT   NOT NULL,
    transaction_uuid TEXT   REFERENCES txn(uuid) ON DELETE SET NULL,
    created_at       BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_txn_occurred_at ON txn(occurred_at);
CREATE INDEX IF NOT EXISTS idx_game_event_txn ON game_event(transaction_uuid);
```

- [ ] **Step 2: Write the migration runner**

Create `backend/core-api/internal/migrations/migrations.go`:

```go
// Package migrations applies numbered SQL files in order, exactly once each.
package migrations

import (
	"context"
	"embed"
	"fmt"
	"sort"

	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed *.sql
var files embed.FS

// Apply runs every unapplied migration inside its own transaction, recording it in
// schema_migrations. Re-running is a no-op, which is what makes it safe on every boot.
func Apply(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY)`)
	if err != nil {
		return fmt.Errorf("migrations: create ledger: %w", err)
	}

	entries, err := files.ReadDir(".")
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		names = append(names, e.Name())
	}
	sort.Strings(names)

	for _, name := range names {
		var applied bool
		if err := pool.QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE name = $1)`, name,
		).Scan(&applied); err != nil {
			return err
		}
		if applied {
			continue
		}
		body, err := files.ReadFile(name)
		if err != nil {
			return err
		}
		tx, err := pool.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, string(body)); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("migrations: %s: %w", name, err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO schema_migrations (name) VALUES ($1)`, name); err != nil {
			_ = tx.Rollback(ctx)
			return err
		}
		if err := tx.Commit(ctx); err != nil {
			return err
		}
	}
	return nil
}
```

- [ ] **Step 3: Write the store**

Create `backend/core-api/internal/store/store.go`:

```go
// Package store is every SQL statement core-api runs.
//
// The last-write-wins rule lives in the ON CONFLICT clauses rather than in Go. That is
// deliberate: doing it in Go would mean reading each row, deciding, then writing — three
// round trips and a race between them. In SQL it is one atomic statement, and the rule is
// exactly the WHERE clause you can read here.
package store

import (
	"context"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Store struct{ Pool *pgxpool.Pool }

type Category struct {
	ID           int64
	Name         string
	ParentID     *int64
	IsNecessity  bool
	UpdatedAt    int64
}

type Payee struct {
	ID                int64
	Name              string
	VPA               *string
	DefaultCategoryID *int64
	UpdatedAt         int64
}

type Txn struct {
	UUID              string
	AmountPaise       int64
	PayeeID           int64
	CategoryID        int64
	Source            string
	Status            string
	Regret            string
	BreachedAtLogging bool
	Note              *string
	OccurredAt        int64
	CreatedAt         int64
	UpdatedAt         int64
}

type Budget struct {
	CategoryID  *int64
	Month       string
	AmountPaise int64
	UpdatedAt   int64
}

type Event struct {
	ID              int64
	Type            string
	PayloadJSON     string
	TransactionUUID *string
	CreatedAt       int64
}

type Tombstone struct {
	TableName string
	RowKey    string
	DeletedAt int64
}

type Batch struct {
	Categories []Category
	Payees     []Payee
	Txns       []Txn
	Budgets    []Budget
	Tombstones []Tombstone
	Events     []Event
}

// ApplyBatch writes an entire push in one transaction, in foreign-key order. All or nothing:
// a partial apply would leave the phone's cursor advanced past rows the server never stored.
func (s *Store) ApplyBatch(ctx context.Context, b Batch) error {
	return pgx.BeginFunc(ctx, s.Pool, func(tx pgx.Tx) error {
		for _, c := range b.Categories {
			if _, err := tx.Exec(ctx, `
				INSERT INTO category (id, name, parent_id, is_necessity, updated_at)
				VALUES ($1,$2,$3,$4,$5)
				ON CONFLICT (id) DO UPDATE SET
					name = EXCLUDED.name, parent_id = EXCLUDED.parent_id,
					is_necessity = EXCLUDED.is_necessity, updated_at = EXCLUDED.updated_at
				WHERE EXCLUDED.updated_at >= category.updated_at`,
				c.ID, c.Name, c.ParentID, c.IsNecessity, c.UpdatedAt); err != nil {
				return err
			}
		}
		for _, p := range b.Payees {
			if _, err := tx.Exec(ctx, `
				INSERT INTO payee (id, name, vpa, default_category_id, updated_at)
				VALUES ($1,$2,$3,$4,$5)
				ON CONFLICT (id) DO UPDATE SET
					name = EXCLUDED.name, vpa = EXCLUDED.vpa,
					default_category_id = EXCLUDED.default_category_id,
					updated_at = EXCLUDED.updated_at
				WHERE EXCLUDED.updated_at >= payee.updated_at`,
				p.ID, p.Name, p.VPA, p.DefaultCategoryID, p.UpdatedAt); err != nil {
				return err
			}
		}
		for _, t := range b.Txns {
			if _, err := tx.Exec(ctx, `
				INSERT INTO txn (uuid, amount_paise, payee_id, category_id, source, status,
					regret, breached_at_logging, note, occurred_at, created_at, updated_at)
				VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
				ON CONFLICT (uuid) DO UPDATE SET
					amount_paise = EXCLUDED.amount_paise, payee_id = EXCLUDED.payee_id,
					category_id = EXCLUDED.category_id, source = EXCLUDED.source,
					status = EXCLUDED.status, regret = EXCLUDED.regret,
					breached_at_logging = EXCLUDED.breached_at_logging, note = EXCLUDED.note,
					occurred_at = EXCLUDED.occurred_at, created_at = EXCLUDED.created_at,
					updated_at = EXCLUDED.updated_at
				WHERE EXCLUDED.updated_at >= txn.updated_at`,
				t.UUID, t.AmountPaise, t.PayeeID, t.CategoryID, t.Source, t.Status, t.Regret,
				t.BreachedAtLogging, t.Note, t.OccurredAt, t.CreatedAt, t.UpdatedAt); err != nil {
				return err
			}
		}
		for _, bg := range b.Budgets {
			if _, err := tx.Exec(ctx, `
				INSERT INTO budget (category_id, month, amount_paise, updated_at)
				VALUES ($1,$2,$3,$4)
				ON CONFLICT (category_id, month) DO UPDATE SET
					amount_paise = EXCLUDED.amount_paise, updated_at = EXCLUDED.updated_at
				WHERE EXCLUDED.updated_at >= budget.updated_at`,
				bg.CategoryID, bg.Month, bg.AmountPaise, bg.UpdatedAt); err != nil {
				return err
			}
		}
		for _, e := range b.Events {
			// Append-only: re-sending an event is free, which is what makes blind retry safe.
			if _, err := tx.Exec(ctx, `
				INSERT INTO game_event (id, type, payload_json, transaction_uuid, created_at)
				VALUES ($1,$2,$3,$4,$5)
				ON CONFLICT (id) DO NOTHING`,
				e.ID, e.Type, e.PayloadJSON, e.TransactionUUID, e.CreatedAt); err != nil {
				return err
			}
		}
		for _, tomb := range b.Tombstones {
			if tomb.TableName != "budget" {
				continue // budget is the only synced table with deletes (spec §2.1)
			}
			catID, month, ok := ParseBudgetRowKey(tomb.RowKey)
			if !ok {
				continue
			}
			// Only if the row is not newer than the tombstone — otherwise a stale delete
			// would erase a budget the user has since re-set.
			if _, err := tx.Exec(ctx, `
				DELETE FROM budget
				WHERE category_id IS NOT DISTINCT FROM $1 AND month = $2 AND updated_at <= $3`,
				catID, month, tomb.DeletedAt); err != nil {
				return err
			}
		}
		return nil
	})
}
```

- [ ] **Step 4: Add the row-key parser**

Append to `store.go`:

```go
// ParseBudgetRowKey decodes "<categoryId or *>|<month>" (spec §2.1). The "*" sentinel means
// the overall budget, whose category_id is NULL.
func ParseBudgetRowKey(key string) (*int64, string, bool) {
	i := strings.IndexByte(key, '|')
	if i < 0 {
		return nil, "", false
	}
	head, month := key[:i], key[i+1:]
	if month == "" {
		return nil, "", false
	}
	if head == "*" {
		return nil, month, true
	}
	id, err := strconv.ParseInt(head, 10, 64)
	if err != nil {
		return nil, "", false
	}
	return &id, month, true
}
```

Add `"strconv"` and `"strings"` to the imports.

- [ ] **Step 5: Write the failing tests**

Create `backend/core-api/internal/store/store_test.go`:

```go
package store

import (
	"context"
	"os"
	"testing"

	"expensegarden/core-api/internal/migrations"
	"github.com/jackc/pgx/v5/pgxpool"
)

// The last-write-wins rule lives in SQL, so it is tested against real Postgres. A mock would
// only prove that the mock agrees with itself.
func newTestStore(t *testing.T) *Store {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set; see Task 0")
	}
	pool, err := pgxpool.New(context.Background(), dsn)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	t.Cleanup(pool.Close)
	if err := migrations.Apply(context.Background(), pool); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	// Each test starts clean. Order matters: children before parents.
	for _, tbl := range []string{"game_event", "budget", "txn", "payee", "category"} {
		if _, err := pool.Exec(context.Background(), "DELETE FROM "+tbl); err != nil {
			t.Fatalf("clean %s: %v", tbl, err)
		}
	}
	return &Store{Pool: pool}
}

func seedCategory(t *testing.T, s *Store) {
	t.Helper()
	err := s.ApplyBatch(context.Background(), Batch{
		Categories: []Category{{ID: 3, Name: "Transport", IsNecessity: true, UpdatedAt: 1}},
	})
	if err != nil {
		t.Fatalf("seed: %v", err)
	}
}

func TestParseBudgetRowKey(t *testing.T) {
	cases := []struct {
		key      string
		wantNil  bool
		wantID   int64
		wantMon  string
		wantOK   bool
	}{
		{"3|2026-09", false, 3, "2026-09", true},
		{"*|2026-09", true, 0, "2026-09", true},
		{"garbage", false, 0, "", false},
		{"x|2026-09", false, 0, "", false},
		{"3|", false, 0, "", false},
	}
	for _, c := range cases {
		id, month, ok := ParseBudgetRowKey(c.key)
		if ok != c.wantOK {
			t.Fatalf("%q: ok = %v, want %v", c.key, ok, c.wantOK)
		}
		if !ok {
			continue
		}
		if month != c.wantMon {
			t.Fatalf("%q: month = %q, want %q", c.key, month, c.wantMon)
		}
		if c.wantNil && id != nil {
			t.Fatalf("%q: expected NULL category", c.key)
		}
		if !c.wantNil && (id == nil || *id != c.wantID) {
			t.Fatalf("%q: id mismatch", c.key)
		}
	}
}

func TestNewerWriteWins(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	seedCategory(t, s)

	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	must(s.ApplyBatch(ctx, Batch{Budgets: []Budget{{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 100, UpdatedAt: 10}}}))
	must(s.ApplyBatch(ctx, Batch{Budgets: []Budget{{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 200, UpdatedAt: 20}}}))

	var amount int64
	must(s.Pool.QueryRow(ctx, `SELECT amount_paise FROM budget WHERE category_id = 3`).Scan(&amount))
	if amount != 200 {
		t.Fatalf("amount = %d, want 200", amount)
	}
}

func TestOlderWriteIsIgnored(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	seedCategory(t, s)

	_ = s.ApplyBatch(ctx, Batch{Budgets: []Budget{{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 200, UpdatedAt: 20}}})
	_ = s.ApplyBatch(ctx, Batch{Budgets: []Budget{{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 100, UpdatedAt: 10}}})

	var amount int64
	if err := s.Pool.QueryRow(ctx, `SELECT amount_paise FROM budget WHERE category_id = 3`).Scan(&amount); err != nil {
		t.Fatal(err)
	}
	if amount != 200 {
		t.Fatalf("a stale write overwrote a newer one: amount = %d, want 200", amount)
	}
}

// The reason spec §2.1 insists on NULLS NOT DISTINCT: without it, every overall budget for a
// month is a distinct row, ON CONFLICT never fires, and duplicates pile up unnoticed.
func TestOverallBudgetIsUniquePerMonth(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	seedCategory(t, s)

	_ = s.ApplyBatch(ctx, Batch{Budgets: []Budget{{CategoryID: nil, Month: "2026-09", AmountPaise: 500, UpdatedAt: 10}}})
	_ = s.ApplyBatch(ctx, Batch{Budgets: []Budget{{CategoryID: nil, Month: "2026-09", AmountPaise: 900, UpdatedAt: 20}}})

	var count, amount int64
	if err := s.Pool.QueryRow(ctx, `SELECT COUNT(*), MAX(amount_paise) FROM budget WHERE category_id IS NULL`).Scan(&count, &amount); err != nil {
		t.Fatal(err)
	}
	if count != 1 || amount != 900 {
		t.Fatalf("count = %d amount = %d, want 1 and 900", count, amount)
	}
}

func TestStaleTombstoneDoesNotDeleteANewerBudget(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	seedCategory(t, s)

	_ = s.ApplyBatch(ctx, Batch{Budgets: []Budget{{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 700, UpdatedAt: 50}}})
	// A delete that happened BEFORE the row's latest write must not erase it.
	_ = s.ApplyBatch(ctx, Batch{Tombstones: []Tombstone{{TableName: "budget", RowKey: "3|2026-09", DeletedAt: 40}}})

	var count int64
	if err := s.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM budget WHERE category_id = 3`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("stale tombstone deleted a newer budget")
	}
}

func TestFreshTombstoneDeletes(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	seedCategory(t, s)

	_ = s.ApplyBatch(ctx, Batch{Budgets: []Budget{{CategoryID: nil, Month: "2026-09", AmountPaise: 700, UpdatedAt: 50}}})
	_ = s.ApplyBatch(ctx, Batch{Tombstones: []Tombstone{{TableName: "budget", RowKey: "*|2026-09", DeletedAt: 60}}})

	var count int64
	if err := s.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM budget WHERE category_id IS NULL`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatalf("tombstone did not delete the overall budget")
	}
}

func TestResendingAnEventIsANoOp(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()

	ev := Event{ID: 1, Type: "gate.dodged", PayloadJSON: "{}", CreatedAt: 5}
	_ = s.ApplyBatch(ctx, Batch{Events: []Event{ev}})
	_ = s.ApplyBatch(ctx, Batch{Events: []Event{ev}})

	var count int64
	if err := s.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM game_event`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("count = %d, want 1 — retry must be idempotent", count)
	}
}

func ptr[T any](v T) *T { return &v }
```

- [ ] **Step 6: Run the tests**

```bash
cd backend/core-api && TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" go test ./... && cd "$(git rev-parse --show-toplevel)"
```

Expected: `ok expensegarden/core-api/internal/store` with 7 tests passing.

If `TestOverallBudgetIsUniquePerMonth` fails with two rows, the Postgres version is below 15 and `NULLS NOT DISTINCT` was silently ignored — stop and re-check Task 0 Step 3.

- [ ] **Step 7: Commit**

```bash
git add backend/
git commit -m "feat: postgres schema, embedded migration runner and the sync store"
```

---

## Task 12: Auth and the push endpoint

Spec §3.1.

**Files:**
- Create: `backend/core-api/internal/httpapi/auth.go`, `backend/core-api/internal/httpapi/push.go`
- Modify: `backend/core-api/internal/httpapi/router.go`, `backend/core-api/cmd/api/main.go`

- [ ] **Step 1: Write the auth middleware**

Create `backend/core-api/internal/httpapi/auth.go`:

```go
package httpapi

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// requireToken guards every route but health.
//
// subtle.ConstantTimeCompare rather than ==: string comparison short-circuits on the first
// differing byte, which leaks the token's prefix to anyone who can time responses. One user
// makes that unlikely to matter and free to prevent.
func (s *Server) requireToken(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		token := strings.TrimPrefix(header, "Bearer ")
		if token == header || subtle.ConstantTimeCompare([]byte(token), []byte(s.Token)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		next(w, r)
	}
}
```

- [ ] **Step 2: Write the push handler**

Create `backend/core-api/internal/httpapi/push.go`:

```go
package httpapi

import (
	"encoding/json"
	"net/http"

	"expensegarden/core-api/internal/store"
)

type pushRequest struct {
	Categories []store.Category  `json:"categories"`
	Payees     []store.Payee     `json:"payees"`
	Txns       []store.Txn       `json:"txns"`
	Budgets    []store.Budget    `json:"budgets"`
	Tombstones []store.Tombstone `json:"tombstones"`
	Events     []store.Event     `json:"events"`
}

func (s *Server) handlePush(w http.ResponseWriter, r *http.Request) {
	var req pushRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxPushBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "malformed body"})
		return
	}
	batch := store.Batch{
		Categories: req.Categories, Payees: req.Payees, Txns: req.Txns,
		Budgets: req.Budgets, Tombstones: req.Tombstones, Events: req.Events,
	}
	if err := s.Store.ApplyBatch(r.Context(), batch); err != nil {
		// The phone treats any non-2xx as "cursors do not advance", so a failure here is
		// simply retried with the same rows. Nothing is lost by refusing.
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "apply failed"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]int{"accepted": len(batch.Txns) + len(batch.Events) +
		len(batch.Budgets) + len(batch.Payees) + len(batch.Categories)})
}

const maxPushBytes = 32 << 20 // 32MB: the first sync carries the entire history
```

- [ ] **Step 3: Add the JSON tags to the store types**

The handler decodes straight into `store` types, so they need tags. In `store.go`, tag every field, e.g.:

```go
type Budget struct {
	CategoryID  *int64 `json:"categoryId"`
	Month       string `json:"month"`
	AmountPaise int64  `json:"amountPaise"`
	UpdatedAt   int64  `json:"updatedAt"`
}

type Tombstone struct {
	TableName string `json:"tableName"`
	RowKey    string `json:"rowKey"`
	DeletedAt int64  `json:"deletedAt"`
}
```

Do the same for `Category` (`id`, `name`, `parentId`, `isNecessity`, `updatedAt`), `Payee` (`id`, `name`, `vpa`, `defaultCategoryId`, `updatedAt`), `Txn` (`uuid`, `amountPaise`, `payeeId`, `categoryId`, `source`, `status`, `regret`, `breachedAtLogging`, `note`, `occurredAt`, `createdAt`, `updatedAt`) and `Event` (`id`, `type`, `payloadJson`, `transactionUuid`, `createdAt`).

These names must match `SyncClient`'s writers in Task 6 exactly — a mismatch decodes as a zero value with no error, which is the quietest possible bug.

- [ ] **Step 4: Wire the route and the pool**

In `router.go`, add the store to `Server` and register the route:

```go
type Server struct {
	Token string
	Store *store.Store
}
```

```go
	mux.HandleFunc("POST /v1/sync/push", s.requireToken(s.handlePush))
```

In `main.go`, open the pool and migrate before listening:

```go
	pool, err := pgxpool.New(context.Background(), cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	defer pool.Close()
	if err := migrations.Apply(context.Background(), pool); err != nil {
		log.Fatalf("migrate: %v", err)
	}
	srv := &httpapi.Server{Token: cfg.Token, Store: &store.Store{Pool: pool}}
```

- [ ] **Step 5: Verify auth and push by hand**

```bash
cd backend/core-api && DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" SYNC_TOKEN=dev-token-not-secret go run ./cmd/api &
sleep 2
echo "no token:"; curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/v1/sync/push -d '{}'
echo "wrong token:"; curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/v1/sync/push -H "Authorization: Bearer nope" -d '{}'
echo "right token:"; curl -s -w "\n%{http_code}\n" -X POST http://localhost:8080/v1/sync/push -H "Authorization: Bearer dev-token-not-secret" -H "Content-Type: application/json" -d '{"categories":[{"id":1,"name":"Food","parentId":null,"isNecessity":false,"updatedAt":1}]}'
```

Expected: `401`, `401`, then `{"accepted":1}` and `200`.

- [ ] **Step 6: Commit**

```bash
git add backend/
git commit -m "feat: bearer auth and the sync push endpoint"
```

---

## Task 13: The snapshot endpoint

Spec §3.3.

**Files:**
- Create: `backend/core-api/internal/httpapi/snapshot.go`
- Modify: `backend/core-api/internal/store/store.go`, `backend/core-api/internal/httpapi/router.go`

- [ ] **Step 1: Add the reads**

Append to `store.go`:

```go
// Snapshot is everything, for a restore. No pagination: a single user's lifetime ledger is
// a few thousand rows, and streaming it would buy complexity nobody needs.
func (s *Store) Snapshot(ctx context.Context) (Batch, error) {
	var b Batch

	rows, err := s.Pool.Query(ctx, `SELECT id, name, parent_id, is_necessity, updated_at FROM category ORDER BY id`)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var c Category
		if err := rows.Scan(&c.ID, &c.Name, &c.ParentID, &c.IsNecessity, &c.UpdatedAt); err != nil {
			return b, err
		}
		b.Categories = append(b.Categories, c)
	}
	rows.Close()

	rows, err = s.Pool.Query(ctx, `SELECT id, name, vpa, default_category_id, updated_at FROM payee ORDER BY id`)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var p Payee
		if err := rows.Scan(&p.ID, &p.Name, &p.VPA, &p.DefaultCategoryID, &p.UpdatedAt); err != nil {
			return b, err
		}
		b.Payees = append(b.Payees, p)
	}
	rows.Close()

	rows, err = s.Pool.Query(ctx, `SELECT uuid, amount_paise, payee_id, category_id, source, status,
		regret, breached_at_logging, note, occurred_at, created_at, updated_at FROM txn ORDER BY created_at`)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var t Txn
		if err := rows.Scan(&t.UUID, &t.AmountPaise, &t.PayeeID, &t.CategoryID, &t.Source, &t.Status,
			&t.Regret, &t.BreachedAtLogging, &t.Note, &t.OccurredAt, &t.CreatedAt, &t.UpdatedAt); err != nil {
			return b, err
		}
		b.Txns = append(b.Txns, t)
	}
	rows.Close()

	rows, err = s.Pool.Query(ctx, `SELECT category_id, month, amount_paise, updated_at FROM budget ORDER BY month`)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var bg Budget
		if err := rows.Scan(&bg.CategoryID, &bg.Month, &bg.AmountPaise, &bg.UpdatedAt); err != nil {
			return b, err
		}
		b.Budgets = append(b.Budgets, bg)
	}
	rows.Close()

	// Ordered by id, which is the garden's replay order (parent spec §9.2).
	rows, err = s.Pool.Query(ctx, `SELECT id, type, payload_json, transaction_uuid, created_at FROM game_event ORDER BY id`)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var e Event
		if err := rows.Scan(&e.ID, &e.Type, &e.PayloadJSON, &e.TransactionUUID, &e.CreatedAt); err != nil {
			return b, err
		}
		b.Events = append(b.Events, e)
	}
	rows.Close()

	return b, rows.Err()
}
```

- [ ] **Step 2: Write the handler**

Create `backend/core-api/internal/httpapi/snapshot.go`:

```go
package httpapi

import "net/http"

func (s *Server) handleSnapshot(w http.ResponseWriter, r *http.Request) {
	b, err := s.Store.Snapshot(r.Context())
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "snapshot failed"})
		return
	}
	// Empty slices, not nulls: the client's readers iterate arrays and a JSON null would be a
	// parse failure that reads on the phone as "restore did nothing".
	writeJSON(w, http.StatusOK, map[string]any{
		"categories": nonNil(b.Categories),
		"payees":     nonNil(b.Payees),
		"txns":       nonNil(b.Txns),
		"budgets":    nonNil(b.Budgets),
		"events":     nonNil(b.Events),
	})
}

func nonNil[T any](v []T) []T {
	if v == nil {
		return []T{}
	}
	return v
}
```

- [ ] **Step 3: Register the route**

In `router.go`:

```go
	mux.HandleFunc("GET /v1/sync/snapshot", s.requireToken(s.handleSnapshot))
```

- [ ] **Step 4: Verify the round trip by hand**

```bash
curl -s http://localhost:8080/v1/sync/snapshot -H "Authorization: Bearer dev-token-not-secret" | python3 -m json.tool | head -20
```

Expected: an object with all five keys, `categories` containing the row pushed in Task 12, and every other key an empty array rather than `null`.

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "feat: snapshot endpoint for restore"
```

---

## Task 14: The acceptance gate — wipe and restore

Spec §1, §6. This is the phase. Nothing before it counts.

**Files:** none

- [ ] **Step 1: Full test suites**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest && ./gradlew connectedDebugAndroidTest && ./gradlew installDebug
```

Expected: all JVM green, all instrumented green, then a fresh install. Remember the guardrail: `installDebug` must run **last and alone**, because the connected-test task uninstalls both APKs.

```bash
cd backend/core-api && TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" go test ./... && cd "$(git rev-parse --show-toplevel)"
```

Expected: all Go packages ok.

- [ ] **Step 2: Start the server**

```bash
cd backend/core-api && DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" SYNC_TOKEN=dev-token-not-secret ADDR=:8080 go run ./cmd/api
```

Leave it running. The emulator reaches the host at `10.0.2.2`, not `localhost` — `localhost` inside the emulator is the emulator.

- [ ] **Step 3: Configure the phone**

On the device: Settings → Backup. Server url `http://10.0.2.2:8080`, token `dev-token-not-secret`.

Expected: the status line reads "Never backed up".

> `http`, not `https`, and that is fine only because this is loopback to the developer's own
> machine. 2B introduces TLS; do not carry an `http` URL to any real host.

- [ ] **Step 4: Populate**

Log at least: one manual transaction, one budget, one regret tag, and one gate dodge (set a small budget, then start a payment that breaches it and back out). The dodge matters — it is a `game_event` with no transaction behind it, so it is the row that proves the event log is genuinely syncing rather than being re-derived from transactions.

- [ ] **Step 5: Confirm the server received it**

```bash
psql -d expense_garden -c "SELECT (SELECT COUNT(*) FROM txn) AS txns, (SELECT COUNT(*) FROM budget) AS budgets, (SELECT COUNT(*) FROM game_event) AS events;"
```

Expected: non-zero in all three columns. If events is 0 but txns is not, the scheduler is not being signalled after event writes — check Task 8 Step 2.

- [ ] **Step 6: Record what the garden looks like now**

Screenshot the home screen, and record the numbers:

```bash
psql -d expense_garden -tAc "SELECT COUNT(*) FROM txn; SELECT COUNT(*) FROM game_event; SELECT COALESCE(SUM(amount_paise),0) FROM txn WHERE status='LOGGED';"
```

- [ ] **Step 7: Wipe the phone**

```bash
~/Library/Android/sdk/platform-tools/adb shell pm clear com.expensegarden.app
```

Expected: `Success`. This destroys Room, both prefs files, and the sync cursors — a genuinely fresh install.

- [ ] **Step 8: Restore**

Open the app. Expected: an empty garden, ₹0.00. Go to Settings → Backup, re-enter the url and token, tap **Restore from backup**, confirm.

- [ ] **Step 9: Verify the restore**

Expected, and all of these must hold:

1. Home shows the same total as Step 6.
2. The garden renders the same plants, same house level, same month markers as the screenshot.
3. The butterfly from the gate dodge is back — proving `game_event` restored, not just transactions.
4. `SELECT COUNT(*)` on the device matches the server:

```bash
~/Library/Android/sdk/platform-tools/adb shell am force-stop com.expensegarden.app
~/Library/Android/sdk/platform-tools/adb exec-out run-as com.expensegarden.app cat databases/garden.db > /tmp/restored.db
sqlite3 /tmp/restored.db "SELECT COUNT(*) FROM txn; SELECT COUNT(*) FROM game_event; SELECT COALESCE(SUM(amountPaise),0) FROM txn WHERE status='LOGGED';"
```

Expected: identical to Step 6's three numbers.

5. The digest table is empty and the app does not narrate the restored history — spec §3.3's designed interaction with 1D's first-run floor.

- [ ] **Step 10: Verify the restored phone does not re-upload everything**

Re-open the app and watch the server log. Expected: no `accepted` line with a large count. The cursors were set from the snapshot, so there is nothing dirty.

```bash
psql -d expense_garden -tAc "SELECT COUNT(*) FROM game_event;"
```

Expected: unchanged from Step 6. A jump would mean the restore failed to advance the cursors and the phone is re-sending rows the server already has.

- [ ] **Step 11: Commit**

```bash
git commit --allow-empty -m "test: phase 2a acceptance — wipe and restore round-trips"
```

---

## What 2A deliberately leaves undone

- **Hosting.** Everything above runs on `localhost`. 2B picks a free host, adds TLS and a real domain, and re-verifies the free tier on the day rather than trusting a note.
- **Backups of the backup.** The Postgres holding the replica is not itself backed up until 2C. Until then this protects against losing the phone, not against losing the Mac.
- **Incremental pull.** Restore downloads everything. Fine for one user's lifetime ledger; revisit only if a second device ever appears.
- **`quip` and `digest`.** Regenerated on the restored phone at no cost.
