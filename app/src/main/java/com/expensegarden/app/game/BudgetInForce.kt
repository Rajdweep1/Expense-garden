package com.expensegarden.app.game

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * The overall budget in force on each day of a month.
 *
 * A budget row has no history, so every elapsed day used to be scored against whatever budget
 * existed now: setting one on the 18th re-scored days 1–17 and minted streak rewards for them
 * (review, 2026-10-18), and raising one late in the month did the same for every day before the
 * raise, then closed the month on the raised amount (final review, 2026-10-19). The streak judges
 * each day against the budget in force that day, and a closed month against the smallest of them.
 */
object BudgetInForce {
    /** The overall budget became [amountPaise] at [at] (epoch millis, wall time). */
    data class Change(val at: Long, val amountPaise: Long)

    /**
     * @param carriedPaise the overall amount a `budget.carried` event copied into [month]: a
     *   standing budget, in force from the 1st however late in the month the app was opened.
     * @param sets [month]'s overall `budget.set` events, oldest first.
     * @param legacy the month's overall row, for rows written before `budget.set` existed: its
     *   amount from its stamp on. Used only when [sets] is empty.
     * @return day of [month] → the amount in force at that day's end. Days before any budget are
     *   absent, and a day's last change is the one in force that day.
     */
    fun overallByDay(month: YearMonth, carriedPaise: Long?, sets: List<Change>, legacy: Change?, zone: ZoneId): Map<Int, Long> {
        val lastDay = month.lengthOfMonth()
        fun dayOf(at: Long): Int {
            val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            return when {
                YearMonth.from(date) < month -> 1
                YearMonth.from(date) > month -> lastDay + 1
                else -> date.dayOfMonth
            }
        }
        // sortedBy is stable, so changes on the same day keep the order they were made in.
        val changes = (listOfNotNull(carriedPaise?.let { 1 to it }) +
            sets.ifEmpty { listOfNotNull(legacy) }.map { dayOf(it.at) to it.amountPaise })
            .sortedBy { it.first }
        val byDay = mutableMapOf<Int, Long>()
        var inForce: Long? = null
        var next = 0
        for (day in 1..lastDay) {
            while (next < changes.size && changes[next].first <= day) inForce = changes[next++].second
            inForce?.let { byDay[day] = it }
        }
        return byDay
    }

    /** What a closed month is judged against: the smallest overall budget in force on any of its
     *  days, so raising one can't buy the month a pass. None unless one was in force from the 1st:
     *  "under-spending a budget that does not exist is not an achievement" (RareEngine). */
    fun closingPaise(byDay: Map<Int, Long>): Long? = if (1 in byDay) byDay.values.min() else null
}
