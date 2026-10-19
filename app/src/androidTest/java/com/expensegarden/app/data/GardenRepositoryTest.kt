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
}
