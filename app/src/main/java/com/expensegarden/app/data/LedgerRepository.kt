package com.expensegarden.app.data

import androidx.room.withTransaction
import com.expensegarden.app.gate.GateAggregator
import com.expensegarden.app.gate.GateEvaluator
import com.expensegarden.app.gate.GateVerdict
import com.expensegarden.app.gate.ScopeInput
import com.expensegarden.app.stats.CategoryTree
import org.json.JSONObject
import java.time.Instant
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
            db.transactionDao().byUuid(uuid)?.let { emitCrossings(it) }
        }
    }

    suspend fun discard(uuid: String) = db.transactionDao().setStatus(uuid, TxnStatus.DISCARDED)

    /** Regret is re-taggable; only transitions touching REGRET leave history (spec §4).
     *  Never punishes the log — this feeds garden rendering only. */
    suspend fun setRegret(uuid: String, value: Regret) {
        db.withTransaction {
            val txn = db.transactionDao().byUuid(uuid) ?: return@withTransaction
            if (txn.regret == value) return@withTransaction
            db.transactionDao().setRegret(uuid, value)
            if (value == Regret.REGRET) {
                val payload = JSONObject().put("uuid", uuid)
                    .put("categoryId", txn.categoryId).put("amountPaise", txn.amountPaise)
                db.gameEventDao().insert(GameEventEntity(
                    type = "transaction.regretted", payloadJson = payload.toString(),
                    transactionUuid = uuid, createdAt = now(),
                ))
            } else if (txn.regret == Regret.REGRET) {
                db.gameEventDao().insert(GameEventEntity(
                    type = "transaction.regret_cleared", payloadJson = JSONObject().put("uuid", uuid).toString(),
                    transactionUuid = uuid, createdAt = now(),
                ))
            }
        }
    }

    /** User backed out at the gate — record the dodge; the game rewards it later (1C). */
    suspend fun recordGateDodge(amountPaise: Long, categoryId: Long) {
        val payload = JSONObject().put("amountPaise", amountPaise).put("categoryId", categoryId)
        db.gameEventDao().insert(
            GameEventEntity(type = "gate.dodged", payloadJson = payload.toString(), transactionUuid = null, createdAt = now())
        )
    }

    fun observePendingConfirm(): Flow<List<TransactionEntity>> = db.transactionDao().observePendingConfirm()
    fun observeRecent(): Flow<List<TxnRow>> = db.transactionDao().observeRecent()
    fun observeMonthSpent(monthKey: String): Flow<Long> {
        val (from, to) = boundsOfMonth(monthKey)
        return db.transactionDao().observeLoggedSumBetween(from, to)
    }

    suspend fun monthSpentPaise(monthKey: String = currentMonthKey()): Long {
        val (from, to) = boundsOfMonth(monthKey)
        return db.transactionDao().loggedSumBetween(from, to)
    }

    fun currentMonthKey(): String = YearMonth.now(zone).toString()          // "2026-07"
    fun today(): Pair<Int, Int> {
        val d = LocalDate.now(zone)
        return d.dayOfMonth to d.lengthOfMonth()
    }

    fun monthKeyOf(epochMillis: Long): String =
        YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zone)).toString()

    fun boundsOfMonth(monthKey: String): Pair<Long, Long> {
        val ym = YearMonth.parse(monthKey)
        val from = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return from to to
    }

    fun dayAndLengthOf(epochMillis: Long): Pair<Int, Int> {
        val d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return d.dayOfMonth to d.lengthOfMonth()
    }

    /** Budget scopes relevant to a payment in [categoryId] during [occurredAt]'s month:
     *  overall (depth 0) + every budgeted category on the ancestor chain (deeper = more specific). */
    suspend fun scopeInputs(categoryId: Long, occurredAt: Long): List<ScopeInput> {
        val monthKey = monthKeyOf(occurredAt)
        val budgets = db.budgetDao().allForMonth(monthKey)
        if (budgets.isEmpty()) return emptyList()
        val tree = CategoryTree(db.categoryDao().all())
        val chain = tree.ancestorChain(categoryId)                       // [self, …, root]
        val (from, to) = boundsOfMonth(monthKey)
        val leafSums = db.transactionDao().loggedSumsByCategory(from, to)
            .associate { it.categoryId to it.totalPaise }
        val rolled = tree.rollupSums(leafSums)
        return budgets.mapNotNull { b ->
            when {
                b.categoryId == null -> ScopeInput(null, "overall", b.amountPaise, leafSums.values.sum(), depth = 0)
                b.categoryId in chain -> ScopeInput(
                    categoryId = b.categoryId,
                    label = tree.byId(b.categoryId)?.name ?: "?",
                    budgetPaise = b.amountPaise,
                    spentPaise = rolled[b.categoryId] ?: 0L,
                    depth = chain.size - chain.indexOf(b.categoryId),    // self deepest
                )
                else -> null
            }
        }
    }

    /** Worst severity across scopes, evaluated in the month the txn belongs to (spec §3: backdating). */
    suspend fun evaluateGate(categoryId: Long, amountPaise: Long, occurredAt: Long): GateVerdict {
        val (day, days) = dayAndLengthOf(occurredAt)
        return GateAggregator.aggregate(scopeInputs(categoryId, occurredAt), amountPaise, day, days)
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
            if (status == TxnStatus.LOGGED) {
                db.gameEventDao().insert(loggedEvent(uuid))
                db.transactionDao().byUuid(uuid)?.let { emitCrossings(it) }
            }
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

    /** Weather events for the live month only (spec §3/§4): emit when this txn moved a scope's
     *  spend from ≤ threshold to > threshold. Same-month dedup is free — a later txn starts past the line. */
    private suspend fun emitCrossings(txn: TransactionEntity) {
        val txnMonth = monthKeyOf(txn.occurredAt)
        if (txnMonth != currentMonthKey()) return
        val budgets = db.budgetDao().allForMonth(txnMonth)
        if (budgets.isEmpty()) return
        val tree = CategoryTree(db.categoryDao().all())
        val chain = tree.ancestorChain(txn.categoryId).toSet()
        val (from, to) = boundsOfMonth(txnMonth)
        val leafSums = db.transactionDao().loggedSumsByCategory(from, to).associate { it.categoryId to it.totalPaise }
        val rolled = tree.rollupSums(leafSums)
        val (day, days) = dayAndLengthOf(txn.occurredAt)

        for (b in budgets) {
            val affected = b.categoryId == null || b.categoryId in chain
            if (!affected) continue
            val after = if (b.categoryId == null) leafSums.values.sum() else rolled[b.categoryId] ?: 0L
            val before = after - txn.amountPaise
            if (before <= b.amountPaise && after > b.amountPaise) {
                db.gameEventDao().insert(crossingEvent("budget.breached", txnMonth, b, after, txn.uuid, allowancePaise = null))
                continue
            }
            val allowance = GateEvaluator.paceAllowancePaise(b.amountPaise, day, days)
            if (before <= allowance && after > allowance) {
                db.gameEventDao().insert(crossingEvent("budget.pace_warning", txnMonth, b, after, txn.uuid, allowancePaise = allowance))
            }
        }
    }

    private fun crossingEvent(
        type: String, month: String, budget: BudgetEntity, spentPaise: Long, txnUuid: String, allowancePaise: Long?,
    ): GameEventEntity {
        val payload = JSONObject()
            .put("month", month)
            .put("categoryId", budget.categoryId ?: JSONObject.NULL)
            .put("budgetPaise", budget.amountPaise)
            .put("spentPaise", spentPaise)
            .put("txnUuid", txnUuid)
        allowancePaise?.let { payload.put("allowancePaise", it) }
        return GameEventEntity(type = type, payloadJson = payload.toString(), transactionUuid = txnUuid, createdAt = now())
    }

    private fun loggedEvent(uuid: String): GameEventEntity =
        GameEventEntity(
            type = "transaction.logged",
            payloadJson = JSONObject().put("uuid", uuid).toString(),
            transactionUuid = uuid,
            createdAt = now(),
        )

    private fun now(): Long = System.currentTimeMillis()
}
