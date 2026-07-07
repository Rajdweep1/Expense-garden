package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.game.Weather
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class GardenRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepository
    private lateinit var garden: GardenRepository
    private val zone = ZoneId.systemDefault()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        ledger = LedgerRepository(db)
        garden = GardenRepository(db, ledger)
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
}
