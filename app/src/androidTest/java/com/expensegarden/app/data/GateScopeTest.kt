package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.gate.Severity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class GateScopeTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository
    private val zone = ZoneId.systemDefault()
    private val nowMillis = System.currentTimeMillis()
    private val month = java.time.YearMonth.now(zone).toString()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        repo = LedgerRepository(db)
    }

    @After fun teardown() = db.close()

    @Test fun category_budget_breach_wins_over_healthy_overall() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = month, amountPaise = 10_000_000))  // ₹1,00,000 overall
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = month, amountPaise = 50_000))         // ₹500 on Food & Drinks
        // ₹400 already logged under Chai & Snacks (child of Food & Drinks) this month
        repo.saveManualLogged(
            LedgerRepository.Draft(vpa = null, payeeName = "Chaiwala", amountPaise = 40_000,
                categoryId = 103, note = null, occurredAt = nowMillis),
            breachedAtLogging = false,
        )
        // Candidate ₹200 under Chai: child rolls into the Food budget → 400+200 > 500 → BREACH, offender = Food & Drinks
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 20_000, occurredAt = nowMillis)
        assertEquals(Severity.BREACH, verdict.severity)
        assertEquals(1L, verdict.offender?.categoryId)
        assertEquals("Food & Drinks", verdict.offender?.label)
    }

    @Test fun no_budgets_at_all_is_ok() = runBlocking {
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 1_000_000, occurredAt = nowMillis)
        assertEquals(Severity.OK, verdict.severity)
    }

    @Test fun backdated_evaluation_uses_that_months_budget_and_spend() = runBlocking {
        val lastMonth = java.time.YearMonth.now(zone).minusMonths(1)
        val lastMonthMillis = lastMonth.atDay(15).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = lastMonth.toString(), amountPaise = 1_000))
        // ₹50 candidate against last month's tiny ₹10 budget → BREACH even though this month has no budget
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 5_000, occurredAt = lastMonthMillis)
        assertEquals(Severity.BREACH, verdict.severity)
    }
}
