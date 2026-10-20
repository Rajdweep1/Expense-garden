package com.expensegarden.app.data

import androidx.room.withTransaction
import com.expensegarden.app.gate.GateAggregator
import com.expensegarden.app.gate.GateEvaluator
import com.expensegarden.app.gate.GateVerdict
import com.expensegarden.app.gate.ScopeInput
import com.expensegarden.app.stats.CategoryTree
import com.expensegarden.app.stats.RecurringPayees
import com.expensegarden.app.sync.SyncClock
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class LedgerRepository(
    private val db: AppDatabase,
    private val clock: SyncClock = SyncClock.inMemory(),
    private val onChanged: () -> Unit = {},
) {
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
    suspend fun savePending(draft: Draft, breachedAtLogging: Boolean, uuid: String? = null): String =
        save(draft, TxnSource.QR_GATE, TxnStatus.PENDING_CONFIRM, breachedAtLogging, uuid)

    /** The QR path's save, once per scan: false, writing nothing, when [uuid] already exists. A
     *  double tap, or a retry after a rotation killed the first attempt mid-flight, used to insert
     *  the same uuid again and crash mid-payment; the caller now also knows not to open the UPI
     *  app a second time. Check and insert share one transaction. */
    suspend fun savePendingOnce(draft: Draft, breachedAtLogging: Boolean, uuid: String): Boolean =
        db.withTransaction {
            if (db.transactionDao().byUuid(uuid) != null) false
            else {
                savePending(draft, breachedAtLogging, uuid)
                true
            }
        }

    /** Manual path: post-hoc, money already spent — straight to LOGGED (spec §5.1). */
    suspend fun saveManualLogged(draft: Draft, breachedAtLogging: Boolean): String =
        save(draft, TxnSource.MANUAL, TxnStatus.LOGGED, breachedAtLogging)

    /** "It went through." Only a still-pending row moves: a second tap changes nothing, and
     *  emits no second transaction.logged or crossing events. @return whether it moved. */
    suspend fun confirm(uuid: String): Boolean {
        val moved = db.withTransaction {
            val changed = db.transactionDao().transition(uuid, TxnStatus.PENDING_CONFIRM, TxnStatus.LOGGED, clock.next()) > 0
            if (changed) {
                db.gameEventDao().insert(loggedEvent(uuid))
                db.transactionDao().byUuid(uuid)?.let { emitCrossings(it) }
            }
            changed
        }
        if (moved) onChanged()
        return moved
    }

    /** "It didn't." Only a still-pending row moves. @return whether it moved. */
    suspend fun discard(uuid: String): Boolean =
        revert(uuid, TxnStatus.PENDING_CONFIRM, TxnStatus.DISCARDED)

    /** One status change, applied only if the row is still [from] — what Undo uses (LOGGED →
     *  DISCARDED for a manual log, LOGGED or DISCARDED → PENDING_CONFIRM for the pending card).
     *  Events stay appended: the garden folds rows, so an undone log simply stops growing a plant.
     *  @return whether it moved. */
    suspend fun revert(uuid: String, from: TxnStatus, to: TxnStatus): Boolean {
        val moved = db.transactionDao().transition(uuid, from, to, clock.next()) > 0
        if (moved) onChanged()
        return moved
    }

    /** Regret is re-taggable; only transitions touching REGRET leave history (spec §4).
     *  Never punishes the log — this feeds garden rendering only. */
    suspend fun setRegret(uuid: String, value: Regret) {
        db.withTransaction {
            val txn = db.transactionDao().byUuid(uuid) ?: return@withTransaction
            if (txn.regret == value) return@withTransaction
            db.transactionDao().setRegret(uuid, value, updatedAt = clock.next())
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
        onChanged()
    }

    /** User backed out at the gate — record the dodge; the game rewards it later (1C). */
    suspend fun recordGateDodge(amountPaise: Long, categoryId: Long) {
        val payload = JSONObject().put("amountPaise", amountPaise).put("categoryId", categoryId)
        db.gameEventDao().insert(
            GameEventEntity(type = "gate.dodged", payloadJson = payload.toString(), transactionUuid = null, createdAt = now())
        )
        onChanged()
    }

    fun observePendingRows(): Flow<List<TxnRow>> = db.transactionDao().observePendingRows()
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

    /** Each recurring payee's fixed cap in [monthKey] (RecurringPayees.detect over its
     *  look-back window). Shared by the gate's fixed total and the streak's per-day fixed spend,
     *  so the two can't disagree about what rent is. */
    suspend fun recurringCaps(monthKey: String, ledgerBeganAt: Long?): Map<Long, Long> {
        val ym = YearMonth.parse(monthKey)
        val lookBackFrom =
            boundsOfMonth(ym.minusMonths(RecurringPayees.LOOK_BACK_MONTHS.toLong()).toString()).first
        val (_, to) = boundsOfMonth(monthKey)
        return RecurringPayees.detect(db.transactionDao().loggedBetween(lookBackFrom, to), ym, zone, ledgerBeganAt)
    }

    /** Worst severity across scopes, evaluated in the month the txn belongs to (spec §3: backdating).
     *  @param ledgerBeganAt see StreakBaseline.ledgerBeganAt. Required: this repository has no
     *    GardenPrefs of its own to look it up, so every caller has to bring it.
     *  @param payeeVpa who is being paid, when the payment has a VPA. Required: a payment to a
     *    recurring payee takes what is left of its fixed cap the moment it is logged, and leaving
     *    that out of the fixed term here made the gate contradict the strip (final review,
     *    2026-10-19). The manual path passes its null VPA; it reads only BREACH, which no fixed
     *    term moves. */
    suspend fun evaluateGate(
        categoryId: Long,
        amountPaise: Long,
        occurredAt: Long,
        ledgerBeganAt: Long?,
        payeeVpa: String?,
    ): GateVerdict {
        val (day, days) = dayAndLengthOf(occurredAt)
        val monthKey = monthKeyOf(occurredAt)
        val (from, to) = boundsOfMonth(monthKey)
        val monthTxns = db.transactionDao().loggedBetween(from, to)
        val caps = recurringCaps(monthKey, ledgerBeganAt)
        val payeeId = payeeVpa?.let { db.payeeDao().byVpa(it)?.id }
        // Fixed costs leave the overall pace line, so a day-1 rent payment doesn't raise a dialog on
        // nearly every purchase for three weeks (review, 2026-09-06). Read from the payment's own
        // month, so a backdated payment isn't paced against rent it never saw. The payment itself
        // takes what is left of its payee's cap: the share RecurringPayees.fixedShares gives it once
        // logged, since a payment dated now fills last.
        val fixed = RecurringPayees.fixedSpentPaise(monthTxns, caps)
        val capLeft = payeeId?.let { id ->
            caps[id]?.let { cap -> (cap - monthTxns.filter { it.payeeId == id }.sumOf { it.amountPaise }).coerceAtLeast(0L) }
        } ?: 0L
        return GateAggregator.aggregate(
            scopeInputs(categoryId, occurredAt), amountPaise, fixed, day, days,
            candidateFixedPaise = minOf(amountPaise, capLeft),
        )
    }

    /** Whether the gate treats [categoryId] as a necessity, read from the database. It used to be
     *  read from a UI StateFlow, which is empty until Room's first emission and would then have
     *  answered "not a necessity": a quip and a dodge credit for a chemist run (review, 2026-10-11). */
    suspend fun isNecessity(categoryId: Long): Boolean =
        db.categoryDao().byId(categoryId)?.isNecessity ?: false

    private suspend fun save(
        draft: Draft,
        source: TxnSource,
        status: TxnStatus,
        breached: Boolean,
        uuidOverride: String? = null,
    ): String {
        // The QR path mints this before the gate so the dialog can preview the exact weed this
        // transaction will grow — which weed is a pure function of the uuid (PlantMapper.kt:74).
        // Every other caller still gets a fresh one here.
        val uuid = uuidOverride ?: UUID.randomUUID().toString()
        db.withTransaction {
            val payeeId = resolvePayee(draft)
            db.transactionDao().insert(
                TransactionEntity(
                    uuid = uuid, amountPaise = draft.amountPaise, payeeId = payeeId,
                    categoryId = draft.categoryId, source = source, status = status,
                    breachedAtLogging = breached, note = draft.note,
                    occurredAt = draft.occurredAt, createdAt = now(), updatedAt = clock.next(),
                )
            )
            db.payeeDao().setDefaultCategory(payeeId, draft.categoryId, updatedAt = clock.next())   // payee->category map learns
            if (status == TxnStatus.LOGGED) {
                db.gameEventDao().insert(loggedEvent(uuid))
                db.transactionDao().byUuid(uuid)?.let { emitCrossings(it) }
            }
        }
        onChanged()
        return uuid
    }

    private suspend fun resolvePayee(draft: Draft): Long {
        val existing = if (draft.vpa != null) db.payeeDao().byVpa(draft.vpa)
                       else db.payeeDao().cashPayeeByName(draft.payeeName)
        if (existing != null) return existing.id
        return db.payeeDao().insert(
            PayeeEntity(name = draft.payeeName, vpa = draft.vpa, defaultCategoryId = draft.categoryId, updatedAt = clock.next())
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
