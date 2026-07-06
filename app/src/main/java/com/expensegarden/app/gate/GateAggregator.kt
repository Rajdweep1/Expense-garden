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
    fun aggregate(scopes: List<ScopeInput>, candidatePaise: Long, dayOfMonth: Int, daysInMonth: Int): GateVerdict {
        val evaluated = scopes.map { scope ->
            scope to GateEvaluator.evaluate(scope.spentPaise, scope.budgetPaise, candidatePaise, dayOfMonth, daysInMonth)
        }
        val worst = evaluated.maxOfOrNull { it.second } ?: Severity.OK   // enum order: OK < PACE_WARNING < BREACH
        if (worst == Severity.OK) return GateVerdict(Severity.OK, null)
        val offender = evaluated.filter { it.second == worst }.maxBy { it.first.depth }.first
        return GateVerdict(worst, offender)
    }
}
