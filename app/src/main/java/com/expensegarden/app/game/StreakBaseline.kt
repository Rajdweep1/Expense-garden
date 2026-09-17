package com.expensegarden.app.game

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * The first day of a month the ledger actually covers.
 *
 * Neither source is sufficient alone, which is why this takes both:
 *
 * - The **device's first run** alone truncates a restored phone. Install on the 25th with a year
 *   of synced history and the streak would reset to nothing.
 * - The **earliest ledger event** alone destroys a real no-spend run. Install on the 1st, spend
 *   nothing until the 20th, and the first event is the 20th — erasing nineteen days that were
 *   genuinely earned.
 *
 * So it is the earlier of the two, clamped into the month being folded.
 */
object StreakBaseline {

    /**
     * @param installedAtMillis device first-run timestamp; 0 when never recorded.
     * @param earliestEventMillis createdAt of the oldest game_event; null when the ledger is empty.
     * @return a 1-based day of `month`. 1 means "the whole month is observed".
     */
    fun firstObservedDay(
        installedAtMillis: Long,
        earliestEventMillis: Long?,
        month: YearMonth,
        zone: ZoneId,
    ): Int {
        val candidates = listOfNotNull(
            installedAtMillis.takeIf { it > 0L },
            earliestEventMillis,
        )
        // Nothing recorded: treat the month as fully observed rather than inventing a floor that
        // would silently suppress a streak.
        val earliest = candidates.minOrNull() ?: return 1
        val date = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate()
        return when {
            YearMonth.from(date) < month -> 1          // began before this month — all of it counts
            YearMonth.from(date) > month -> 1          // clock skew; do not suppress a real streak
            else -> date.dayOfMonth
        }
    }
}
