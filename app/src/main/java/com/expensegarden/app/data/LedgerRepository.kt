package com.expensegarden.app.data

import androidx.room.withTransaction
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class LedgerRepository(private val db: AppDatabase) {
    private val zone: ZoneId = ZoneId.systemDefault()

    data class Draft(
        val vpa: String?,            // null => cash/manual payee
        val payeeName: String,
        val amountPaise: Long,
        val categoryId: Long,
        val note: String?,
        val occurredAt: Long,
    )

    /** QR path: saved as PENDING_CONFIRM before the UPI intent fires. Returns txn uuid. */
    suspend fun savePending(draft: Draft, breachedAtLogging: Boolean): String =
        save(draft, TxnSource.QR_GATE, TxnStatus.PENDING_CONFIRM, breachedAtLogging)

    /** Manual path: post-hoc, money already spent — straight to LOGGED (spec §5.1). */
    suspend fun saveManualLogged(draft: Draft, breachedAtLogging: Boolean): String =
        save(draft, TxnSource.MANUAL, TxnStatus.LOGGED, breachedAtLogging)

    suspend fun confirm(uuid: String) {
        db.withTransaction {
            db.transactionDao().setStatus(uuid, TxnStatus.LOGGED)
            db.gameEventDao().insert(loggedEvent(uuid))
        }
    }

    suspend fun discard(uuid: String) = db.transactionDao().setStatus(uuid, TxnStatus.DISCARDED)

    /** User backed out at the gate — record the dodge; the game rewards it later (1C). */
    suspend fun recordGateDodge(amountPaise: Long, categoryId: Long) {
        val payload = JSONObject().put("amountPaise", amountPaise).put("categoryId", categoryId)
        db.gameEventDao().insert(
            GameEventEntity(type = "gate.dodged", payloadJson = payload.toString(), transactionUuid = null, createdAt = now())
        )
    }

    fun observePendingConfirm(): Flow<List<TransactionEntity>> = db.transactionDao().observePendingConfirm()
    fun observeRecent(): Flow<List<TxnRow>> = db.transactionDao().observeRecent()
    fun observeMonthSpent(): Flow<Long> {
        val (from, to) = currentMonthBounds()
        return db.transactionDao().observeLoggedSumBetween(from, to)
    }

    suspend fun monthSpentPaise(): Long {
        val (from, to) = currentMonthBounds()
        return db.transactionDao().loggedSumBetween(from, to)
    }

    fun currentMonthKey(): String = YearMonth.now(zone).toString()          // "2026-07"
    fun today(): Pair<Int, Int> {
        val d = LocalDate.now(zone)
        return d.dayOfMonth to d.lengthOfMonth()
    }

    private suspend fun save(draft: Draft, source: TxnSource, status: TxnStatus, breached: Boolean): String {
        val uuid = UUID.randomUUID().toString()
        db.withTransaction {
            val payeeId = resolvePayee(draft)
            db.transactionDao().insert(
                TransactionEntity(
                    uuid = uuid, amountPaise = draft.amountPaise, payeeId = payeeId,
                    categoryId = draft.categoryId, source = source, status = status,
                    breachedAtLogging = breached, note = draft.note,
                    occurredAt = draft.occurredAt, createdAt = now(),
                )
            )
            db.payeeDao().setDefaultCategory(payeeId, draft.categoryId)      // payee->category map learns
            if (status == TxnStatus.LOGGED) db.gameEventDao().insert(loggedEvent(uuid))
        }
        return uuid
    }

    private suspend fun resolvePayee(draft: Draft): Long {
        val existing = if (draft.vpa != null) db.payeeDao().byVpa(draft.vpa)
                       else db.payeeDao().cashPayeeByName(draft.payeeName)
        if (existing != null) return existing.id
        return db.payeeDao().insert(
            PayeeEntity(name = draft.payeeName, vpa = draft.vpa, defaultCategoryId = draft.categoryId)
        )
    }

    private fun loggedEvent(uuid: String): GameEventEntity =
        GameEventEntity(
            type = "transaction.logged",
            payloadJson = JSONObject().put("uuid", uuid).toString(),
            transactionUuid = uuid,
            createdAt = now(),
        )

    private fun currentMonthBounds(): Pair<Long, Long> {
        val ym = YearMonth.now(zone)
        val from = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return from to to
    }

    private fun now(): Long = System.currentTimeMillis()
}
