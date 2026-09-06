package com.expensegarden.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.expensegarden.app.AppContainer
import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.stats.MonthStats
import com.expensegarden.app.stats.MonthStatsFolder
import com.expensegarden.app.stats.RecurringPayees
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    private val ledger = container.ledger
    private val zone: ZoneId = ZoneId.systemDefault()

    /** null = loading (skeleton). flow{} wrapper: re-subscription re-derives the month key (spec §5 staleness fix). */
    val stats: StateFlow<MonthStats?> =
        flow {
            val monthKey = ledger.currentMonthKey()
            val (from, to) = ledger.boundsOfMonth(monthKey)
            // Recurring detection needs the complete months before this one, so the observed
            // window starts earlier than the month being folded. It is one query either way,
            // and it keeps the whole thing reactive rather than a suspend read that would go
            // stale the moment a transaction lands.
            val ym = YearMonth.parse(monthKey)
            val lookBackFrom =
                ledger.boundsOfMonth(ym.minusMonths(RecurringPayees.LOOK_BACK_MONTHS.toLong()).toString()).first
            emitAll(
                combine(
                    container.db.categoryDao().observeAll(),
                    container.db.transactionDao().observeLoggedSumsByCategory(from, to),
                    container.db.budgetDao().observeAllForMonth(monthKey),
                    container.db.transactionDao().observeLoggedBetween(lookBackFrom, to),
                ) { cats, sums, budgets, window ->
                    val (day, days) = ledger.today()
                    val recurring = RecurringPayees.detect(window, ym, zone)
                    val fixed = RecurringPayees.fixedSpentPaise(window.filter { it.occurredAt in from..to }, recurring)
                    MonthStatsFolder.fold(
                        cats, sums.associate { it.categoryId to it.totalPaise }, budgets, fixed, day, days,
                    )
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recent: Flow<List<TxnRow>> = ledger.observeRecent()

    fun setRegret(uuid: String, value: Regret) = viewModelScope.launch { ledger.setRegret(uuid, value) }

    /** amountPaise null = clear. categoryId null = overall. Same delete+insert idiom as 1A. */
    fun setBudget(categoryId: Long?, amountPaise: Long?) {
        viewModelScope.launch {
            // Month key resolved at call time, not VM birth. The delete+insert idiom and the
            // sync tombstone rule both live in BudgetRepository now (spec §2.5).
            container.budgets.setBudget(categoryId, ledger.currentMonthKey(), amountPaise)
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DashboardViewModel(container) as T
        }
    }
}
