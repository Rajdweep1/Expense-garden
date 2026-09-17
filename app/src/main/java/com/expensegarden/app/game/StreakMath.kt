package com.expensegarden.app.game

import com.expensegarden.app.gate.GateEvaluator

/** Day-level derivations for the live month. `today` is 1-based; only fully-past days (1..today-1) count. */
object StreakMath {
    /** @param firstObservedDay see [underPaceStreak] — an unobserved day is not a no-spend day. */
    fun noSpendDays(dayTotalsPaise: Map<Int, Long>, today: Int, firstObservedDay: Int = 1): Int =
        (maxOf(1, firstObservedDay) until today).count { (dayTotalsPaise[it] ?: 0L) == 0L }

    /** Consecutive days ending yesterday whose cumulative spend stayed ≤ that day's pace allowance.
     *
     *  @param firstObservedDay the first day of this month the ledger actually covers. Days before
     *   it are *unknown*, not *under pace* — without this, a fresh install on the 13th is handed a
     *   12-day streak it never earned, which is both a cosmetic lie on the home strip and enough
     *   to fire STREAK_7 and grow a rare. Defaults to 1, which is the whole month and therefore
     *   the previous behaviour exactly. */
    fun underPaceStreak(
        dayTotalsPaise: Map<Int, Long>,
        budgetPaise: Long?,
        today: Int,
        daysInMonth: Int,
        firstObservedDay: Int = 1,
    ): Int {
        if (budgetPaise == null || budgetPaise <= 0) return 0
        var cumulative = 0L
        val underByDay = (1 until today).map { day ->
            cumulative += dayTotalsPaise[day] ?: 0L
            // Cumulative spend still accrues across unobserved days — a restored ledger may hold
            // transactions there — but such a day can never itself extend the streak.
            day >= firstObservedDay && cumulative <= GateEvaluator.paceAllowancePaise(budgetPaise, day, daysInMonth)
        }
        return underByDay.takeLastWhile { it }.size
    }
}
