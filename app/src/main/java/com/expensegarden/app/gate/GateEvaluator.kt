package com.expensegarden.app.gate

enum class Severity { OK, PACE_WARNING, BREACH }

object GateEvaluator {
    private const val PACE_GRACE = 1.15

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
        val paceAllowance = monthBudgetPaise.toDouble() * dayOfMonth / daysInMonth * PACE_GRACE
        return if (afterPayment > paceAllowance) Severity.PACE_WARNING else Severity.OK
    }
}
