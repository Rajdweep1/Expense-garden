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

@RunWith(AndroidJUnit4::class)
class RegretTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        repo = LedgerRepository(db)
    }

    @After fun teardown() = db.close()

    private suspend fun logOne(): String = repo.saveManualLogged(
        LedgerRepository.Draft(vpa = null, payeeName = "p", amountPaise = 5_000, categoryId = 103,
            note = null, occurredAt = System.currentTimeMillis()),
        breachedAtLogging = false,
    )

    private suspend fun eventsOf(type: String) = db.gameEventDao().allByIdAsc().filter { it.type == type }

    @Test fun tagging_regret_updates_row_and_emits_event() = runBlocking {
        val uuid = logOne()
        repo.setRegret(uuid, Regret.REGRET)
        assertEquals(Regret.REGRET, db.transactionDao().byUuid(uuid)!!.regret)
        assertEquals(1, eventsOf("transaction.regretted").size)
    }

    @Test fun clearing_regret_emits_cleared_event() = runBlocking {
        val uuid = logOne()
        repo.setRegret(uuid, Regret.REGRET)
        repo.setRegret(uuid, Regret.WORTH_IT)
        assertEquals(Regret.WORTH_IT, db.transactionDao().byUuid(uuid)!!.regret)
        assertEquals(1, eventsOf("transaction.regretted").size)
        assertEquals(1, eventsOf("transaction.regret_cleared").size)
    }

    @Test fun worth_it_from_unrated_emits_nothing() = runBlocking {
        val uuid = logOne()
        repo.setRegret(uuid, Regret.WORTH_IT)
        assertEquals(0, eventsOf("transaction.regretted").size)
        assertEquals(0, eventsOf("transaction.regret_cleared").size)
    }

    @Test fun retagging_same_value_is_a_no_op() = runBlocking {
        val uuid = logOne()
        repo.setRegret(uuid, Regret.REGRET)
        repo.setRegret(uuid, Regret.REGRET)
        assertEquals(1, eventsOf("transaction.regretted").size)
    }
}
