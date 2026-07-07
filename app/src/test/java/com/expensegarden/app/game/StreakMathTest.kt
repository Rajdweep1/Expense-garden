package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Test

class StreakMathTest {
    // dayTotals[d] = paise spent on day d (1-based). Month of 30 days, ₹3000 budget → allowance/day grows 115/day-ish.
    private val budget = 300_000L

    @Test fun `no-spend days count fully past zero days only`() {
        // today = day 5; days 1..4 count, day 5 (today) excluded even at zero
        val totals = mapOf(2 to 10_000L)     // spent only on day 2
        assertEquals(3, StreakMath.noSpendDays(totals, today = 5))
    }

    @Test fun `streak counts consecutive under-pace days ending yesterday`() {
        // days 1..4 all under pace (tiny spends), today = 5 → streak 4
        val totals = mapOf(1 to 1_000L, 3 to 1_000L)
        assertEquals(4, StreakMath.underPaceStreak(totals, budget, today = 5, daysInMonth = 30))
    }

    @Test fun `a breach day resets the streak`() {
        // day 3 blows past day-3 allowance (300000*3/30*1.15 = 34500): spend 40000 that day
        val totals = mapOf(3 to 40_000L)
        assertEquals(1, StreakMath.underPaceStreak(totals, budget, today = 5, daysInMonth = 30))  // only day 4 counts
    }

    @Test fun `no budget means no streak but no-spend still counts`() {
        assertEquals(0, StreakMath.underPaceStreak(emptyMap(), null, today = 5, daysInMonth = 30))
        assertEquals(4, StreakMath.noSpendDays(emptyMap(), today = 5))
    }
}
