package com.expensegarden.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.expensegarden.app.AppContainer
import com.expensegarden.app.ai.DigestWriter
import com.expensegarden.app.ai.PromptFacts
import com.expensegarden.app.ai.QuipRefresher
import com.expensegarden.app.ai.RoomQuipSink
import com.expensegarden.app.data.DigestEntity
import com.expensegarden.app.game.DigestKind
import com.expensegarden.app.game.DigestReason
import com.expensegarden.app.game.DigestSnapshot
import com.expensegarden.app.game.DigestTrigger
import com.expensegarden.app.game.GardenState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Owns the one background job in the app and the state of the two digest cards.
 *
 *  Runs on viewModelScope, which is Dispatchers.Main. That is safe because Room's suspend
 *  DAOs dispatch internally and GeminiClient wraps its own HttpURLConnection work in
 *  withContext(Dispatchers.IO) — see spec §3. Nothing here is a read path: this writes rows,
 *  and the screens observe them. */
class AiViewModel(private val container: AppContainer) : ViewModel() {

    /** Resolved once at construction. A session that spans midnight keeps yesterday's key
     *  until the ViewModel is recreated — acceptable, and simpler than pulling in an
     *  experimental flatMapLatest for a card that only matters on the day it is written. */
    val dailyCard: StateFlow<DigestEntity?> =
        container.digests.observeDaily(LocalDate.now().toString())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { viewModelScope.launch { runAiJob() } }

    /** "Not today" — mutes the persona for 24h without touching logging (spec §8). */
    fun notToday() {
        val card = dailyCard.value ?: return
        viewModelScope.launch {
            container.aiPrefs.mutedUntil = System.currentTimeMillis() + MUTE_MS
            container.digests.dismiss(card.id, System.currentTimeMillis())
        }
    }

    fun dismiss() {
        val card = dailyCard.value ?: return
        viewModelScope.launch { container.digests.dismiss(card.id, System.currentTimeMillis()) }
    }

    suspend fun monthlyFor(monthKey: String): DigestEntity? = container.digests.monthly(monthKey)

    private suspend fun runAiJob() {
        // No key is a first-class state, not an error: the app is fully functional here.
        if (!container.aiPrefs.hasKey) return

        val now = System.currentTimeMillis()
        val tone = container.aiPrefs.tone

        // Quip refresh, throttled to once a day.
        if (now - container.aiPrefs.lastQuipRefreshAt > DAY_MS) {
            container.aiPrefs.lastQuipRefreshAt = now
            QuipRefresher(container.llm, RoomQuipSink(container.db)).refresh(tone)
        }

        // Digest. window() reads the head BEFORE the events, so anything logged during the LLM
        // round trip has id > head and stays pending (spec §9). It also owns the first-run
        // floor, so a fresh install cannot sweep up every event since 1A.
        val garden = container.garden.observeAllTimeGarden().first()
        val last = container.digests.latestSnapshot()
        val window = container.digests.window(last, startOfTodayMillis())

        val verdict = DigestTrigger.evaluate(
            lastDigest = last,
            eventsSince = window.events,
            monthToDate = container.digests.monthFacts(garden.monthKey),
            now = garden,
            today = LocalDate.now(),
            mutedUntilMillis = container.aiPrefs.mutedUntil.takeIf { it > 0 },
            nowMillis = now,
        )
        if (verdict.isSilent) return

        val reasons = verdict.monthly + listOfNotNull(verdict.daily)
        // A scope that already has a row would be swallowed by UNIQUE(kind, scopeKey) while its
        // siblings committed the shared watermark past events nobody spoke about — a dodge or a
        // streak lost for good. Staying silent consumes nothing and costs no completion; the
        // verdict is re-evaluated next open, and a repeat daily scope clears itself at midnight.
        if (reasons.any { container.digests.exists(it) }) return

        val snapshot = DigestSnapshot(
            weather = garden.weather,
            houseLevel = garden.houseLevel,
            streakDays = garden.streakDays,
            lastEventId = window.head,
        )
        val writer = DigestWriter(container.llm)
        // A MONTHLY reason is about the CLOSED month, so it gets that month's facts — otherwise
        // August's retrospective would be written against September's spend and categories.
        val todayFacts = promptFacts(garden.monthKey, garden)
        suspend fun factsFor(r: DigestReason): PromptFacts =
            if (r.kind == DigestKind.MONTHLY) promptFacts(r.scopeKey, container.garden.foldMonth(r.scopeKey))
            else todayFacts

        // All or nothing (spec §9). Every row shares one watermark, so writing the monthly
        // card while the daily one failed would consume the day's events forever. Compose
        // every text first; a single null writes nothing, and the whole verdict is
        // re-evaluated next open — the same self-healing shape as the mute.
        val texts = reasons.map { writer.compose(it, factsFor(it), tone) ?: return }
        container.digests.writeAll(reasons.zip(texts), snapshot, now)
    }

    private suspend fun promptFacts(monthKey: String, garden: GardenState): PromptFacts {
        val (from, to) = container.ledger.boundsOfMonth(monthKey)
        return PromptFacts(
            weather = garden.weather,
            houseLevel = garden.houseLevel,
            streakDays = garden.streakDays,
            spentPaise = garden.spentPaise,
            budgetPaise = container.db.budgetDao().overallForMonth(monthKey)?.amountPaise,
            regretCount = container.digests.monthFacts(monthKey).regretCount,
            dodgeCount = garden.butterflies,
            monthKey = monthKey,
            topCategories = container.db.transactionDao().topCategoryNames(from, to),
        )
    }

    private fun startOfTodayMillis(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MUTE_MS = 24L * 60 * 60 * 1000

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AiViewModel(container) as T
        }
    }
}
