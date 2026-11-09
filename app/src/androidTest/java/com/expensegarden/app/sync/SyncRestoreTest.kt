package com.expensegarden.app.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.data.AppDatabase
import com.expensegarden.app.data.LedgerRepository
import com.expensegarden.app.data.PayeeEntity
import com.expensegarden.app.data.SeedCallback
import com.expensegarden.app.data.SyncTombstoneEntity
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
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class SyncRestoreTest {
    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepository
    private lateinit var sync: SyncRepository
    private lateinit var prefs: SyncPrefs

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        ledger = LedgerRepository(db)
        prefs = SyncPrefs(ApplicationProvider.getApplicationContext())
        sync = SyncRepository(db, SyncClient(prefs), prefs)
    }

    @After fun teardown() {
        db.close()
        prefs.serverUrl = ""; prefs.token = ""; prefs.lastSuccessAt = 0L
        prefs.lastFailure = null; prefs.lastFailureAt = 0L; prefs.lastPushedAt = 0L; prefs.lastPushedEventId = 0L
    }

    /** A one-port HTTP server that refuses any batch carrying transactions and accepts an empty
     *  one: a server whose real pushes fail while the connection check would pass. */
    private fun startServerRefusingRealBatches(): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val input = s.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(":").trim().toInt()
                    }
                    val body = CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
                    val code = if (String(body).contains("\"txns\":[{")) "500 Internal Server Error" else "200 OK"
                    s.getOutputStream().write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                }
            }
        }
        return server
    }

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

    @Test fun the_connection_check_does_not_hide_a_failing_push() = runBlocking {
        val server = startServerRefusingRealBatches()
        try {
            prefs.serverUrl = "http://127.0.0.1:${server.localPort}"
            prefs.token = "test-token"
            logOne()   // a payment waiting to be backed up, which this server refuses
            assertTrue(sync.checkConnection() is SyncResult.Failed)
            assertEquals(0L, prefs.lastSuccessAt)   // nothing was backed up, so nothing may say so
            assertEquals(SyncFailure.SERVER, prefs.lastFailure)
        } finally {
            server.close()
        }
    }

    @Test fun restore_clears_waiting_tombstones() = runBlocking {
        // A budget cleared while offline: pushed after a restore, it would delete the restored budget on the server.
        db.syncDao().putTombstone(SyncTombstoneEntity(tableName = "budget", rowKey = "*|2026-11", deletedAt = 5L))
        logOne()
        assertTrue(sync.restore(snapshot(withTxn = true)))
        assertEquals(0, sync.collect().tombstones.size)
    }

    @Test fun restore_refuses_a_snapshot_holding_only_discarded_rows() = runBlocking {
        // Discarded rows are payments that never happened: a server holding only those is empty.
        val mine = logOne()
        val discarded = snapshot(withTxn = true).let { s -> s.copy(txns = s.txns.map { it.copy(status = TxnStatus.DISCARDED) }) }
        assertFalse(sync.restore(discarded))
        assertNotNull(db.transactionDao().byUuid(mine))
    }
}
