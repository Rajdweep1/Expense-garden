package com.expensegarden.app.data

import androidx.room.withTransaction
import com.expensegarden.app.sync.SyncClock

/** Budget writes (spec §2.5).
 *
 *  This logic used to live in DashboardViewModel, which was the only ViewModel in the app
 *  reaching into a DAO to write. It moves here because sync needs one choke point, and
 *  because two of the five statements that can silently forget a stamp lived there.
 *
 *  The edit-versus-clear distinction is the part that matters for the protocol. `setBudget`
 *  is still delete-then-insert underneath — that is how the unique index is respected — but
 *  an edit must NOT emit a tombstone. Budgets sync on the natural key (categoryId, month),
 *  so an edit is a plain upsert; a tombstone would race the new row on the server and could
 *  delete the budget the user just set. */
class BudgetRepository(
    private val db: AppDatabase,
    private val clock: SyncClock = SyncClock.inMemory(),
    private val onChanged: () -> Unit = {},
) {
    suspend fun setBudget(categoryId: Long?, month: String, amountPaise: Long?) {
        db.withTransaction {
            if (categoryId == null) db.budgetDao().deleteOverallForMonth(month)
            else db.budgetDao().deleteForCategory(categoryId, month)

            if (amountPaise != null && amountPaise > 0) {
                db.budgetDao().insert(
                    BudgetEntity(
                        categoryId = categoryId,
                        month = month,
                        amountPaise = amountPaise,
                        updatedAt = clock.next(),
                    )
                )
                // An edit, not a clear: drop any tombstone left by an earlier clear of this
                // same key, or an unpushed one would delete the row we just wrote.
                db.syncDao().deleteTombstone("budget", rowKey(categoryId, month))
            } else {
                db.syncDao().putTombstone(
                    SyncTombstoneEntity("budget", rowKey(categoryId, month), deletedAt = clock.next())
                )
            }
        }
        onChanged()
    }

    companion object {
        /** Spec §2.1: "<categoryId or *>|<month>". The sentinel is explicit because an empty
         *  segment could not be told apart from a malformed key. */
        fun rowKey(categoryId: Long?, month: String): String = "${categoryId ?: "*"}|$month"
    }
}
