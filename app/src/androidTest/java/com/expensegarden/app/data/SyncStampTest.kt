package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Spec §2.4: the three UPDATE statements that can silently forget to stamp. An unstamped
 *  row is invisible to the dirty-row predicate forever, which surfaces only as a restore
 *  that comes up short — so each one gets a test rather than a code review. */
@RunWith(AndroidJUnit4::class)
class SyncStampTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private suspend fun insertTxn(uuid: String, payeeId: Long) {
        db.transactionDao().insert(
            TransactionEntity(
                uuid = uuid, amountPaise = 100, payeeId = payeeId, categoryId = 103,
                source = TxnSource.MANUAL, status = TxnStatus.PENDING_CONFIRM,
                breachedAtLogging = false, note = null, occurredAt = 1L, createdAt = 1L,
                updatedAt = 10L,
            )
        )
    }

    @Test fun set_status_advances_the_sync_stamp() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "P", vpa = null, defaultCategoryId = null, updatedAt = 10L))
        insertTxn("u1", payeeId)

        db.transactionDao().setStatus("u1", TxnStatus.LOGGED, updatedAt = 77L)

        assertEquals(77L, db.transactionDao().byUuid("u1")!!.updatedAt)
    }

    @Test fun set_regret_advances_the_sync_stamp() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "P", vpa = null, defaultCategoryId = null, updatedAt = 10L))
        insertTxn("u2", payeeId)

        db.transactionDao().setRegret("u2", Regret.REGRET, updatedAt = 88L)

        assertEquals(88L, db.transactionDao().byUuid("u2")!!.updatedAt)
    }

    @Test fun set_default_category_advances_the_payee_stamp() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "Q", vpa = "q@ybl", defaultCategoryId = null, updatedAt = 10L))

        db.payeeDao().setDefaultCategory(payeeId, 103, updatedAt = 99L)

        val back = db.payeeDao().byVpa("q@ybl")!!
        assertEquals(103L, back.defaultCategoryId)
        assertEquals(99L, back.updatedAt)
    }

    @Test fun an_unstamped_row_would_be_invisible_to_the_dirty_predicate() = runBlocking {
        // Guards the reason the three tests above exist: the cursor is exclusive, so a row
        // left at its old stamp is never selected again.
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "R", vpa = null, defaultCategoryId = null, updatedAt = 10L))
        insertTxn("u3", payeeId)
        db.transactionDao().setStatus("u3", TxnStatus.LOGGED, updatedAt = 50L)

        assertTrue(db.syncDao().txnsChangedSince(49L).any { it.uuid == "u3" })
        assertTrue(db.syncDao().txnsChangedSince(50L).none { it.uuid == "u3" })
    }

    /** Regression. SeedCallback writes categories with raw SQL, bypassing the entity
     *  constructor, so it is the one category-writing path the compiler cannot force to supply
     *  a stamp. Left at the column default of 0 they are invisible to the dirty predicate
     *  forever — and since txn.categoryId is a real foreign key on the server, the first
     *  transaction push would be rejected and degrade to silence. */
    @Test fun seeded_categories_are_dirty_against_a_fresh_cursor() = runBlocking {
        val seeded = db.syncDao().categoriesChangedSince(0L)

        assertEquals(21, seeded.size)
        assertTrue("every seeded category must carry a stamp", seeded.all { it.updatedAt > 0L })
    }
}
