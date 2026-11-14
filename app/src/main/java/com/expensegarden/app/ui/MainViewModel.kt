package com.expensegarden.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.expensegarden.app.AppContainer
import com.expensegarden.app.capture.AwaitingPayment
import com.expensegarden.app.capture.AwaitingSelect
import com.expensegarden.app.capture.UpiPayee
import com.expensegarden.app.capture.UpiQr
import com.expensegarden.app.core.Money
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.LedgerRepository
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import com.expensegarden.app.gate.GateEvaluator
import com.expensegarden.app.gate.EstimateSource
import com.expensegarden.app.gate.PrePayCheck
import com.expensegarden.app.gate.PrePayDecision
import com.expensegarden.app.gate.PrePayInput
import com.expensegarden.app.gate.Severity
import com.expensegarden.app.stats.ChipOrder
import com.expensegarden.app.stats.RecurringPayees
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

data class EntryDraft(
    val vpa: String? = null,
    val payeeName: String = "",
    val amountText: String = "",
    val categoryId: Long? = null,
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    /** The scanned QR, kept to pay a merchant with exactly what was scanned. Null on the manual path. */
    val qrUri: String? = null,
    /** Non-null: the after-payment form for this awaiting payment (pay-first). Null: manual. */
    val awaitingId: String? = null,
)

/** What the scan's pre-payment check decided, with what the hand-off and the card need. */
data class PrePayResult(
    val payee: UpiPayee,
    val decision: PrePayDecision,
    val estimatePaise: Long,
    val categoryId: Long?,
    val quip: String,
)

/** Home header: null while Room's first emission is in flight (loading skeleton). */
data class HomeHeader(val spentPaise: Long, val overallBudgetPaise: Long?, val hint: Severity)

/** The last money action that Undo can take back. */
sealed interface UndoOffer {
    val message: String
    /** Move [uuid] from [from] back to [to]. */
    data class Txn(override val message: String, val uuid: String, val from: TxnStatus, val to: TxnStatus) : UndoOffer
    /** "Didn't pay": put the awaiting payment back, as a home card (plan Q2). */
    data class Awaiting(override val message: String, val payment: AwaitingPayment) : UndoOffer
    /** An after-pay Log it: discard the row and put the payment back as a home card, under a new id
     *  (the old one now names the discarded row), so a mistyped amount can be logged again. */
    data class LoggedScan(override val message: String, val uuid: String, val payment: AwaitingPayment) : UndoOffer
}

class MainViewModel(private val container: AppContainer, private val saved: SavedStateHandle) : ViewModel() {
    private val ledger = container.ledger
    private val zone: ZoneId = ZoneId.systemDefault()

    /** Restored after process death: Android kills a backgrounded app freely, and a half-typed
     *  entry, or a scanned payee, used to vanish with it (review, 2026-10-11). Every change is
     *  written through to [saved], which the system persists with the activity. */
    val draft = MutableStateFlow(DraftState.restore(saved) ?: EntryDraft())

    init {
        viewModelScope.launch { draft.collect { DraftState.save(saved, it) } }
    }

    val categories: StateFlow<List<CategoryEntity>> =
        container.db.categoryDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** flow{} wrapper: a re-subscription (WhileSubscribed restart) re-derives the month key —
     *  bounds frozen at property init went stale across month boundaries (spec §5 fix). */
    val homeHeader: StateFlow<HomeHeader?> =
        flow {
            val monthKey = ledger.currentMonthKey()   // fresh on every (re)subscription
            val ym = YearMonth.parse(monthKey)
            val (from, to) = ledger.boundsOfMonth(monthKey)
            val lookBackFrom = RecurringPayees.windowStart(ym, zone)
            val began = container.garden.ledgerBeganAt()   // once per (re)subscription
            emitAll(
                combine(
                    ledger.observeMonthSpent(monthKey),
                    container.db.budgetDao().observeAllForMonth(monthKey),
                    // The strip's hint must agree with the dashboard's severity, so it needs the
                    // same fixed-cost exclusion. Observed rather than read once: a suspend read
                    // here would go stale the moment a transaction landed.
                    container.db.transactionDao().observeLoggedBetween(lookBackFrom, to),
                ) { spent, budgets, window ->
                    val overall = budgets.firstOrNull { it.categoryId == null }?.amountPaise
                    val (day, days) = ledger.today()
                    val caps = RecurringPayees.detect(window, ym, zone, began)
                    val fixed = RecurringPayees.fixedSpentPaise(window.filter { it.occurredAt in from..to }, caps)
                    HomeHeader(spent, overall, GateEvaluator.evaluate(spent, overall, 0L, fixed, day, days))
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** flow{} wrapper for the same reason [homeHeader] has one: the 90-day window was
     *  evaluated once, at property init, so it drifted on a long-lived process. Same staleness
     *  class the spec §5 fix addressed for month bounds — this property was simply missed by
     *  it. Re-derived on every (re)subscription. */
    val chipCategories: StateFlow<List<CategoryEntity>> =
        flow {
            val since = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            emitAll(
                combine(
                    container.db.categoryDao().observeAll(),
                    container.db.transactionDao().observeCategoryUsageSince(since),
                ) { cats, usage ->
                    ChipOrder.topChips(cats, usage.associate { it.categoryId to it.uses })
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pendingConfirm: StateFlow<List<TxnRow>> =
        ledger.observePendingRows()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recent: Flow<List<TxnRow>> = ledger.observeRecent()

    fun startManualDraft() {
        draft.value = EntryDraft()
    }

    private val _awaiting = MutableStateFlow(container.awaiting.all())
    /** Payments handed to a UPI app and not logged yet (pay-first): home cards and the strip. */
    val awaiting: StateFlow<List<AwaitingPayment>> = _awaiting

    /** SharedPreferences can't be observed, so every write here, and every resume, re-reads it. */
    fun refreshAwaiting() {
        _awaiting.value = container.awaiting.all()
    }

    /** The scan's one decision before paying (pay-first spec): the card only when the month, with
     *  this payment's best guess (D1), is already over — and never for necessities, fixed payees
     *  or with a quip/dodge for unknown payees (D5, D6). */
    suspend fun prePay(payee: UpiPayee): PrePayResult {
        val monthKey = ledger.currentMonthKey()
        container.budgets.carryForward(monthKey)
        val fixedAmount = UpiQr.fixedAmountPaise(payee.raw)
        val last = if (fixedAmount == null) ledger.lastAmountTo(payee.vpa) else null
        val (estimate, source) = when {
            fixedAmount != null -> fixedAmount to EstimateSource.QR_FIXED
            last != null -> last to EstimateSource.LAST_LOGGED
            else -> 0L to EstimateSource.NONE
        }
        val known = container.db.payeeDao().byVpa(payee.vpa)
        val categoryId = known?.defaultCategoryId
        val began = container.garden.ledgerBeganAt()
        val verdict = ledger.evaluateGate(categoryId, estimate, System.currentTimeMillis(), began, payee.vpa)
        val decision = PrePayCheck.decide(
            PrePayInput(
                verdict = verdict, estimatePaise = estimate, estimateSource = source,
                payeeKnown = categoryId != null,
                isNecessity = categoryId?.let { ledger.isNecessity(it) } ?: false,
                isFixedPayee = known != null && ledger.recurringCaps(monthKey, began).containsKey(known.id),
            )
        )
        // Picked only when shown: QuipRepository.pick spends one from the no-repeat rotation.
        val quip = if (decision is PrePayDecision.Card && decision.withQuip)
            container.quips.pick(decision.severity, container.aiPrefs.tone) else ""
        return PrePayResult(payee, decision, estimate, categoryId, quip)
    }

    /** Written (to disk) immediately before the UPI app opens, so a kill while paying keeps it
     *  (plan Q1). Null when it couldn't be written — then nothing may open. The caller removes it
     *  with [cancelHandoff] when nothing opened. */
    fun recordHandoff(r: PrePayResult): AwaitingPayment? {
        val now = System.currentTimeMillis()
        val p = AwaitingPayment(
            id = UUID.randomUUID().toString(), vpa = r.payee.vpa,
            payeeName = r.payee.name ?: r.payee.vpa.substringBefore('@'), qrUri = r.payee.raw,
            fixedAmountPaise = UpiQr.fixedAmountPaise(r.payee.raw), scannedAt = now, handedOffAt = now,
        )
        val saved = container.awaiting.handOff(p)
        refreshAwaiting()
        return p.takeIf { saved }
    }

    fun cancelHandoff(id: String) {
        container.awaiting.remove(id)
        refreshAwaiting()
    }

    /** "Not now" on the pre-payment card. */
    fun recordDodge(r: PrePayResult) = viewModelScope.launch {
        ledger.recordGateDodge(r.estimatePaise.takeIf { it > 0 }, r.categoryId)
    }

    /** The payment whose Log it form opens on this resume, its marker cleared (spec D8). */
    fun takePaymentToOpen(): String? {
        val p = AwaitingSelect.toOpen(container.awaiting.all()) ?: return null
        container.awaiting.markShown(p.id)
        refreshAwaiting()
        return p.id
    }

    /** Only when the form isn't already this payment's: a restore or recomposition must not wipe
     *  a typed amount (plan Q12). */
    fun startAfterPayDraft(id: String) {
        if (draft.value.awaitingId == id) return
        val p = container.awaiting.all().firstOrNull { it.id == id } ?: return
        draft.value = EntryDraft(
            vpa = p.vpa, payeeName = p.payeeName,
            amountText = p.fixedAmountPaise?.let { Money.inputText(it) } ?: "",
            occurredAt = p.scannedAt, qrUri = p.qrUri, awaitingId = p.id,
        )
        viewModelScope.launch { prefillCategoryFromPayee(p.vpa) }
    }

    /** Log it: once (the awaiting id is the uuid), judged at logging time like a manual log —
     *  the payment that crosses the budget grows its weed (spec D2). */
    fun logAfterPayment(amountPaise: Long) {
        val d = draft.value
        val id = d.awaitingId ?: return
        viewModelScope.launch {
            container.budgets.carryForward(ledger.currentMonthKey())
            val verdict = ledger.evaluateGate(d.categoryId, amountPaise, d.occurredAt, container.garden.ledgerBeganAt(), d.vpa)
            val repoDraft = d.toRepoDraft(amountPaise)
            val logged = ledger.saveLoggedOnce(repoDraft, TxnSource.QR_GATE, breached = verdict.severity == Severity.BREACH, uuid = id)
            val payment = container.awaiting.remove(id)
            refreshAwaiting()
            // Already logged (killed between the save and the removal): nothing new was written,
            // so there is nothing to announce or undo (code review, 2026-11-14).
            if (logged && payment != null) {
                _undoOffer.value = UndoOffer.LoggedScan(PendingLabels.logged(amountPaise, repoDraft.payeeName), id, payment)
            }
        }
    }

    /** Didn't pay: nothing is written; Undo brings the payment back as a home card. */
    fun didntPay() {
        val id = draft.value.awaitingId ?: return
        val p = container.awaiting.remove(id) ?: return
        refreshAwaiting()
        _undoOffer.value = UndoOffer.Awaiting(PendingLabels.dropped(p.payeeName), p)
    }

    private val _undoOffer = MutableStateFlow<UndoOffer?>(null)
    /** The last undoable money action, for home's snackbar. */
    val undoOffer: StateFlow<UndoOffer?> = _undoOffer

    fun applyUndo(offer: UndoOffer) = viewModelScope.launch {
        when (offer) {
            is UndoOffer.Txn -> ledger.revert(offer.uuid, offer.from, offer.to)
            is UndoOffer.Awaiting -> {
                container.awaiting.restore(offer.payment)
                refreshAwaiting()
            }
            is UndoOffer.LoggedScan -> if (ledger.revert(offer.uuid, TxnStatus.LOGGED, TxnStatus.DISCARDED)) {
                container.awaiting.restore(offer.payment.copy(id = UUID.randomUUID().toString()))
                refreshAwaiting()
            }
        }
    }

    /** Clears only [offer]: a newer one that arrived meanwhile keeps its own bar. */
    fun dismissUndo(offer: UndoOffer) {
        _undoOffer.compareAndSet(offer, null)
    }

    fun saveManualFromDraft(amountPaise: Long) {
        val d = draft.value
        viewModelScope.launch {
            container.budgets.carryForward(ledger.currentMonthKey())
            // Same scope evaluation as the gate, run silently against the txn's own month (backdating).
            val verdict = ledger.evaluateGate(d.categoryId!!, amountPaise, d.occurredAt, container.garden.ledgerBeganAt(), d.vpa)
            val repoDraft = d.toRepoDraft(amountPaise)
            val uuid = ledger.saveManualLogged(repoDraft, breachedAtLogging = verdict.severity == Severity.BREACH)
            _undoOffer.value = UndoOffer.Txn(
                PendingLabels.logged(amountPaise, repoDraft.payeeName), uuid, TxnStatus.LOGGED, TxnStatus.DISCARDED,
            )
        }
    }

    fun setRegret(uuid: String, value: Regret) = viewModelScope.launch { ledger.setRegret(uuid, value) }

    fun setDraftDate(epochMillis: Long) {
        draft.value = draft.value.copy(occurredAt = epochMillis)
    }

    fun confirmPending(row: TxnRow) = viewModelScope.launch {
        if (ledger.confirm(row.uuid)) {
            _undoOffer.value = UndoOffer.Txn(
                PendingLabels.logged(row.amountPaise, row.payeeName), row.uuid, TxnStatus.LOGGED, TxnStatus.PENDING_CONFIRM,
            )
        }
    }

    fun discardPending(row: TxnRow) = viewModelScope.launch {
        if (ledger.discard(row.uuid)) {
            _undoOffer.value = UndoOffer.Txn(
                PendingLabels.discarded(row.amountPaise, row.payeeName), row.uuid, TxnStatus.DISCARDED, TxnStatus.PENDING_CONFIRM,
            )
        }
    }

    private suspend fun prefillCategoryFromPayee(vpa: String) {
        val known = container.db.payeeDao().byVpa(vpa) ?: return
        known.defaultCategoryId?.let { catId -> draft.value = draft.value.copy(categoryId = catId) }
    }

    private fun EntryDraft.toRepoDraft(amountPaise: Long) = LedgerRepository.Draft(
        vpa = vpa,
        payeeName = payeeName.ifBlank { vpa ?: "Unknown" },
        amountPaise = amountPaise,
        categoryId = categoryId!!,
        note = note.ifBlank { null },
        occurredAt = occurredAt,
    )

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                MainViewModel(container, extras.createSavedStateHandle()) as T
        }
    }
}
