package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.sync.SyncClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Status changes are guarded by the status they expect, so a double tap, a retried save or an
 *  Undo that arrives late is a no-op rather than a second log, a crash or a resurrection. */
@RunWith(AndroidJUnit4::class)
class LedgerTransitionTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository
    private class Store(override var lastStamp: Long = 0L) : SyncClock.Store

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback)
            .allowMainThreadQueries()
            .build()
        repo = LedgerRepository(db, SyncClock({ 1_000L }, Store()))
    }

    @After fun tearDown() = db.close()

    private fun draft(payeeName: String = "Corner Shop", amountPaise: Long = 100) = LedgerRepository.Draft(
        vpa = "shop@ybl", payeeName = payeeName, amountPaise = amountPaise, categoryId = 7, note = null,
        occurredAt = 1_760_000_000_000L,
    )

    @Test fun confirming_twice_logs_once() = runBlocking {
        val uuid = repo.savePending(draft(), breachedAtLogging = false)
        assertTrue(repo.confirm(uuid)); assertFalse(repo.confirm(uuid))
        assertEquals(1, db.gameEventDao().ofType("transaction.logged").count { it.transactionUuid == uuid })
    }

    @Test fun a_stale_transition_changes_nothing() = runBlocking {
        val uuid = repo.savePending(draft(), false)
        assertFalse(repo.revert(uuid, TxnStatus.LOGGED, TxnStatus.DISCARDED))
        assertEquals(TxnStatus.PENDING_CONFIRM, db.transactionDao().byUuid(uuid)!!.status)
    }

    @Test fun discarding_then_undoing_brings_the_pending_back() = runBlocking {
        val uuid = repo.savePending(draft(), false)
        assertTrue(repo.discard(uuid))
        assertTrue(repo.revert(uuid, TxnStatus.DISCARDED, TxnStatus.PENDING_CONFIRM))
        assertEquals(listOf(uuid), repo.observePendingRows().first().map { it.uuid })
    }

    @Test fun undoing_a_manual_log_takes_it_out_of_the_month() = runBlocking {
        val uuid = repo.saveManualLogged(draft(amountPaise = 45_000), breachedAtLogging = false)
        assertTrue(repo.revert(uuid, TxnStatus.LOGGED, TxnStatus.DISCARDED))
        assertEquals(0L, db.transactionDao().loggedSumBetween(0L, Long.MAX_VALUE))
    }

    @Test fun saving_the_same_pending_twice_keeps_one_row() = runBlocking {
        assertTrue(repo.savePendingOnce(draft(), false, "scan-1"))
        assertFalse(repo.savePendingOnce(draft(), false, "scan-1"))
        assertEquals(1, repo.observePendingRows().first().size)
    }

    @Test fun pending_rows_carry_the_payee_name() = runBlocking {
        repo.savePending(draft(payeeName = "Sharma Chai"), false)
        assertEquals("Sharma Chai", repo.observePendingRows().first().single().payeeName)
    }

    @Test fun cash_payees_match_whatever_the_spacing_or_case() = runBlocking {
        fun cash(name: String, vpa: String? = null) = LedgerRepository.Draft(vpa = vpa, payeeName = name, amountPaise = 10_000,
            categoryId = 103, note = null, occurredAt = 1_000L)
        val uuids = listOf("Chai Point", "  chai   point ", "CHAI POINT").map { repo.saveManualLogged(cash(it), breachedAtLogging = false) }
        val payeeIds = uuids.map { db.transactionDao().byUuid(it)!!.payeeId }
        assertEquals(1, payeeIds.toSet().size)
        assertEquals("Chai Point", db.transactionDao().rowByUuid(uuids.last())?.payeeName)   // the first spelling is kept
        // A UPI payee with the same name is someone else: those match on their VPA.
        val upi = repo.saveManualLogged(cash("Chai Point", vpa = "chai@ybl"), breachedAtLogging = false)
        assertNotEquals(payeeIds.first(), db.transactionDao().byUuid(upi)!!.payeeId)
    }

    // Pay first: the awaiting payment's id is the transaction's uuid, so Log it twice (a double tap,
    // or a kill between the save and the store removal) writes one row (plan Q3).
    @Test fun log_it_twice_writes_one_row() = runBlocking {
        assertTrue(repo.saveLoggedOnce(draft(), TxnSource.QR_GATE, breached = false, uuid = "pay-1"))
        assertFalse(repo.saveLoggedOnce(draft(), TxnSource.QR_GATE, breached = false, uuid = "pay-1"))
        val row = db.transactionDao().byUuid("pay-1")!!
        assertEquals(TxnSource.QR_GATE, row.source)
        assertEquals(TxnStatus.LOGGED, row.status)
        assertEquals(1, db.gameEventDao().ofType("transaction.logged").count { it.transactionUuid == "pay-1" })
    }

    @Test fun a_dodge_without_amount_or_category_is_recorded() = runBlocking {
        repo.recordGateDodge(amountPaise = null, categoryId = null)
        assertEquals(1, db.gameEventDao().ofType("gate.dodged").size)
    }

    @Test fun last_amount_to_reads_the_newest_logged_payment() = runBlocking {
        assertEquals(null, repo.lastAmountTo("shop@ybl"))
        repo.saveManualLogged(draft(amountPaise = 300).copy(occurredAt = 1L), false)
        repo.saveManualLogged(draft(amountPaise = 700).copy(occurredAt = 2L), false)
        repo.savePending(draft(amountPaise = 999).copy(occurredAt = 3L), false)   // not logged: ignored
        assertEquals(700L, repo.lastAmountTo("shop@ybl"))
    }
}
