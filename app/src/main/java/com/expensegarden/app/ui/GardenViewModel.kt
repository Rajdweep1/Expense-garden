package com.expensegarden.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.expensegarden.app.AppContainer
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.game.GardenState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GardenViewModel(private val container: AppContainer) : ViewModel() {
    /** null = loading skeleton. flow{} wrapper re-derives the month on re-subscription (house idiom).
     *  1C.5: home shows the persistent all-time island; the greenhouse keeps monthly folds. */
    val garden: StateFlow<GardenState?> =
        flow { emitAll(container.garden.observeAllTimeGarden()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch { container.garden.runReconciler() }   // month.closed / streak.hit on open
    }

    suspend fun plantRow(uuid: String): TxnRow? = container.db.transactionDao().rowByUuid(uuid)

    suspend fun archivedGardens(): List<GardenState> =
        container.garden.monthsWithData().dropLast(1).map { container.garden.foldMonth(it) }.reversed()

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GardenViewModel(container) as T
        }
    }
}
