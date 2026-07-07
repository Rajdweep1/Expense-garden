package com.expensegarden.app.game

import com.expensegarden.app.gate.GateEvaluator

/** Day-level derivations for the live month. `today` is 1-based; only fully-past days (1..today-1) count. */
object StreakMath {
    fun noSpendDays(dayTotalsPaise: Map<Int, Long>, today: Int): Int =
        (1 until today).count { (dayTotalsPaise[it] ?: 0L) == 0L }

    /** Consecutive days ending yesterday whose cumulative spend stayed ≤ that day's pace allowance. */
    fun underPaceStreak(dayTotalsPaise: Map<Int, Long>, budgetPaise: Long?, today: Int, daysInMonth: Int): Int {
        if (budgetPaise == null || budgetPaise <= 0) return 0
        var cumulative = 0L
        val underByDay = (1 until today).map { day ->
            cumulative += dayTotalsPaise[day] ?: 0L
            cumulative <= GateEvaluator.paceAllowancePaise(budgetPaise, day, daysInMonth)
        }
        return underByDay.takeLastWhile { it }.size
    }
}
