package com.expensegarden.app.data

import androidx.room.Room
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.game.Weather
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class GardenRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepository
    private lateinit var garden: GardenRepository
    private lateinit var budgets: BudgetRepository
    private val zone = ZoneId.systemDefault()

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        ledger = LedgerRepository(db)
        // Fixtures are built relative to "now", so the ledger begins today. Pinned for determinism.
        garden = GardenRepository(db, ledger, GardenPrefs(ctx).apply {
            firstObservedAt = System.currentTimeMillis()
        })
        budgets = BudgetRepository(db)
    }

    @After fun teardown() = db.close()

    private suspend fun log(cat: Long, paise: Long, at: Long = System.currentTimeMillis()) =
        ledger.saveManualLogged(
            LedgerRepository.Draft(vpa = null, payeeName = "p", amountPaise = paise, categoryId = cat, note = null, occurredAt = at),
            breachedAtLogging = false,
        )

    @Test fun observed_garden_reflects_logs_live() = runBlocking {
        log(103, 5_000)
        val g = garden.observeCurrentGarden().first()
        assertEquals(1, g.plants.size)
        assertEquals(Weather.SUNNY, g.weather)
    }

    @Test fun dodge_becomes_butterfly() = runBlocking {
        ledger.recordGateDodge(10_000, categoryId = 103)
        assertEquals(1, garden.observeCurrentGarden().first().butterflies)
    }

    @Test fun reconciler_closes_past_month_once() = runBlocking {
        val lastMonth = YearMonth.now(zone).minusMonths(1)
        log(103, 5_000, at = lastMonth.atDay(10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
        garden.runReconciler()
        garden.runReconciler()   // idempotent
        val closed = db.gameEventDao().ofType("month.closed")
        assertEquals(1, closed.size)
        assertTrue(closed.single().payloadJson.contains(lastMonth.toString()))
    }

    @Test fun archived_month_folds_frozen() = runBlocking {
        val lastMonth = YearMonth.now(zone).minusMonths(1)
        log(103, 5_000, at = lastMonth.atDay(10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
        val g = garden.foldMonth(lastMonth.toString())
        assertTrue(g.archived)
        assertEquals(1, g.plants.size)
        assertEquals(listOf(lastMonth.toString(), YearMonth.now(zone).toString()), garden.monthsWithData())
    }

    private fun draftOn(d: LocalDate) = LedgerRepository.Draft(
        vpa = null, payeeName = "Kirana", amountPaise = 50_000, categoryId = 7, note = null,
        occurredAt = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
    )

    private suspend fun closed(m: String) = JSONObject(
        db.gameEventDao().ofType("month.closed").single { JSONObject(it.payloadJson).getString("month") == m }.payloadJson
    )

    @Test fun a_month_whose_budget_was_set_after_it_began_closes_without_one() = runBlocking {
        budgets.setBudget(null, "2025-03", 1_500_000)        // stamped now, long after March 2025
        ledger.saveManualLogged(draftOn(LocalDate.of(2025, 3, 10)), breachedAtLogging = false)
        garden.runReconciler()
        assertTrue(closed("2025-03").isNull("overallBudgetPaise"))
    }

    @Test fun a_carried_month_closes_with_its_budget() = runBlocking {
        budgets.setBudget(null, "2025-03", 1_500_000)
        budgets.carryForward("2025-04")
        ledger.saveManualLogged(draftOn(LocalDate.of(2025, 4, 10)), breachedAtLogging = false)
        garden.runReconciler()
        assertEquals(1_500_000L, closed("2025-04").getLong("overallBudgetPaise"))
    }

    @Test fun a_month_whose_budget_was_raised_closes_on_the_lower_amount() = runBlocking {
        budgets.setBudget(null, "2025-03", 1_500_000)
        budgets.carryForward("2025-04")                                  // ₹15,000 from 1 April
        ledger.saveManualLogged(draftOn(LocalDate.of(2025, 4, 10)).copy(amountPaise = 1_800_000), breachedAtLogging = false)
        budgets.setBudget(null, "2025-04", 2_000_000)
        // setBudget stamps its event with today's date; this records the raise on the day it happened.
        db.gameEventDao().insert(GameEventEntity(
            type = BudgetRepository.EVENT_SET,
            payloadJson = JSONObject().put("month", "2025-04").put("categoryId", JSONObject.NULL)
                .put("amountPaise", 2_000_000).toString(),
            transactionUuid = null,
            createdAt = LocalDate.of(2025, 4, 28).atTime(21, 0).atZone(zone).toInstant().toEpochMilli(),
        ))
        garden.runReconciler()
        assertEquals(1_500_000L, closed("2025-04").getLong("overallBudgetPaise"))
    }

    @Test fun raising_the_budget_mints_no_streak_for_the_days_before_it() = runBlocking {
        val today = LocalDate.now(zone)
        assumeTrue("the 1st has no finished day to score", today.dayOfMonth > 1)
        val month = YearMonth.from(today)
        GardenPrefs(ApplicationProvider.getApplicationContext()).firstObservedAt =
            month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        budgets.setBudget(null, month.minusMonths(1).toString(), 1_500_000)
        budgets.carryForward(month.toString())                           // ₹15,000 from the 1st
        // ₹643 a day is over ₹15,000's pace line every day, and under ₹20,000's.
        for (d in 1 until today.dayOfMonth) {
            ledger.saveManualLogged(draftOn(month.atDay(d)).copy(amountPaise = 64_300), breachedAtLogging = false)
        }
        budgets.setBudget(null, month.toString(), 2_000_000)             // raised today
        assertEquals(0, garden.currentUnderPaceStreak())
        garden.runReconciler()
        assertTrue(db.gameEventDao().ofType("streak.hit").isEmpty())
    }

    @Test fun the_month_fold_paces_its_streak_against_rent() = runBlocking {
        GardenPrefs(ApplicationProvider.getApplicationContext()).firstObservedAt =
            LocalDate.of(2025, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        budgets.setBudget(null, "2025-05", 1_500_000)
        budgets.carryForward("2025-06")                                  // ₹15,000 all of June
        suspend fun paid(payee: String, vpa: String?, paise: Long, day: LocalDate, cat: Long) = ledger.saveManualLogged(
            LedgerRepository.Draft(vpa = vpa, payeeName = payee, amountPaise = paise, categoryId = cat, note = null,
                occurredAt = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()),
            breachedAtLogging = false,
        )
        paid("Landlord", "landlord@ybl", 1_000_000, LocalDate.of(2025, 5, 1), 401)   // once in May: June's rent is fixed
        paid("Landlord", "landlord@ybl", 1_000_000, LocalDate.of(2025, 6, 1), 401)
        for (d in 1..30) paid("Chai", null, 10_000, LocalDate.of(2025, 6, d), 103)
        // Against the plain line the rent fails June 1–21; against the fixed-cost line no day fails.
        assertEquals(30, garden.foldMonth("2025-06").streakDays)
    }
}
