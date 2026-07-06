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
}
