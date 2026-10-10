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
     * When the ledger began: the earlier of the device's first run and the oldest event, or null
     * when neither exists. The one definition every "was the ledger watching?" question shares:
     * the streak floor below, and RecurringPayees' look-back months, which had made the same
     * mistake on their own (review, 2026-10-11).
     */
    fun ledgerBeganAt(installedAtMillis: Long, earliestEventMillis: Long?): Long? =
        listOfNotNull(installedAtMillis.takeIf { it > 0L }, earliestEventMillis).minOrNull()

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
        // Nothing recorded: treat the month as fully observed rather than inventing a floor that
        // would silently suppress a streak.
        val earliest = ledgerBeganAt(installedAtMillis, earliestEventMillis) ?: return 1
        val date = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate()
        return when {
            YearMonth.from(date) < month -> 1          // began before this month — all of it counts
            YearMonth.from(date) > month -> 1          // clock skew; do not suppress a real streak
            else -> date.dayOfMonth
        }
    }
}
