package com.expensegarden.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.expensegarden.app.AppContainer
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.SpiralTiler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GardenViewModel(private val container: AppContainer) : ViewModel() {
    /** null = loading skeleton. flow{} wrapper re-derives the month on re-subscription (house idiom).
     *  1C.5: home shows the persistent all-time island; the greenhouse keeps monthly folds. */
    val garden: StateFlow<GardenState?> =
        flow { emitAll(container.garden.observeAllTimeGarden()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 1C.7: the same transactions folded at the level this device last saw. Non-null only
     *  when that level's FOOTPRINT differs — level 1→2 shares the 2×2 plot, so it records
     *  silently and never animates. A fresh install (lastSeen == 0) also adopts silently:
     *  we don't replay an expansion the user was never present for. */
    /** Bumped once the tween has played, so expandFrom re-reads prefs and collapses to null.
     *  Without it the flow keeps its non-null value until the next DB emission, and merely
     *  navigating away and back would rebuild the canvas and replay the expansion. */
    private val expansionRefresh = MutableStateFlow(0)

    val expandFrom: StateFlow<GardenState?> =
        combine(garden, expansionRefresh) { g, _ -> g }.map { g ->
            if (g == null) return@map null
            val lastSeen = container.prefs.lastSeenHouseLevel
            if (lastSeen == 0 || lastSeen >= g.houseLevel ||
                SpiralTiler.footprint(lastSeen) == SpiralTiler.footprint(g.houseLevel)
            ) {
                container.prefs.lastSeenHouseLevel = g.houseLevel
                return@map null
            }
            container.garden.observeAllTimeGarden(houseLevelOverride = lastSeen).first()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun markExpansionShown() {
        garden.value?.let { container.prefs.lastSeenHouseLevel = it.houseLevel }
        expansionRefresh.value += 1
    }

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
