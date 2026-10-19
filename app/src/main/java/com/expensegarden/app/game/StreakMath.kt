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
     *   the previous behaviour exactly.
     *  @param fixedByDayPaise spend on recurring payees, per day — the slice of [dayTotalsPaise]
     *   the gate takes out of the pace line. Each day is judged against the line the gate drew that
     *   day, with the fixed spend so far: without it, rent on the 1st failed every day until about
     *   the 22nd while the gate and the strip said "on pace" (review, 2026-10-18).
     *  @param budgetByDayPaise the overall budget in force on each day (BudgetInForce): a day is
     *   judged against its own budget, and a day without one can't extend the streak. null judges
     *   every day against [budgetPaise], for callers with no budget history. */
    fun underPaceStreak(
        dayTotalsPaise: Map<Int, Long>,
        budgetPaise: Long?,
        today: Int,
        daysInMonth: Int,
        firstObservedDay: Int = 1,
        fixedByDayPaise: Map<Int, Long> = emptyMap(),
        budgetByDayPaise: Map<Int, Long>? = null,
    ): Int {
        if (budgetPaise == null || budgetPaise <= 0) return 0
        var cumulative = 0L
        var fixedSoFar = 0L
        val underByDay = (1 until today).map { day ->
            cumulative += dayTotalsPaise[day] ?: 0L
            fixedSoFar += fixedByDayPaise[day] ?: 0L
            val line = if (budgetByDayPaise == null) budgetPaise else budgetByDayPaise[day]
            // Cumulative spend still accrues across unobserved days — a restored ledger may hold
            // transactions there — but such a day can never itself extend the streak.
            day >= firstObservedDay && line != null &&
                cumulative <= GateEvaluator.paceAllowancePaise(line, fixedSoFar, day, daysInMonth)
        }
        return underByDay.takeLastWhile { it }.size
    }
}
