package com.expensegarden.app.gate

/** One budget scope at gate time. depth: 0 = overall, then 1 per taxonomy level (deeper = more specific). */
data class ScopeInput(
    val categoryId: Long?,
    val label: String,
    val budgetPaise: Long,
    val spentPaise: Long,
    val depth: Int,
)

/** Worst severity across scopes; offender = deepest scope at that severity (null when OK). */
data class GateVerdict(val severity: Severity, val offender: ScopeInput?)

object GateAggregator {
    /** @param fixedPaise this month's spend on recurring payees, applied to the OVERALL scope
     *    only. A category budget paces that category, and rent is not inside Shopping — giving
     *    every scope the overall fixed allowance would hand each of them room it has not
     *    earned, and would quietly disable the per-category gate. */
    fun aggregate(
        scopes: List<ScopeInput>,
        candidatePaise: Long,
        fixedPaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): GateVerdict {
        val evaluated = scopes.map { scope ->
            val fixedForScope = if (scope.categoryId == null) fixedPaise else 0L
            scope to GateEvaluator.evaluate(
                scope.spentPaise, scope.budgetPaise, candidatePaise, fixedForScope, dayOfMonth, daysInMonth,
            )
        }
        val worst = evaluated.maxOfOrNull { it.second } ?: Severity.OK   // enum order: OK < PACE_WARNING < BREACH
        if (worst == Severity.OK) return GateVerdict(Severity.OK, null)
        val offender = evaluated.filter { it.second == worst }.maxBy { it.first.depth }.first
        return GateVerdict(worst, offender)
    }
}
