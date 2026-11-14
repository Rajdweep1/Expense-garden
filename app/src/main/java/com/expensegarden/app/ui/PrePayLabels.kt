package com.expensegarden.app.ui

import com.expensegarden.app.core.Money
import com.expensegarden.app.gate.EstimateSource
import com.expensegarden.app.gate.PrePayDecision
import com.expensegarden.app.gate.Severity

/** The pre-payment card's words. Pure, so the copy is JVM-tested. */
object PrePayLabels {
    fun headline(card: PrePayDecision.Card, payee: String): String {
        val line = if (card.severity == Severity.BREACH) "budget" else "pace"
        val scope = "this month's " + (card.scopeLabel?.let { "$it " } ?: "") + line
        val over = Money.display(card.overPaise)
        return when (card.estimateSource) {
            EstimateSource.NONE -> "You're $over over $scope. Still pay $payee?"
            EstimateSource.LAST_LOGGED -> "Your usual ${Money.display(card.estimatePaise)} to $payee puts you $over over $scope. Still pay?"
            EstimateSource.QR_FIXED -> "This ${Money.display(card.estimatePaise)} to $payee puts you $over over $scope. Still pay?"
        }
    }
}
