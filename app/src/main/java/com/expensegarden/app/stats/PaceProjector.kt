package com.expensegarden.app.stats

/** Classical-stats month pace (spec §8.3). Money stays paise-Long; division truncates at
 *  display precision.
 *
 *  `fixedPaise` is this month's spend on recurring payees — see [RecurringPayees]. It is added
 *  back once instead of being extrapolated, because linear projection over a day-1 rent
 *  payment multiplies it by the length of the month. Measured on three months of real
 *  spending before this fix: a headline of ₹1,65,305 by month end, against actual months of
 *  ₹43–49k.
 *
 *  Fixed costs that have NOT yet landed are deliberately not predicted. Under-counting a rent
 *  payment still to come is a smaller and quieter error than over-counting one already made by
 *  five times, and it corrects itself the day the payment lands. Predicting it would mean
 *  guessing an amount, which is a second thing to be wrong about. */
object PaceProjector {
    fun projectedMonthEndPaise(
        spentPaise: Long,
        fixedPaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Long {
        val day = dayOfMonth.coerceAtLeast(1)
        // Both clamps guard the same caller error — a fixed total computed over a different
        // window than the spend total. Arithmetically impossible from one month's rows, and
        // cheap to make impossible to get wrong: the result can never fall below spend.
        val fixed = fixedPaise.coerceIn(0L, spentPaise)
        return fixed + (spentPaise - fixed) * daysInMonth / day
    }

    /** Remaining budget spread over the remaining days, today included. 0 when over. */
    fun perDayToStayUnderPaise(spentPaise: Long, budgetPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long {
        val daysLeft = (daysInMonth - dayOfMonth + 1).coerceAtLeast(1)
        return ((budgetPaise - spentPaise) / daysLeft).coerceAtLeast(0L)
    }
}
