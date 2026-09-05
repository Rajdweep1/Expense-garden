package com.expensegarden.app.sync

import androidx.room.withTransaction
import com.expensegarden.app.data.AppDatabase

/** Drives one push and owns restore (spec §3.2, §3.3).
 *
 *  Nothing here is a read path. Screens read Room; this only ever writes to the server, or —
 *  on an explicit restore — replaces Room wholesale from it. */
class SyncRepository(
    private val db: AppDatabase,
    private val client: SyncClient,
    private val prefs: SyncPrefs,
) {
    /** Collect everything dirty. Cursors are read ONCE, before the reads, so a row written
     *  during collection carries a higher stamp and is simply caught next time. */
    suspend fun collect(): SyncBatch {
        val since = prefs.lastPushedAt
        val sinceEvent = prefs.lastPushedEventId
        val dao = db.syncDao()
        return SyncBatch(
            categories = dao.categoriesChangedSince(since),
            payees = dao.payeesChangedSince(since),
            txns = dao.txnsChangedSince(since),
            budgets = dao.budgetsChangedSince(since),
            tombstones = dao.tombstones(),
            events = dao.eventsAfter(sinceEvent),
        )
    }

    /** One push. True if something was sent AND accepted.
     *
     *  Cursors advance only on success, and only to values derived from the batch that was
     *  actually sent — never to "now". A failure leaves every cursor untouched, so the same
     *  rows are simply re-sent next time; the server's upserts are idempotent, which is what
     *  makes blind retry safe. */
    suspend fun pushOnce(): Boolean {
        if (!prefs.isConfigured) return false
        val batch = collect()
        if (batch.isEmpty) return false

        if (!client.push(batch)) return false

        val advanced = CursorMath.advanced(Cursors(prefs.lastPushedAt, prefs.lastPushedEventId), batch)
        prefs.lastPushedAt = advanced.lastPushedAt
        prefs.lastPushedEventId = advanced.lastPushedEventId
        prefs.lastSuccessAt = System.currentTimeMillis()
        // Tombstones the server has accepted have done their job. Bounded by what was sent, so
        // a clear that happened during the round trip survives to be pushed next time.
        db.syncDao().clearTombstonesUpTo(batch.tombstones.maxOfOrNull { it.deletedAt } ?: 0L)
        return true
    }

    /** How many rows are waiting. Drives the Settings status line (spec §5). */
    suspend fun pendingCount(): Int = collect().rowCount

    /** Replace local data with the server's (spec §3.3).
     *
     *  Guarded by the caller, not here: the UI refuses to offer this against a non-empty
     *  ledger without an explicit confirmation.
     *
     *  Ids are preserved, which is mandatory rather than cosmetic — txn.payeeId and
     *  game_event.transactionUuid are real foreign keys, and the garden's month markers
     *  depend on event ordering. Verified separately: an explicit-id insert also advances
     *  SQLite's AUTOINCREMENT sequence, so the next new payee cannot collide with a
     *  restored one. */
    suspend fun restore(): Boolean {
        if (!prefs.isConfigured) return false
        val snap = client.snapshot() ?: return false

        db.withTransaction {
            val dao = db.syncDao()
            dao.wipeEvents()
            dao.wipeBudgets()
            dao.wipeTxns()
            dao.wipePayees()

            db.categoryDao().upsertAll(snap.categories)
            db.payeeDao().insertAll(snap.payees)
            db.transactionDao().insertAll(snap.txns)
            db.budgetDao().insertAll(snap.budgets)
            db.gameEventDao().insertAll(snap.events)
        }

        // Do not immediately re-upload what we were just given.
        prefs.lastPushedAt = maxOf(
            snap.categories.maxOfOrNull { it.updatedAt } ?: 0L,
            snap.payees.maxOfOrNull { it.updatedAt } ?: 0L,
            snap.txns.maxOfOrNull { it.updatedAt } ?: 0L,
            snap.budgets.maxOfOrNull { it.updatedAt } ?: 0L,
        )
        prefs.lastPushedEventId = snap.events.maxOfOrNull { it.id } ?: 0L
        prefs.lastSuccessAt = System.currentTimeMillis()
        return true
    }

    suspend fun isLedgerEmpty(): Boolean = db.syncDao().txnCount() == 0
}
