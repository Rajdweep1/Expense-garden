package com.expensegarden.app.game

import java.time.YearMonth

/** Pure decisions for the on-open reconciler — the local-first answer to "no server, no cron".
 *  The repository turns these into appended game_events; both outputs are idempotent by construction. */
object Reconciler {
    val STREAK_THRESHOLDS = listOf(3, 7, 14, 30)

    data class Decisions(val monthsToClose: List<String>, val streakHitsToEmit: List<Int>)

    /** @param heldMonths months holding a payment not logged yet: closing them now would be
     *    permanent (append-only) and could be wrong (pay-first spec D3).
     *  @param holdStreak true while any payment is unlogged, for the same reason. */
    fun decide(
        currentMonth: YearMonth,
        monthsWithData: List<YearMonth>,
        closedMonths: Set<String>,
        currentStreakDays: Int,
        streakHitDaysThisMonth: Set<Int>,
        heldMonths: Set<String> = emptySet(),
        holdStreak: Boolean = false,
    ): Decisions = Decisions(
        monthsToClose = monthsWithData
            .filter { it < currentMonth && it.toString() !in closedMonths && it.toString() !in heldMonths }
            .sorted()
            .map { it.toString() },
        streakHitsToEmit = if (holdStreak) emptyList()
            else STREAK_THRESHOLDS.filter { it <= currentStreakDays && it !in streakHitDaysThisMonth },
    )
}
