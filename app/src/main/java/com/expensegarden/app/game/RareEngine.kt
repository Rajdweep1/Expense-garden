package com.expensegarden.app.game

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Detects earned rares from the signal log (spec §3). Pure: no IO, no clock, no randomness,
 *  and — by construction — no `org.json`.
 *
 *  **Earns are derived, never emitted or stored** (spec §4.2). That is load-bearing rather than
 *  tidy: an emitter sees only the current window and therefore cannot enforce "once ever",
 *  while a fold over all of history can. The once-per-scope guarantee in §3.3 is the only thing
 *  standing between this design and a farming exploit, and it can only hold here.
 *
 *  Everything the caller supplies — `noSpendByMonth`, `breadthByMonth`, `houseLevel` — is a
 *  derived fact rather than a live read, so the same inputs always produce the same output. */
object RareEngine {

    const val DODGES_FOR_EARN = 3
    const val NO_SPEND_DAYS_FOR_EARN = 7
    const val ROOT_CATEGORIES_FOR_EARN = 8

    /** Reconciler emits 3, 7, 14 and 30. Only two of them are rewards; the others are progress. */
    private const val STREAK_UNCOMMON = 7
    private const val STREAK_RARE = 30

    private val LANDMARK_HOUSE_LEVELS = listOf(3, 4)

    /**
     * @param signals every projected signal in the log, any order.
     * @param noSpendByMonth month key → no-spend days elapsed that month.
     * @param breadthByMonth month key → distinct root categories spent in that month.
     * @param houseLevel the current level from `GardenFolder.houseLevel`.
     */
    fun earns(
        signals: List<RareSignal>,
        noSpendByMonth: Map<String, Int>,
        breadthByMonth: Map<String, Int>,
        houseLevel: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Earn> {
        // Sorted by event id so the result never depends on the order Room happened to return.
        val ordered = signals.sortedBy { it.eventId }
        val out = mutableListOf<Earn>()

        for (s in ordered.filterIsInstance<RareSignal.StreakHit>()) {
            val tier = when (s.days) {
                STREAK_UNCOMMON -> RareTier.UNCOMMON
                STREAK_RARE -> RareTier.RARE
                else -> null
            } ?: continue
            val trigger = if (s.days == STREAK_UNCOMMON) RareTrigger.STREAK_7 else RareTrigger.STREAK_30
            out += Earn(trigger, "streak${s.days}:${s.month}", tier, s.eventId, s.atMillis)
        }

        ordered.filterIsInstance<RareSignal.GateDodged>()
            .groupBy { monthOf(it.atMillis, zone) }
            .filterValues { it.size >= DODGES_FOR_EARN }
            .forEach { (month, group) ->
                // Seeded on the dodge that COMPLETED the earn, so the species stays stable even
                // as further dodges accumulate in the same month.
                val completing = group.sortedBy { it.eventId }[DODGES_FOR_EARN - 1]
                out += Earn(
                    RareTrigger.GATE_DODGES, "dodges:$month", RareTier.UNCOMMON,
                    completing.eventId, completing.atMillis,
                )
            }

        for (s in ordered.filterIsInstance<RareSignal.MonthClosed>()) {
            // A null budget means none was set. Under-spending a budget that does not exist is
            // not an achievement, and treating null as zero would invert the comparison.
            val budget = s.budgetPaise ?: continue
            if (budget <= 0L || s.spentPaise > budget) continue
            out += Earn(
                RareTrigger.MONTH_UNDER_BUDGET, "under:${s.month}", RareTier.RARE,
                s.eventId, s.atMillis,
            )
        }

        // ONCE PER TRANSACTION, not per event (spec §3.3). LedgerRepository.setRegret no-ops
        // only on an unchanged value, so REGRET -> WORTH_IT -> REGRET -> WORTH_IT emits
        // regret_cleared twice. Keyed per event, two taps repeated would mint Uncommons forever.
        ordered.filterIsInstance<RareSignal.RegretCleared>()
            .distinctBy { it.txnUuid }
            .forEach { s ->
                out += Earn(
                    RareTrigger.REDEEMED, "redeem:${s.txnUuid}", RareTier.UNCOMMON,
                    s.eventId, s.atMillis,
                )
            }

        // Derived month facts. These have no originating event, so the scope key itself seeds
        // the species roll — stable forever, because the key never changes for a given month.
        for ((month, days) in noSpendByMonth.toSortedMap()) {
            if (days < NO_SPEND_DAYS_FOR_EARN) continue
            val key = "nospend:$month"
            out += Earn(RareTrigger.NO_SPEND_DAYS, key, RareTier.UNCOMMON, seedFrom(key), monthStart(month, zone))
        }
        for ((month, roots) in breadthByMonth.toSortedMap()) {
            if (roots < ROOT_CATEGORIES_FOR_EARN) continue
            val key = "breadth:$month"
            out += Earn(RareTrigger.CATEGORY_BREADTH, key, RareTier.RARE, seedFrom(key), monthStart(month, zone))
        }
        for (level in LANDMARK_HOUSE_LEVELS) {
            if (houseLevel < level) continue
            val key = "house:$level"
            out += Earn(RareTrigger.HOUSE_LEVEL, key, RareTier.LANDMARK, seedFrom(key), 0L)
        }

        // One earn per scope key, in a stable order so RarePairing reproduces the same
        // assignment on every fold.
        return out.distinctBy { it.scopeKey }
            .sortedWith(compareBy({ it.atMillis }, { it.scopeKey }))
    }

    /** Seed for earns derived from a month fact rather than a single event. Using the scope key
     *  means the same month always yields the same species, however often the fold re-runs. */
    private fun seedFrom(scopeKey: String): Long = scopeKey.hashCode().toLong()

    private fun monthOf(atMillis: Long, zone: ZoneId): String =
        YearMonth.from(Instant.ofEpochMilli(atMillis).atZone(zone)).toString()

    /** Month-derived earns are ordered as if they happened at the month's start, so a rare
     *  earned for September's restraint cannot jump ahead of an October event in the pairing. */
    private fun monthStart(monthKey: String, zone: ZoneId): Long =
        runCatching {
            YearMonth.parse(monthKey).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrDefault(0L)
}
