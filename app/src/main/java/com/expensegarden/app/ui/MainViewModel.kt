package com.expensegarden.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.expensegarden.app.AppContainer
import com.expensegarden.app.capture.UpiPayee
import com.expensegarden.app.core.Money
import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.LedgerRepository
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.gate.GateEvaluator
import com.expensegarden.app.gate.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

data class GatePrompt(val severity: Severity, val quip: String)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val ledger = container.ledger

    val draft = MutableStateFlow(EntryDraft())

    val categories: StateFlow<List<CategoryEntity>> =
        container.db.categoryDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val monthSpent: StateFlow<Long> =
        ledger.observeMonthSpent(ledger.currentMonthKey())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val monthBudget: StateFlow<BudgetEntity?> =
        container.db.budgetDao().observeOverallForMonth(ledger.currentMonthKey())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
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

    /** Compute severity + quip. OK never shows a dialog (silence rule at the gate). */
    suspend fun prepareGate(amountPaise: Long): GatePrompt {
        val spent = ledger.monthSpentPaise()
        val budget = container.db.budgetDao().overallForMonth(ledger.currentMonthKey())?.amountPaise
        val (day, days) = ledger.today()
        val severity = GateEvaluator.evaluate(spent, budget, amountPaise, day, days)
        val quip = if (severity == Severity.OK) "" else container.quips.pick(severity)
        return GatePrompt(severity, quip)
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
            val spent = ledger.monthSpentPaise()
            val budget = container.db.budgetDao().overallForMonth(ledger.currentMonthKey())?.amountPaise
            val (day, days) = ledger.today()
            val severity = GateEvaluator.evaluate(spent, budget, amountPaise, day, days)
            ledger.saveManualLogged(d.toRepoDraft(amountPaise), breachedAtLogging = severity == Severity.BREACH)
        }
    }

    fun recordDodge(amountPaise: Long) {
        val categoryId = draft.value.categoryId ?: return
        viewModelScope.launch { ledger.recordGateDodge(amountPaise, categoryId) }
    }

    fun confirmPending(uuid: String) = viewModelScope.launch { ledger.confirm(uuid) }
    fun discardPending(uuid: String) = viewModelScope.launch { ledger.discard(uuid) }

    fun setOverallBudget(amountPaise: Long?) {
        viewModelScope.launch {
            val month = ledger.currentMonthKey()
            container.db.withTransaction {
                container.db.budgetDao().deleteOverallForMonth(month)
                if (amountPaise != null) {
                    container.db.budgetDao().insert(BudgetEntity(categoryId = null, month = month, amountPaise = amountPaise))
                }
            }
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
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    }
}
