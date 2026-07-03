package com.expensegarden.app.gate

import org.junit.Assert.assertEquals
import org.junit.Test

class GateEvaluatorTest {
    // budget ₹10,000.00 = 1_000_000 paise; 30-day month
    private val budget = 1_000_000L

    @Test fun `no budget set means OK`() =
        assertEquals(Severity.OK, GateEvaluator.evaluate(999_999L, null, 50_000L, 15, 30))

    @Test fun `over budget is BREACH`() =
        assertEquals(Severity.BREACH, GateEvaluator.evaluate(950_000L, budget, 100_000L, 20, 30))

    @Test fun `exactly at budget on last day is not breach`() =
        assertEquals(Severity.OK, GateEvaluator.evaluate(900_000L, budget, 100_000L, 30, 30))

    @Test fun `ahead of pace is PACE_WARNING`() {
        // day 10/30: allowance = 10000 * 10/30 * 1.15 = ₹3,833.33. Spent 3000 + paying 1000 = 4000 > allowance
        assertEquals(Severity.PACE_WARNING, GateEvaluator.evaluate(300_000L, budget, 100_000L, 10, 30))
    }

    @Test fun `under pace is OK`() {
        // day 20/30: allowance = 10000 * 20/30 * 1.15 = ₹7,666.67. Spent 5000 + paying 1000 = 6000 < allowance
        assertEquals(Severity.OK, GateEvaluator.evaluate(500_000L, budget, 100_000L, 20, 30))
    }
}
