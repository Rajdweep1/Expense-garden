package com.expensegarden.app.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrePayCheckTest {
    private val overall = ScopeInput(categoryId = null, label = "Overall", budgetPaise = 1_000_000, spentPaise = 700_000, depth = 0)
    private val food = ScopeInput(categoryId = 1L, label = "Food & Drinks", budgetPaise = 300_000, spentPaise = 310_000, depth = 1)

    private fun input(
        verdict: GateVerdict, estimate: Long = 0, source: EstimateSource = EstimateSource.NONE,
        known: Boolean = true, necessity: Boolean = false, fixed: Boolean = false,
    ) = PrePayInput(verdict, estimate, source, payeeKnown = known, isNecessity = necessity, isFixedPayee = fixed)

    @Test fun `an OK month goes straight to payment`() =
        assertEquals(PrePayDecision.Go, PrePayCheck.decide(input(GateVerdict(Severity.OK, null))))

    @Test fun `over pace with a known payee shows the card with a quip and dodge credit`() {
        val card = PrePayCheck.decide(input(GateVerdict(Severity.PACE_WARNING, overall, allowancePaise = 600_000))) as PrePayDecision.Card
        assertEquals(Severity.PACE_WARNING, card.severity)
        assertEquals(100_000L, card.overPaise)          // 7,000 spent − 6,000 allowance
        assertNull(card.scopeLabel)
        assertEquals(true, card.withQuip)
        assertEquals(true, card.recordsDodge)
    }

    @Test fun `a necessity or a fixed payee never gets the card`() {
        val breach = GateVerdict(Severity.BREACH, overall)
        assertEquals(PrePayDecision.Go, PrePayCheck.decide(input(breach, necessity = true)))
        assertEquals(PrePayDecision.Go, PrePayCheck.decide(input(breach, fixed = true)))
    }

    @Test fun `an unknown payee gets the card without a quip or dodge credit`() {
        val over = overall.copy(spentPaise = 1_050_000)
        val card = PrePayCheck.decide(input(GateVerdict(Severity.BREACH, over), known = false)) as PrePayDecision.Card
        assertEquals(50_000L, card.overPaise)           // 10,500 spent − 10,000 budget
        assertEquals(false, card.withQuip)
        assertEquals(false, card.recordsDodge)
    }

    @Test fun `the estimate counts toward how far over, and a category offender is named`() {
        val card = PrePayCheck.decide(
            input(GateVerdict(Severity.BREACH, food), estimate = 20_000, source = EstimateSource.LAST_LOGGED),
        ) as PrePayDecision.Card
        assertEquals(30_000L, card.overPaise)           // 3,100 + 200 − 3,000
        assertEquals("Food & Drinks", card.scopeLabel)
        assertEquals(20_000L, card.estimatePaise)
        assertEquals(EstimateSource.LAST_LOGGED, card.estimateSource)
    }

    // Spec D1: late in the month the pace line sits above the budget, so only the estimate can warn.
    @Test fun `day 28 with fifty rupees left and a usual two-thousand payee shows the card`() {
        val scope = overall.copy(spentPaise = 995_000)   // ₹9,950 of ₹10,000
        fun decide(estimate: Long, day: Int) = PrePayCheck.decide(
            input(GateAggregator.aggregate(listOf(scope), estimate, 0, day, 31), estimate, EstimateSource.LAST_LOGGED),
        )
        assertEquals(PrePayDecision.Go, decide(0, 28))
        assertEquals(PrePayDecision.Go, decide(0, 27))
        val card = decide(200_000, 28) as PrePayDecision.Card
        assertEquals(Severity.BREACH, card.severity)
        assertEquals(195_000L, card.overPaise)
        assertEquals(PrePayDecision.Go, decide(5_000, 28))   // exactly at the budget is not over
    }
}
