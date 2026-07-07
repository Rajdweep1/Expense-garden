package com.expensegarden.app.game

import java.time.YearMonth

/** Pure decisions for the on-open reconciler — the local-first answer to "no server, no cron".
 *  The repository turns these into appended game_events; both outputs are idempotent by construction. */
object Reconciler {
    val STREAK_THRESHOLDS = listOf(3, 7, 14, 30)

    data class Decisions(val monthsToClose: List<String>, val streakHitsToEmit: List<Int>)

    fun decide(
        currentMonth: YearMonth,
        monthsWithData: List<YearMonth>,
        closedMonths: Set<String>,
        currentStreakDays: Int,
        streakHitDaysThisMonth: Set<Int>,
    ): Decisions = Decisions(
        monthsToClose = monthsWithData
            .filter { it < currentMonth && it.toString() !in closedMonths }
            .sorted()
            .map { it.toString() },
        streakHitsToEmit = STREAK_THRESHOLDS.filter { it <= currentStreakDays && it !in streakHitDaysThisMonth },
    )
}
