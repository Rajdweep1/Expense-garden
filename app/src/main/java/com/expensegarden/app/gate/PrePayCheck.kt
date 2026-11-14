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

object PrePayCheck {
    fun decide(i: PrePayInput): PrePayDecision = TODO()
}
