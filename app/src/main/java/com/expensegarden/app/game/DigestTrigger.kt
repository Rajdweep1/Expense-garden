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

        // One MONTHLY digest per closed month. These do NOT suppress the daily card: the two
        // land on different screens (greenhouse postcard vs home), and runReconciler emits
        // month.closed and streak.hit in the SAME call — a suppression rule would put the
        // streak, and any dodge or regret in the window, behind the watermark forever.
        val monthly = eventsSince.filterIsInstance<DigestEvent.MonthClosed>()
            .distinctBy { it.monthKey }         // game_event has no (type, month) uniqueness
            .sortedBy { it.monthKey }
            .map { DigestReason(DigestKind.MONTHLY, it.monthKey, listOf(Trigger.MonthClosed(it.monthKey))) }

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
                ?.let { add(Trigger.StreakHit(it.days)) }

            val dodges = eventsSince.count { it is DigestEvent.GateDodged }
            if (dodges > 0) add(Trigger.GateDodged(dodges))

            // "First regret of the month" needs month scope, which the window cannot see.
            // `<= 0`, not `== 0`: tolerates a window that straddles an unfenced month boundary.
            val regretsInWindow = eventsSince.count { it is DigestEvent.Regretted }
            if (regretsInWindow > 0 && monthToDate.regretCount - regretsInWindow <= 0) {
                add(Trigger.FirstRegretOfMonth)
            }
        }

        val daily = if (triggers.isEmpty()) null else DigestReason(DigestKind.DAILY, today.toString(), triggers)
        return DigestVerdict(daily, monthly)
    }
}
