package com.expensegarden.app.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class PaceProjectorTest {
    @Test fun `linear projection scales spend to month length`() =
        assertEquals(900_000L, PaceProjector.projectedMonthEndPaise(300_000L, 10, 30))

    @Test fun `projection on day one is spend times month length`() =
        assertEquals(3_000_000L, PaceProjector.projectedMonthEndPaise(100_000L, 1, 30))

    @Test fun `per-day allowance divides remaining budget over remaining days incl today`() {
        // (10000₹ - 4000₹) / 21 days (day 10 of 30, today counts) = ₹285.71 → 28571 paise
        assertEquals(28_571L, PaceProjector.perDayToStayUnderPaise(400_000L, 1_000_000L, 10, 30))
    }

    @Test fun `per-day allowance floors at zero once budget is gone`() =
        assertEquals(0L, PaceProjector.perDayToStayUnderPaise(1_200_000L, 1_000_000L, 10, 30))

    @Test fun `per-day allowance on the last day is the whole remainder`() =
        assertEquals(50_000L, PaceProjector.perDayToStayUnderPaise(950_000L, 1_000_000L, 30, 30))
}
