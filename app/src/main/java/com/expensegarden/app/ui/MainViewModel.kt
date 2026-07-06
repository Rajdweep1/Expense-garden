package com.expensegarden.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.expensegarden.app.AppContainer
import com.expensegarden.app.capture.UpiPayee
import com.expensegarden.app.core.Money
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.LedgerRepository
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.gate.GateEvaluator
import com.expensegarden.app.gate.Severity
import com.expensegarden.app.stats.ChipOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EntryDraft(
    val fromScan: Boolean = false,
    val vpa: String? = null,
    val payeeName: String = "",
    val amountText: String = "",
    val categoryId: Long? = null,
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
)

data class GatePrompt(val severity: Severity, val quip: String, val scopeLabel: String?)

/** Home header: null while Room's first emission is in flight (loading skeleton). */
data class HomeHeader(val spentPaise: Long, val overallBudgetPaise: Long?, val hint: Severity)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val ledger = container.ledger

    val draft = MutableStateFlow(EntryDraft())

    val categories: StateFlow<List<CategoryEntity>> =
        container.db.categoryDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** flow{} wrapper: a re-subscription (WhileSubscribed restart) re-derives the month key —
     *  bounds frozen at property init went stale across month boundaries (spec §5 fix). */
    val homeHeader: StateFlow<HomeHeader?> =
        flow {
            val monthKey = ledger.currentMonthKey()   // fresh on every (re)subscription
            emitAll(
                combine(
                    ledger.observeMonthSpent(monthKey),
                    container.db.budgetDao().observeAllForMonth(monthKey),
                ) { spent, budgets ->
                    val overall = budgets.firstOrNull { it.categoryId == null }?.amountPaise
                    val (day, days) = ledger.today()
                    HomeHeader(spent, overall, GateEvaluator.evaluate(spent, overall, 0L, day, days))
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val chipCategories: StateFlow<List<CategoryEntity>> =
        combine(
            container.db.categoryDao().observeAll(),
            container.db.transactionDao().observeCategoryUsageSince(
                System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            ),
        ) { cats, usage ->
            ChipOrder.topChips(cats, usage.associate { it.categoryId to it.uses })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pendingConfirm: StateFlow<List<TransactionEntity>> =
        ledger.observePendingConfirm()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recent: Flow<List<TxnRow>> = ledger.observeRecent()

    fun startScanDraft(payee: UpiPayee) {
        draft.value = EntryDraft(
            fromScan = true,
            vpa = payee.vpa,
            payeeName = payee.name ?: payee.vpa.substringBefore('@'),
            amountText = payee.amountPaise?.let { Money.intentAmount(it) } ?: "",
        )
        viewModelScope.launch { prefillCategoryFromPayee(payee.vpa) }
    }

    fun startManualDraft() {
        draft.value = EntryDraft(fromScan = false)
    }

    /** Compute severity + quip across all budget scopes. OK never shows a dialog (silence rule at the gate). */
    suspend fun prepareGate(amountPaise: Long): GatePrompt {
        val d = draft.value
        val verdict = ledger.evaluateGate(d.categoryId!!, amountPaise, d.occurredAt)
        val quip = if (verdict.severity == Severity.OK) "" else container.quips.pick(verdict.severity)
        val label = verdict.offender?.takeIf { it.categoryId != null }?.label
        return GatePrompt(verdict.severity, quip, label)
    }

    /** QR path: persist pending + record breach flag; caller then fires the intent. */
    suspend fun savePendingFromDraft(amountPaise: Long, severity: Severity): String =
        ledger.savePending(
            draft = draft.value.toRepoDraft(amountPaise),
            breachedAtLogging = severity == Severity.BREACH,
        )

    fun saveManualFromDraft(amountPaise: Long) {
        val d = draft.value
        viewModelScope.launch {
            // Same scope evaluation as the gate, run silently against the txn's own month (backdating).
            val verdict = ledger.evaluateGate(d.categoryId!!, amountPaise, d.occurredAt)
            ledger.saveManualLogged(d.toRepoDraft(amountPaise), breachedAtLogging = verdict.severity == Severity.BREACH)
        }
    }

    fun setRegret(uuid: String, value: Regret) = viewModelScope.launch { ledger.setRegret(uuid, value) }

    fun setDraftDate(epochMillis: Long) {
        draft.value = draft.value.copy(occurredAt = epochMillis)
    }

    fun recordDodge(amountPaise: Long) {
        val categoryId = draft.value.categoryId ?: return
        viewModelScope.launch { ledger.recordGateDodge(amountPaise, categoryId) }
    }

    fun confirmPending(uuid: String) = viewModelScope.launch { ledger.confirm(uuid) }
    fun discardPending(uuid: String) = viewModelScope.launch { ledger.discard(uuid) }

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
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    }
}
