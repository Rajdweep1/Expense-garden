package com.expensegarden.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2_preservesRows_andEnforcesFk() {
        // v1 database with hand-inserted rows (helper creates schema only — no SeedCallback).
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO category (id, name, parentId, isNecessity) VALUES (1, 'Food', NULL, 0)")
            execSQL("INSERT INTO budget (categoryId, month, amountPaise) VALUES (NULL, '2026-07', 1000000)")
            execSQL("INSERT INTO budget (categoryId, month, amountPaise) VALUES (1, '2026-07', 50000)")
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test", 2, true, MIGRATION_1_2)

        // Rows survived — overall (NULL FK is vacuously valid) and the category-scoped one.
        db.query("SELECT categoryId, month, amountPaise FROM budget ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertTrue(c.isNull(0))
            assertEquals("2026-07", c.getString(1))
            assertEquals(1_000_000L, c.getLong(2))
            c.moveToNext()
            assertEquals(1L, c.getLong(0))
            assertEquals(50_000L, c.getLong(2))
        }

        // FK now enforced: unknown category must be rejected.
        db.execSQL("PRAGMA foreign_keys = ON")
        var rejected = false
        try {
            db.execSQL("INSERT INTO budget (categoryId, month, amountPaise) VALUES (999, '2026-08', 1)")
        } catch (e: SQLiteConstraintException) {
            rejected = true
        }
        assertTrue("insert with bogus categoryId must violate the new FK", rejected)
    }

    @Test
    fun migrate2To3_addsToneAndDigest_andEnforcesScopeUniqueness() {
        helper.createDatabase("migration-test-3", 2).apply {
            execSQL("INSERT INTO quip (severity, origin, text, usedAt) VALUES ('BREACH', 'STATIC', 'old line', NULL)")
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test-3", 3, true, MIGRATION_2_3)

        // Existing quips adopt the voice they were written in.
        db.query("SELECT tone, text FROM quip").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("SHARP", c.getString(0))
            assertEquals("old line", c.getString(1))
        }

        // The digest table exists and its uniqueness guard actually bites — this is what
        // stops a turbulent day producing a stream of cards instead of one.
        db.execSQL(
            "INSERT INTO digest (kind, scopeKey, text, reasonJson, snapshotJson, lastEventId, createdAt, dismissedAt) " +
                "VALUES ('DAILY', '2026-09-05', 'first', '{}', '{}', 7, 1000, NULL)"
        )
        var rejected = false
        try {
            db.execSQL(
                "INSERT INTO digest (kind, scopeKey, text, reasonJson, snapshotJson, lastEventId, createdAt, dismissedAt) " +
                    "VALUES ('DAILY', '2026-09-05', 'second', '{}', '{}', 9, 2000, NULL)"
            )
        } catch (e: SQLiteConstraintException) {
            rejected = true
        }
        assertTrue("a second DAILY digest for the same day must be rejected", rejected)

        // A different scope is fine — one card per day, not one card ever.
        db.execSQL(
            "INSERT INTO digest (kind, scopeKey, text, reasonJson, snapshotJson, lastEventId, createdAt, dismissedAt) " +
                "VALUES ('MONTHLY', '2026-09', 'month', '{}', '{}', 9, 2000, NULL)"
        )
        db.query("SELECT COUNT(*) FROM digest").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }
    }
}
