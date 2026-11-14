package com.expensegarden.app.gate

enum class EstimateSource { QR_FIXED, LAST_LOGGED, NONE }

data class PrePayInput(
    val verdict: GateVerdict,
    val estimatePaise: Long,
    val estimateSource: EstimateSource,
    val payeeKnown: Boolean,
    val isNecessity: Boolean,
    val isFixedPayee: Boolean,
)

sealed interface PrePayDecision {
    data object Go : PrePayDecision
    data class Card(
        val severity: Severity,
        val overPaise: Long,
        val scopeLabel: String?,
        val estimatePaise: Long,
        val estimateSource: EstimateSource,
        val withQuip: Boolean,
        val recordsDodge: Boolean,
    ) : PrePayDecision
}

/** Pay first: the one card before paying appears only when the month (with this payment's best
 *  guess, spec D1) is already over pace or budget. Necessities and fixed payees never get it, and
 *  an unknown payee gets it without the quip or dodge credit — backing out of a first-time chemist
 *  must not farm the "backed out" rare (spec D5, GatePresentation's old rule). */
object PrePayCheck {
    fun decide(i: PrePayInput): PrePayDecision {
        val offender = i.verdict.offender
        if (i.verdict.severity == Severity.OK || offender == null || i.isNecessity || i.isFixedPayee) return PrePayDecision.Go
        val line = if (i.verdict.severity == Severity.BREACH) offender.budgetPaise else i.verdict.allowancePaise
        return PrePayDecision.Card(
            severity = i.verdict.severity,
            overPaise = offender.spentPaise + i.estimatePaise - line,
            scopeLabel = offender.label.takeIf { offender.categoryId != null },
            estimatePaise = i.estimatePaise,
            estimateSource = i.estimateSource,
            withQuip = i.payeeKnown,
            recordsDodge = i.payeeKnown,
        )
    }
}
