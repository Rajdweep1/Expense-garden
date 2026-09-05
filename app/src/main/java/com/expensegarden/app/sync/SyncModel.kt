package com.expensegarden.app.sync

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.PayeeEntity
import com.expensegarden.app.data.SyncTombstoneEntity
import com.expensegarden.app.data.TransactionEntity

/** Everything the phone has that the server does not (spec §3.1).
 *
 *  Deliberately free of org.json: that class is an Android stub which throws "not mocked" in
 *  JVM unit tests, so any arithmetic entangled with it becomes untestable off-device.
 *  Serialization lives at the boundary in SyncClient — the same split DigestTrigger and
 *  DigestRepository use in 1D. */
data class SyncBatch(
    val categories: List<CategoryEntity>,
    val payees: List<PayeeEntity>,
    val txns: List<TransactionEntity>,
    val budgets: List<BudgetEntity>,
    val tombstones: List<SyncTombstoneEntity>,
    val events: List<GameEventEntity>,
) {
    val isEmpty: Boolean
        get() = categories.isEmpty() && payees.isEmpty() && txns.isEmpty() &&
            budgets.isEmpty() && tombstones.isEmpty() && events.isEmpty()

    val rowCount: Int
        get() = categories.size + payees.size + txns.size + budgets.size +
            tombstones.size + events.size
}

/** Everything the server has, for a restore. */
data class SyncSnapshot(
    val categories: List<CategoryEntity>,
    val payees: List<PayeeEntity>,
    val txns: List<TransactionEntity>,
    val budgets: List<BudgetEntity>,
    val events: List<GameEventEntity>,
)

data class Cursors(val lastPushedAt: Long, val lastPushedEventId: Long)

object CursorMath {
    /** The cursors a batch earns IF the server accepts it.
     *
     *  Computed from the batch that was actually built, never from "now": a row written during
     *  the network round trip carries a strictly higher stamp than anything in this batch (the
     *  logical clock guarantees it), so it stays dirty and is caught next time. That is the
     *  same head-first ordering 1D's DigestRepository.window() uses to close its race. */
    fun advanced(current: Cursors, batch: SyncBatch): Cursors {
        val maxStamp = maxOf(
            batch.categories.maxOfOrNull { it.updatedAt } ?: 0L,
            batch.payees.maxOfOrNull { it.updatedAt } ?: 0L,
            batch.txns.maxOfOrNull { it.updatedAt } ?: 0L,
            batch.budgets.maxOfOrNull { it.updatedAt } ?: 0L,
            batch.tombstones.maxOfOrNull { it.deletedAt } ?: 0L,
        )
        return Cursors(
            lastPushedAt = maxOf(current.lastPushedAt, maxStamp),
            lastPushedEventId = maxOf(current.lastPushedEventId, batch.events.maxOfOrNull { it.id } ?: 0L),
        )
    }
}
