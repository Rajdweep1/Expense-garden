package com.expensegarden.app.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.data.AppDatabase
import com.expensegarden.app.data.LedgerRepository
import com.expensegarden.app.data.PayeeEntity
import com.expensegarden.app.data.SeedCallback
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncRestoreTest {
    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepository
    private lateinit var sync: SyncRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        ledger = LedgerRepository(db)
        val prefs = SyncPrefs(ApplicationProvider.getApplicationContext())
        sync = SyncRepository(db, SyncClient(prefs), prefs)
    }

    @After fun teardown() = db.close()

    private fun logOne(): String = runBlocking {
        ledger.saveManualLogged(
            LedgerRepository.Draft(vpa = null, payeeName = "Phone shop", amountPaise = 10_000, categoryId = 103,
                note = null, occurredAt = 1_000L),
            breachedAtLogging = false,
        )
    }

    private fun snapshot(withTxn: Boolean) = runBlocking {
        SyncSnapshot(
            categories = db.categoryDao().all(),
            payees = if (withTxn) listOf(PayeeEntity(id = 500, name = "Server shop", vpa = null, defaultCategoryId = null, updatedAt = 1L)) else emptyList(),
            txns = if (withTxn) listOf(
                TransactionEntity(uuid = "server-1", amountPaise = 1_000, payeeId = 500, categoryId = 103,
                    source = TxnSource.MANUAL, status = TxnStatus.LOGGED, breachedAtLogging = false, note = null,
                    occurredAt = 1_000L, createdAt = 1_000L, updatedAt = 1L),
            ) else emptyList(),
            budgets = emptyList(),
            events = emptyList(),
        )
    }

    @Test fun restore_refuses_an_empty_snapshot_over_a_ledger() = runBlocking {
        logOne()
        assertFalse(sync.restore(snapshot(withTxn = false)))
        assertEquals(1, db.syncDao().txnCount())
    }

    @Test fun restore_replaces_the_ledger_with_a_snapshot() = runBlocking {
        val mine = logOne()
        assertTrue(sync.restore(snapshot(withTxn = true)))
        assertEquals(1, db.syncDao().txnCount())
        assertNotNull(db.transactionDao().byUuid("server-1"))
        assertNull(db.transactionDao().byUuid(mine))
    }
}
