package com.expensegarden.app.game

import java.time.LocalDate

/** The entire silence rule (spec §5). Pure: no IO, no LLM, no org.json.
 *
 *  The daily digest speaks only when something TRANSITIONED. Ordinary days produce silence
 *  automatically — not because a tuned threshold went unmet, but because nothing changed. */
object DigestTrigger {

    fun evaluate(
        lastDigest: DigestSnapshot?,
        eventsSince: List<DigestEvent>,
        monthToDate: MonthFacts,
        now: GardenState,
        today: LocalDate,
        mutedUntilMillis: Long?,
        nowMillis: Long,
    ): DigestVerdict {
        // "Not today" (spec §8). Returning early means no row is written, so neither the
        // snapshot nor lastEventId advances — the transition is still pending when the window
        // expires, and if the state has since reverted the comparison correctly yields
        // nothing. The rule is self-healing.
        if (mutedUntilMillis != null && nowMillis < mutedUntilMillis) return DigestVerdict.SILENT

        // A closed month is bigger news than a day. One digest per closed month, and the
        // daily card stands down — the day's transitions stay pending for tomorrow, because
        // the snapshot freezes at write time (spec §9).
        val closed = eventsSince.filterIsInstance<DigestEvent.MonthClosed>()
        if (closed.isNotEmpty()) {
            return DigestVerdict(
                daily = null,
                monthly = closed.sortedBy { it.monthKey }.map {
                    DigestReason(DigestKind.MONTHLY, it.monthKey, listOf(Trigger.MonthClosed(it.monthKey)))
                },
            )
        }

        val triggers = buildList {
            // Comparison triggers need a baseline. On first run there isn't one, so claiming
            // "the weather changed" would be a fabrication — stay quiet instead.
            if (lastDigest != null) {
                if (now.weather != lastDigest.weather) {
                    add(Trigger.WeatherChanged(lastDigest.weather, now.weather))
                }
                if (now.houseLevel > lastDigest.houseLevel) {
                    add(Trigger.HouseLevelled(lastDigest.houseLevel, now.houseLevel))
                }
            }

            // Event triggers are self-contained facts and need no baseline.
            eventsSince.filterIsInstance<DigestEvent.StreakHit>()
                .maxByOrNull { it.days }
                ?.let { add(Trigger.StreakCrossed(it.days)) }

            val dodges = eventsSince.count { it is DigestEvent.GateDodged }
            if (dodges > 0) add(Trigger.GateDodged(dodges))

            // "First regret of the month" needs month scope, which the window cannot see.
            // regretCount is the month's total at evaluation time; subtracting the regrets
            // inside the window says how many came before it.
            val regretsInWindow = eventsSince.count { it is DigestEvent.Regretted }
            if (regretsInWindow > 0 && monthToDate.regretCount - regretsInWindow <= 0) {
                add(Trigger.FirstRegretOfMonth)
            }
        }

        if (triggers.isEmpty()) return DigestVerdict.SILENT
        return DigestVerdict(
            daily = DigestReason(DigestKind.DAILY, today.toString(), triggers),
            monthly = emptyList(),
        )
    }
}
