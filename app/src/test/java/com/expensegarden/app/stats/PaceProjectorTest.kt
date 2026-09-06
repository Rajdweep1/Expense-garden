package com.expensegarden.app.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class PaceProjectorTest {
    @Test fun `linear projection scales spend to month length`() =
        assertEquals(900_000L, PaceProjector.projectedMonthEndPaise(300_000L, 0L, 10, 30))

    @Test fun `projection on day one is spend times month length`() =
        assertEquals(3_000_000L, PaceProjector.projectedMonthEndPaise(100_000L, 0L, 1, 30))

    @Test fun `a fixed cost is added once, never extrapolated`() {
        // THE review finding in one assertion. ₹33,061 spent by day 6, of which ₹26,174 is
        // rent, utilities and the SIP. The old formula gave ₹1,65,305 against actual months
        // of ₹43–49k, and that number was the dashboard's headline.
        val projected = PaceProjector.projectedMonthEndPaise(3_306_100L, 2_617_400L, 6, 30)
        assertEquals(2_617_400L + (3_306_100L - 2_617_400L) * 30 / 6, projected)
        assertEquals(6_060_900L, projected)
    }

    @Test fun `an all-fixed month projects to exactly what has been spent`() =
        assertEquals(1_800_000L, PaceProjector.projectedMonthEndPaise(1_800_000L, 1_800_000L, 1, 30))

    @Test fun `fixed spend above total cannot drive the projection below spend`() =
        assertEquals(500_000L, PaceProjector.projectedMonthEndPaise(500_000L, 900_000L, 5, 30))

    @Test fun `per-day allowance divides remaining budget over remaining days incl today`() {
        // (10000₹ - 4000₹) / 21 days (day 10 of 30, today counts) = ₹285.71 → 28571 paise
        assertEquals(28_571L, PaceProjector.perDayToStayUnderPaise(400_000L, 1_000_000L, 10, 30))
    }

    @Test fun `per-day allowance floors at zero once budget is gone`() =
        assertEquals(0L, PaceProjector.perDayToStayUnderPaise(1_200_000L, 1_000_000L, 10, 30))

    @Test fun `per-day allowance on the last day is the whole remainder`() =
        assertEquals(50_000L, PaceProjector.perDayToStayUnderPaise(950_000L, 1_000_000L, 30, 30))
}
