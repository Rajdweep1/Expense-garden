package com.expensegarden.app.game

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * The first day of a month on which its overall budget was in force.
 *
 * A budget row has no start date, so the streak used to score every elapsed day against whatever
 * budget existed now: setting one on the 18th re-scored days 1–17 and minted streak rewards for
 * them (review, 2026-10-18). Days before this floor can't extend a streak, and a month whose floor
 * isn't the 1st can't earn MONTH_UNDER_BUDGET — "under-spending a budget that does not exist is
 * not an achievement" (RareEngine).
 */
object BudgetFloor {

    /**
     * @param carried a `budget.carried` event for [month] copied an overall row: the budget was a
     *   standing one, in force from the 1st however late in the month the app was opened.
     * @param overallSetAt createdAt of every `budget.set` event for [month]'s overall budget.
     * @param legacyStampedAt updatedAt of the month's overall row, for rows written before
     *   `budget.set` existed; null when there is no row.
     * @return 1..`month.lengthOfMonth() + 1`, where `lengthOfMonth() + 1` means no day of [month].
     */
    fun overallFromDay(
        month: YearMonth,
        carried: Boolean,
        overallSetAt: List<Long>,
        legacyStampedAt: Long?,
        zone: ZoneId,
    ): Int {
        if (carried) return 1
        // No budget at all: nothing to floor, and the streak is 0 without one anyway.
        val earliest = overallSetAt.minOrNull() ?: legacyStampedAt ?: return 1
        val date = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate()
        return when {
            YearMonth.from(date) < month -> 1
            YearMonth.from(date) > month -> month.lengthOfMonth() + 1
            else -> date.dayOfMonth
        }
    }
}
