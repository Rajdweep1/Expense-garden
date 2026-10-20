package com.expensegarden.app.gate

/** One budget scope at gate time. depth: 0 = overall, then 1 per taxonomy level (deeper = more specific). */
data class ScopeInput(
    val categoryId: Long?,
    val label: String,
    val budgetPaise: Long,
    val spentPaise: Long,
    val depth: Int,
)

/** Worst severity across scopes; offender = deepest scope at that severity (null when OK).
 *  [allowancePaise] is the offender's pace line, with the same fixed-cost term the verdict used,
 *  so the dialog's numbers can't disagree with the verdict they explain (0 when OK).
 *  [allowanceBeforePaise] is that line without this payment's own fixed share: a fixed-cost
 *  payment raises the line it is judged by, so "already ahead" is judged against this one. */
data class GateVerdict(
    val severity: Severity,
    val offender: ScopeInput?,
    val allowancePaise: Long = 0L,
    val allowanceBeforePaise: Long = allowancePaise,
)

object GateAggregator {
    /** @param fixedPaise this month's fixed spend (RecurringPayees.fixedSpentPaise), applied to the
     *    OVERALL scope only. A category budget paces that category, and rent is not inside
     *    Shopping — giving every scope the overall fixed allowance would hand each of them room it
     *    has not earned, and would quietly disable the per-category gate.
     *  @param candidateFixedPaise the part of [candidatePaise] that is itself a fixed cost: what is
     *    left of its payee's fixed cap this month (RecurringPayees.fixedShares). It joins the
     *    overall fixed term, as it will the moment it is logged; left out, the gate warned about a
     *    payment the strip called fixed a second later (final review, 2026-10-19). */
    fun aggregate(
        scopes: List<ScopeInput>,
        candidatePaise: Long,
        fixedPaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
        candidateFixedPaise: Long = 0L,
    ): GateVerdict {
        val overallFixed = fixedPaise + candidateFixedPaise
        val evaluated = scopes.map { scope ->
            val fixedForScope = if (scope.categoryId == null) overallFixed else 0L
            scope to GateEvaluator.evaluate(
                scope.spentPaise, scope.budgetPaise, candidatePaise, fixedForScope, dayOfMonth, daysInMonth,
            )
        }
        val worst = evaluated.maxOfOrNull { it.second } ?: Severity.OK   // enum order: OK < PACE_WARNING < BREACH
        if (worst == Severity.OK) return GateVerdict(Severity.OK, null)
        val offender = evaluated.filter { it.second == worst }.maxBy { it.first.depth }.first
        val isOverall = offender.categoryId == null
        return GateVerdict(
            worst, offender,
            allowancePaise = GateEvaluator.paceAllowancePaise(
                offender.budgetPaise, if (isOverall) overallFixed else 0L, dayOfMonth, daysInMonth,
            ),
            allowanceBeforePaise = GateEvaluator.paceAllowancePaise(
                offender.budgetPaise, if (isOverall) fixedPaise else 0L, dayOfMonth, daysInMonth,
            ),
        )
    }
}
