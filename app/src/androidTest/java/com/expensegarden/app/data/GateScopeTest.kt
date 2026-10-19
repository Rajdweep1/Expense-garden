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
import java.time.LocalDate
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
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = month, amountPaise = 10_000_000, updatedAt = 1L))  // ₹1,00,000 overall
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = month, amountPaise = 50_000, updatedAt = 1L))         // ₹500 on Food & Drinks
        // ₹400 already logged under Chai & Snacks (child of Food & Drinks) this month
        repo.saveManualLogged(
            LedgerRepository.Draft(vpa = null, payeeName = "Chaiwala", amountPaise = 40_000,
                categoryId = 103, note = null, occurredAt = nowMillis),
            breachedAtLogging = false,
        )
        // Candidate ₹200 under Chai: child rolls into the Food budget → 400+200 > 500 → BREACH, offender = Food & Drinks
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 20_000, occurredAt = nowMillis, ledgerBeganAt = null)
        assertEquals(Severity.BREACH, verdict.severity)
        assertEquals(1L, verdict.offender?.categoryId)
        assertEquals("Food & Drinks", verdict.offender?.label)
    }

    @Test fun no_budgets_at_all_is_ok() = runBlocking {
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 1_000_000, occurredAt = nowMillis, ledgerBeganAt = null)
        assertEquals(Severity.OK, verdict.severity)
    }

    @Test fun backdated_evaluation_uses_that_months_budget_and_spend() = runBlocking {
        val lastMonth = java.time.YearMonth.now(zone).minusMonths(1)
        val lastMonthMillis = lastMonth.atDay(15).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = lastMonth.toString(), amountPaise = 1_000, updatedAt = 1L))
        // ₹50 candidate against last month's tiny ₹10 budget → BREACH even though this month has no budget
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 5_000, occurredAt = lastMonthMillis, ledgerBeganAt = null)
        assertEquals(Severity.BREACH, verdict.severity)
    }

    @Test fun a_payee_seen_once_in_a_partly_watched_month_does_not_loosen_the_next_months_gate() = runBlocking {
        fun noonOn(y: Int, m: Int, d: Int) =
            java.time.LocalDate.of(y, m, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-11", amountPaise = 2_000_000, updatedAt = 1L))  // ₹20,000
        // One dinner on Oct 24 in a ledger that began Oct 20, then the same place again on Nov 3.
        for (day in listOf(noonOn(2026, 10, 24), noonOn(2026, 11, 3))) {
            repo.saveManualLogged(
                LedgerRepository.Draft(vpa = null, payeeName = "Dinner place", amountPaise = 150_000,
                    categoryId = 101, note = null, occurredAt = day),
                breachedAtLogging = false,
            )
        }
        // ₹3,000 more on Nov 5: ₹4,500 against a ₹3,833 pace line is a warning.
        val nov5 = noonOn(2026, 11, 5)
        val floored = repo.evaluateGate(categoryId = 103, amountPaise = 300_000, occurredAt = nov5,
            ledgerBeganAt = noonOn(2026, 10, 20))
        assertEquals(Severity.PACE_WARNING, floored.severity)
        // Unfloored, the dinner counts as rent and the warning goes silent: the 2026-10-11 defect.
        val unfloored = repo.evaluateGate(categoryId = 103, amountPaise = 300_000, occurredAt = nov5,
            ledgerBeganAt = null)
        assertEquals(Severity.OK, unfloored.severity)
    }

    @Test fun the_gate_reads_necessity_from_the_database() = runBlocking {
        // Seed: Groceries (2) and Rent (401) are necessities; Shopping (7) is not.
        assertEquals(true, repo.isNecessity(2))
        assertEquals(true, repo.isNecessity(401))
        assertEquals(false, repo.isNecessity(7))
        assertEquals(false, repo.isNecessity(9_999))
    }

    @Test fun the_gate_sees_a_carried_budget() = runBlocking {
        val budgets = BudgetRepository(db)
        budgets.setBudget(null, "2026-10", 1_000_000)
        budgets.carryForward("2026-11")
        val nov15 = LocalDate.of(2026, 11, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(Severity.BREACH, repo.evaluateGate(7, 1_100_000, nov15, null).severity)
    }
}
