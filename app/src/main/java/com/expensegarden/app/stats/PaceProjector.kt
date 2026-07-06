package com.expensegarden.app.stats

/** Classical-stats month pace (spec §8.3). Money stays paise-Long; division truncates at display precision. */
object PaceProjector {
    fun projectedMonthEndPaise(spentPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long {
        val day = dayOfMonth.coerceAtLeast(1)
        return spentPaise * daysInMonth / day
    }

    /** Remaining budget spread over the remaining days, today included. 0 when over. */
    fun perDayToStayUnderPaise(spentPaise: Long, budgetPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long {
        val daysLeft = (daysInMonth - dayOfMonth + 1).coerceAtLeast(1)
        return ((budgetPaise - spentPaise) / daysLeft).coerceAtLeast(0L)
    }
}
