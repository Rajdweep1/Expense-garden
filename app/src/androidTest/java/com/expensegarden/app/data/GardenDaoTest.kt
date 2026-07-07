package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GardenDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
    }

    @After fun teardown() = db.close()

    private suspend fun logTxn(categoryId: Long, paise: Long, at: Long): String {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "p$at", vpa = null, defaultCategoryId = null))
        val uuid = UUID.randomUUID().toString()
        db.transactionDao().insert(TransactionEntity(
            uuid = uuid, amountPaise = paise, payeeId = payeeId, categoryId = categoryId,
            source = TxnSource.MANUAL, status = TxnStatus.LOGGED, breachedAtLogging = false,
            note = null, occurredAt = at, createdAt = at,
        ))
        return uuid
    }

    @Test fun logged_rows_between_bounds_flow_and_suspend() = runBlocking {
        logTxn(103, 1_000, at = 100L)
        logTxn(103, 2_000, at = 200L)
        logTxn(103, 4_000, at = 900L)
        assertEquals(2, db.transactionDao().loggedBetween(0L, 500L).size)
        assertEquals(2, db.transactionDao().observeLoggedBetween(0L, 500L).first().size)
    }

    @Test fun txn_row_by_uuid_joins_names() = runBlocking {
        val uuid = logTxn(103, 1_000, at = 100L)
        val row = db.transactionDao().rowByUuid(uuid)!!
        assertEquals("Chai & Snacks", row.categoryName)
        assertEquals(1_000L, row.amountPaise)
    }

    @Test fun events_between_and_by_type() = runBlocking {
        db.gameEventDao().insert(GameEventEntity(type = "gate.dodged", payloadJson = "{}", transactionUuid = null, createdAt = 100L))
        db.gameEventDao().insert(GameEventEntity(type = "month.closed", payloadJson = "{}", transactionUuid = null, createdAt = 200L))
        assertEquals(1, db.gameEventDao().eventsBetween(0L, 150L).size)
        assertEquals(1, db.gameEventDao().observeEventsBetween(0L, 150L).first().size)
        assertEquals(1, db.gameEventDao().ofType("month.closed").size)
    }

    @Test fun logged_count_in_categories_and_earliest() = runBlocking {
        logTxn(10, 1_000, at = 100L)
        logTxn(10, 1_000, at = 300L)
        logTxn(103, 1_000, at = 200L)
        assertEquals(2, db.transactionDao().observeLoggedCountIn(listOf(10L)).first())
        assertEquals(100L, db.transactionDao().earliestLoggedAt())
    }
}
