package com.expensegarden.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback)
            .allowMainThreadQueries()
            .build()
    }

    @After fun teardown() = db.close()

    @Test fun seed_categories_present() = runBlocking {
        // Any query forces onCreate; seed row 2 = Groceries (necessity)
        val groceries = db.categoryDao().byId(2)
        assertNotNull(groceries)
        assertEquals(true, groceries!!.isNecessity)
    }

    @Test fun insert_and_sum_logged_transaction() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "Chaiwala", vpa = "chai@ybl", defaultCategoryId = 103, updatedAt = 1L))
        db.transactionDao().insert(
            TransactionEntity(
                uuid = UUID.randomUUID().toString(), amountPaise = 2000, payeeId = payeeId,
                categoryId = 103, source = TxnSource.QR_GATE, status = TxnStatus.LOGGED,
                breachedAtLogging = false, note = null, occurredAt = 1000L, createdAt = 1000L, updatedAt = 1L,
            )
        )
        assertEquals(2000L, db.transactionDao().loggedSumBetween(0L, 2000L))
        assertEquals(0L, db.transactionDao().loggedSumBetween(3000L, 4000L))
    }

    @Test(expected = SQLiteConstraintException::class)
    fun fk_rejects_transaction_with_unknown_category(): Unit = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "X", vpa = null, defaultCategoryId = null, updatedAt = 1L))
        db.transactionDao().insert(
            TransactionEntity(
                uuid = UUID.randomUUID().toString(), amountPaise = 100, payeeId = payeeId,
                categoryId = 999_999, source = TxnSource.MANUAL, status = TxnStatus.LOGGED,
                breachedAtLogging = false, note = null, occurredAt = 0L, createdAt = 0L, updatedAt = 1L,
            )
        )
    }

    @Test fun quip_picker_prefers_unused_then_lru() = runBlocking {
        val first = db.quipDao().leastRecentlyUsed("BREACH", "SHARP")!!
        db.quipDao().markUsed(first.id, now = 100L)
        val second = db.quipDao().leastRecentlyUsed("BREACH", "SHARP")!!
        // second must be a different, still-unused quip
        assertEquals(null, second.usedAt)
    }

    @Test fun top_category_names_orders_by_logged_sum_and_caps_at_three() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "Mixed", vpa = null, defaultCategoryId = null, updatedAt = 1L))
        fun txn(categoryId: Long, paise: Long, at: Long, status: TxnStatus = TxnStatus.LOGGED) = TransactionEntity(
            uuid = UUID.randomUUID().toString(), amountPaise = paise, payeeId = payeeId,
            categoryId = categoryId, source = TxnSource.MANUAL, status = status,
            breachedAtLogging = false, note = null, occurredAt = at, createdAt = at, updatedAt = 1L,
        )
        // Sums inside [1000, 2000]: Fuel 900, Restaurants 500 (two rows), Streaming 300, Chai 100.
        db.transactionDao().insert(txn(301, 900, 1000))
        db.transactionDao().insert(txn(101, 200, 1100))
        db.transactionDao().insert(txn(101, 300, 1200))
        db.transactionDao().insert(txn(601, 300, 1300))
        db.transactionDao().insert(txn(103, 100, 1400))
        // Excluded: pending-confirm inside the window, and LOGGED but outside it.
        db.transactionDao().insert(txn(2, 5000, 1500, status = TxnStatus.PENDING_CONFIRM))
        db.transactionDao().insert(txn(303, 9000, 3000))

        assertEquals(listOf("Fuel", "Restaurants", "Streaming"), db.transactionDao().topCategoryNames(1000L, 2000L))
    }
}
