package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.sync.SyncClock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private suspend fun carried(m: String) =
        db.gameEventDao().ofType("budget.carried").filter { JSONObject(it.payloadJson).getString("month") == m }

    @Test fun carrying_forward_copies_the_latest_months_budgets_into_an_empty_month() = runBlocking {
        repo.setBudget(null, "2026-10", 1_500_000); repo.setBudget(3, "2026-10", 400_000)
        assertTrue(repo.carryForward("2026-11"))
        assertEquals(mapOf(null to 1_500_000L, 3L to 400_000L),
            db.budgetDao().allForMonth("2026-11").associate { it.categoryId to it.amountPaise })
        val octMax = db.budgetDao().allForMonth("2026-10").maxOf { it.updatedAt }
        assertTrue(db.budgetDao().allForMonth("2026-11").all { it.updatedAt > octMax })   // dirty for sync
        assertEquals(1, carried("2026-11").size)
        assertEquals(1_500_000L, JSONObject(carried("2026-11").single().payloadJson).getLong("overallPaise"))
    }

    @Test fun a_month_with_budgets_is_left_alone() = runBlocking {
        repo.setBudget(null, "2026-10", 1_500_000); repo.setBudget(null, "2026-11", 900_000)
        assertFalse(repo.carryForward("2026-11"))
        assertEquals(900_000L, db.budgetDao().overallForMonth("2026-11")!!.amountPaise)
    }

    @Test fun a_month_the_user_cleared_stays_cleared() = runBlocking {
        repo.setBudget(null, "2026-10", 1_500_000)
        repo.carryForward("2026-11"); repo.setBudget(null, "2026-11", null)
        assertFalse(repo.carryForward("2026-11"))
        assertTrue(db.budgetDao().allForMonth("2026-11").isEmpty())
    }

    @Test fun carrying_skips_a_gap_to_the_latest_month_with_budgets() = runBlocking {
        repo.setBudget(null, "2026-07", 3_200_000)
        assertTrue(repo.carryForward("2026-10"))
        assertEquals(3_200_000L, db.budgetDao().overallForMonth("2026-10")!!.amountPaise)
    }

    @Test fun with_no_budget_ever_set_nothing_is_carried() = runBlocking {
        assertFalse(repo.carryForward("2026-11"))
        assertTrue(db.gameEventDao().ofType("budget.carried").isEmpty())
    }

    @Test fun setting_a_budget_records_when_it_was_set() = runBlocking {
        repo.setBudget(null, "2026-10", 1_500_000)
        val sets = db.gameEventDao().ofType("budget.set")
        assertEquals(1, sets.size)
        val payload = JSONObject(sets.single().payloadJson)
        assertEquals("2026-10", payload.getString("month"))
        assertTrue(payload.isNull("categoryId"))
        assertEquals(1_500_000L, payload.getLong("amountPaise"))

        repo.setBudget(null, "2026-10", null)   // a clear records no new budget.set
        assertEquals(1, db.gameEventDao().ofType("budget.set").size)
    }
}
