package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CrossingEventTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository
    private val zone = ZoneId.systemDefault()
    private val month = YearMonth.now(zone).toString()
    private val nowMillis = System.currentTimeMillis()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        repo = LedgerRepository(db)
    }

    @After fun teardown() = db.close()

    private fun draft(paise: Long, categoryId: Long = 103, at: Long = nowMillis) = LedgerRepository.Draft(
        vpa = null, payeeName = "p", amountPaise = paise, categoryId = categoryId, note = null, occurredAt = at,
    )

    private suspend fun eventsOf(type: String) = db.gameEventDao().allByIdAsc().filter { it.type == type }

    @Test fun breach_crossing_fires_once_not_on_every_subsequent_txn() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = month, amountPaise = 10_000)) // ₹100 on Food
        repo.saveManualLogged(draft(9_000), breachedAtLogging = false)     // 90 ≤ 100: no cross
        assertEquals(0, eventsOf("budget.breached").size)
        repo.saveManualLogged(draft(2_000), breachedAtLogging = true)      // 90 → 110: crosses
        assertEquals(1, eventsOf("budget.breached").size)
        repo.saveManualLogged(draft(1_000), breachedAtLogging = true)      // 110 → 120: already past, no dup
        assertEquals(1, eventsOf("budget.breached").size)
    }

    @Test fun raising_the_budget_can_legitimately_recross() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = month, amountPaise = 10_000))
        repo.saveManualLogged(draft(11_000), breachedAtLogging = true)     // 0 → 110 vs 100: cross #1
        db.budgetDao().deleteForCategory(1, month)
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = month, amountPaise = 20_000)) // raised to ₹200
        repo.saveManualLogged(draft(10_000), breachedAtLogging = true)     // 110 → 210 vs 200: cross #2
        assertEquals(2, eventsOf("budget.breached").size)
    }

    @Test fun confirm_path_fires_crossings_when_spend_materializes() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = month, amountPaise = 10_000))
        val uuid = repo.savePending(draft(11_000), breachedAtLogging = true)
        assertEquals(0, eventsOf("budget.breached").size)                  // pending ≠ spent
        repo.confirm(uuid)
        assertEquals(1, eventsOf("budget.breached").size)
    }

    @Test fun backdated_past_month_txn_emits_no_weather() = runBlocking {
        val lastMonth = YearMonth.now(zone).minusMonths(1)
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = lastMonth.toString(), amountPaise = 1_000))
        val at = lastMonth.atDay(10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        repo.saveManualLogged(draft(5_000, at = at), breachedAtLogging = true)
        assertEquals(0, eventsOf("budget.breached").size)
        assertEquals(1, eventsOf("transaction.logged").size)               // the seed still plants
    }

    @Test fun pace_warning_crossing_fires_without_breach() = runBlocking {
        // Big budget so breach is far: allowance today = budget * day/days * 1.15
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = month, amountPaise = 3_000_000))
        val (day, days) = repo.dayAndLengthOf(nowMillis)
        val allowance = com.expensegarden.app.gate.GateEvaluator.paceAllowancePaise(3_000_000, day, days)
        repo.saveManualLogged(draft(allowance + 1), breachedAtLogging = false)  // 0 → allowance+1: crosses pace
        assertEquals(1, eventsOf("budget.pace_warning").size)
        assertEquals(0, eventsOf("budget.breached").size)
    }
}
