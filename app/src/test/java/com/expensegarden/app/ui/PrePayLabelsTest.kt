package com.expensegarden.app.ui

import com.expensegarden.app.gate.EstimateSource
import com.expensegarden.app.gate.PrePayDecision
import com.expensegarden.app.gate.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

class PrePayLabelsTest {
    private fun card(
        severity: Severity = Severity.PACE_WARNING, over: Long = 230_000, scope: String? = null,
        estimate: Long = 0, source: EstimateSource = EstimateSource.NONE,
    ) = PrePayDecision.Card(severity, over, scope, estimate, source, withQuip = true, recordsDodge = true)

    @Test fun `with no estimate it says how far over the month already is`() = assertEquals(
        "You're ₹2,300 over this month's pace. Still pay Sharma Chai?",
        PrePayLabels.headline(card(), "Sharma Chai"),
    )

    @Test fun `a usual amount is named as usual`() = assertEquals(
        "Your usual ₹600 to Sharma Chai puts you ₹230 over this month's budget. Still pay?",
        PrePayLabels.headline(card(Severity.BREACH, 23_000, estimate = 60_000, source = EstimateSource.LAST_LOGGED), "Sharma Chai"),
    )

    @Test fun `a QR's own amount is named as this payment`() = assertEquals(
        "This ₹600 to Sharma Chai puts you ₹230 over this month's Food & Drinks budget. Still pay?",
        PrePayLabels.headline(card(Severity.BREACH, 23_000, "Food & Drinks", 60_000, EstimateSource.QR_FIXED), "Sharma Chai"),
    )
}
