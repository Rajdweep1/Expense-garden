package com.expensegarden.app.gate

enum class Severity { OK, PACE_WARNING, BREACH }

object GateEvaluator {
    private const val PACE_GRACE = 1.15

    /** Day-proportional spend allowance incl. grace, floored to paise. */
    fun paceAllowancePaise(budgetPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long =
        (budgetPaise.toDouble() * dayOfMonth / daysInMonth * PACE_GRACE).toLong()

    fun evaluate(
        spentThisMonthPaise: Long,
        monthBudgetPaise: Long?,
        candidatePaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Severity {
        if (monthBudgetPaise == null || monthBudgetPaise <= 0) return Severity.OK
        val afterPayment = spentThisMonthPaise + candidatePaise
        if (afterPayment > monthBudgetPaise) return Severity.BREACH
        return if (afterPayment > paceAllowancePaise(monthBudgetPaise, dayOfMonth, daysInMonth)) Severity.PACE_WARNING
        else Severity.OK
    }
}
