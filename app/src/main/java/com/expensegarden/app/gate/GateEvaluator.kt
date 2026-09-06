package com.expensegarden.app.gate

enum class Severity { OK, PACE_WARNING, BREACH }

object GateEvaluator {
    private const val PACE_GRACE = 1.15

    /** Day-proportional spend allowance incl. grace, floored to paise.
     *
     *  Equivalent to `fixedPaise = 0`. Kept for callers with no notion of fixed spend — the
     *  per-category scopes, which pace their own budget and have no rent inside them. */
    fun paceAllowancePaise(budgetPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long =
        paceAllowancePaise(budgetPaise, 0L, dayOfMonth, daysInMonth)

    /** Allowance with fixed monthly costs removed from BOTH sides of the comparison.
     *
     *  Only the variable half of the budget is paced; whatever has already gone on recurring
     *  payees passes straight through. Without this, rent paid on day 1 sits above a
     *  day-proportional line for most of the month, and the payment gate raises a dialog on
     *  nearly every purchase — measured at days 1 through 22 on real spending. A prompt that
     *  always fires is one people learn to tap through, which spends the exact attention it
     *  exists to buy. */
    fun paceAllowancePaise(
        budgetPaise: Long,
        fixedPaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Long {
        val fixed = fixedPaise.coerceIn(0L, budgetPaise)
        val variableBudget = budgetPaise - fixed
        return fixed + (variableBudget.toDouble() * dayOfMonth / daysInMonth * PACE_GRACE).toLong()
    }

    fun evaluate(
        spentThisMonthPaise: Long,
        monthBudgetPaise: Long?,
        candidatePaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Severity =
        evaluate(spentThisMonthPaise, monthBudgetPaise, candidatePaise, 0L, dayOfMonth, daysInMonth)

    fun evaluate(
        spentThisMonthPaise: Long,
        monthBudgetPaise: Long?,
        candidatePaise: Long,
        fixedPaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Severity {
        if (monthBudgetPaise == null || monthBudgetPaise <= 0) return Severity.OK
        val afterPayment = spentThisMonthPaise + candidatePaise
        // BREACH is untouched by the fixed term. Over the budget is over the budget, however
        // the money was committed — only the PACE line ever moves.
        if (afterPayment > monthBudgetPaise) return Severity.BREACH
        val allowance = paceAllowancePaise(monthBudgetPaise, fixedPaise, dayOfMonth, daysInMonth)
        return if (afterPayment > allowance) Severity.PACE_WARNING else Severity.OK
    }
}
