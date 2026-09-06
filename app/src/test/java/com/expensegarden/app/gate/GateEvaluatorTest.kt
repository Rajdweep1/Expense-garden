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

    @Test fun `pace allowance is day-proportional with grace`() {
        // 10000₹ budget, day 10/30: 10000 * 10/30 * 1.15 = ₹3,833.33 → 383333 paise (floor)
        assertEquals(383_333L, GateEvaluator.paceAllowancePaise(1_000_000L, 10, 30))
    }

    // ---------- fixed costs (review fix, 2026-09-06) ----------

    @Test fun `rent paid on day one no longer trips the pace warning`() {
        // The review's core damage. With ₹18,000 rent against a ₹42,000 budget the old
        // allowance was ₹9,660 on day 6 while spend stood at ₹33,061, so the gate raised a
        // dialog on essentially every payment from day 1 to day 22. A prompt that always
        // fires is one people learn to tap through, which spends the attention it exists to buy.
        val b = 4_200_000L
        val fixed = 1_800_000L
        assertEquals(Severity.OK, GateEvaluator.evaluate(fixed + 180_000L, b, 0L, fixed, 6, 30))
    }

    @Test fun `genuine overspending on the variable half still warns`() {
        val b = 4_200_000L
        val fixed = 1_800_000L
        assertEquals(Severity.PACE_WARNING, GateEvaluator.evaluate(fixed + 1_500_000L, b, 0L, fixed, 6, 30))
    }

    @Test fun `a breach is still a breach regardless of what is fixed`() {
        // Over the budget is over the budget. The fixed term only ever moves the PACE line.
        assertEquals(Severity.BREACH, GateEvaluator.evaluate(4_300_000L, 4_200_000L, 0L, 1_800_000L, 6, 30))
    }

    @Test fun `fixed spend exceeding the budget leaves no variable allowance`() {
        // Rent alone blew the budget. Every further payment is a breach, not a pace nudge.
        assertEquals(Severity.BREACH, GateEvaluator.evaluate(4_500_000L, 4_200_000L, 0L, 4_500_000L, 6, 30))
    }

    @Test fun `zero fixed spend reproduces the old behaviour exactly`() {
        // The two-arg overload keeps every pre-existing caller identical, which is what makes
        // this change safe to land before every call site has been threaded.
        for (day in 1..30) {
            assertEquals(
                GateEvaluator.paceAllowancePaise(budget, day, 30),
                GateEvaluator.paceAllowancePaise(budget, 0L, day, 30),
            )
        }
    }
}
