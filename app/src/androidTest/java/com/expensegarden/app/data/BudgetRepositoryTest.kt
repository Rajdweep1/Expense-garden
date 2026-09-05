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
        assertEquals("*|2026-09", tomb.rowKey)
    }

    @Test fun clearing_a_category_budget_uses_its_id_in_the_row_key() = runBlocking {
        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = 500)
        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = 0)

        assertEquals("3|2026-09", db.syncDao().tombstones().single().rowKey)
    }

    @Test fun re_setting_a_cleared_budget_removes_its_tombstone() = runBlocking {
        // Otherwise the unpushed tombstone would delete, on the server, the row we just wrote.
        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = 500)
        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = null)
        assertEquals(1, db.syncDao().tombstones().size)

        repo.setBudget(categoryId = 3, month = "2026-09", amountPaise = 900)

        assertTrue(db.syncDao().tombstones().isEmpty())
        assertEquals(900L, db.budgetDao().allForMonth("2026-09").single { it.categoryId == 3L }.amountPaise)
    }
}
