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
    /** One emission carries BOTH the island and its expansion origin.
     *
     *  They were two StateFlows and that produced a visible flicker: `garden` emits at once
     *  while `expandFrom` still has async folding to do, so the canvas rendered the NEW state
     *  with a null origin, started at ep = 1, and drew the finished villa — then the origin
     *  arrived, ep snapped to 0, and the house jumped back to the old sprite to animate
     *  forward. The decision is logically atomic, so it must arrive atomically. */
    data class Homestead(val state: GardenState, val expandFrom: GardenState?)

    /** Bumped once the tween has played, so the flow re-reads prefs and drops the origin.
     *  Without it the value survives until the next DB emission, and merely navigating away
     *  and back would rebuild the canvas and replay the expansion. */
    private val expansionRefresh = MutableStateFlow(0)

    /** null = loading skeleton. flow{} wrapper re-derives the month on re-subscription (house
     *  idiom). 1C.5: home shows the persistent all-time island; the greenhouse keeps monthly folds.
     *
     *  1C.7: the origin is the same transactions folded at the level this device last saw. It is
     *  non-null only when that level's FOOTPRINT differs — level 1→2 shares the 2×2 plot, so it
     *  records silently and never animates. A fresh install (lastSeen == 0) also adopts silently:
     *  we don't replay an expansion the user was never present for. */
    val homestead: StateFlow<Homestead?> =
        combine(
            flow { emitAll(container.garden.observeAllTimeGarden()) },
            expansionRefresh,
        ) { g, _ -> g }.map { g ->
            val lastSeen = container.prefs.lastSeenHouseLevel
            val origin = if (
                lastSeen == 0 || lastSeen >= g.houseLevel ||
                SpiralTiler.footprint(lastSeen) == SpiralTiler.footprint(g.houseLevel)
            ) {
                container.prefs.lastSeenHouseLevel = g.houseLevel
                null
            } else {
                container.garden.observeAllTimeGarden(houseLevelOverride = lastSeen).first()
            }
            Homestead(g, origin)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The greenhouse and other callers only ever want the island itself. */
    val garden: StateFlow<GardenState?> =
        homestead.map { it?.state }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun markExpansionShown() {
        homestead.value?.let { container.prefs.lastSeenHouseLevel = it.state.houseLevel }
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
