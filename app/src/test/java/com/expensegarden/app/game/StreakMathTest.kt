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

    @Test fun `days before the ledger began never count`() {
        // Installed on day 10 of a 30-day month, today is 13, nothing logged. Days 1..9 were never
        // observed and cannot count — but 10, 11 and 12 were, and were under pace, so 3 is earned.
        assertEquals(3, StreakMath.underPaceStreak(emptyMap(), budget, today = 13, daysInMonth = 30, firstObservedDay = 10))
    }

    @Test fun `installing today earns nothing yet`() {
        // The bug this parameter exists for: a fresh install on the 13th used to report 12 days,
        // which is enough to fire STREAK_7 and grow a rare the user never earned.
        assertEquals(0, StreakMath.underPaceStreak(emptyMap(), budget, today = 13, daysInMonth = 30, firstObservedDay = 13))
    }

    @Test fun `a genuine no-spend run from day one is still earned`() {
        // Installed on day 1, spent nothing through day 19. That streak was earned.
        assertEquals(19, StreakMath.underPaceStreak(emptyMap(), budget, today = 20, daysInMonth = 30, firstObservedDay = 1))
    }

    @Test fun `no-spend days ignore days before the ledger began`() {
        // Same rule as the streak: an unobserved day is not a no-spend day. Four sparkles for
        // days the app was not installed is the cosmetic half of the same lie.
        assertEquals(3, StreakMath.noSpendDays(emptyMap(), today = 13, firstObservedDay = 10))
        assertEquals(0, StreakMath.noSpendDays(emptyMap(), today = 13, firstObservedDay = 13))
    }

    @Test fun `the baseline only trims the start, it does not rescue a breach`() {
        // Observed from day 2; day 3 blows the allowance, so only day 4 survives.
        val totals = mapOf(3 to 40_000L)
        assertEquals(1, StreakMath.underPaceStreak(totals, budget, today = 5, daysInMonth = 30, firstObservedDay = 2))
    }

    @Test fun `fixed costs move the streak's line the way they move the gate's`() {
        val days = mapOf(1 to 1_010_000L) + (2..9).associateWith { 10_000L }   // ₹10,000 rent + ₹100/day
        assertEquals(9, StreakMath.underPaceStreak(days, 1_500_000, 10, 31, fixedByDayPaise = mapOf(1 to 1_000_000L)))
        assertEquals(0, StreakMath.underPaceStreak(days, 1_500_000, 10, 31))   // the old line: rent fails day 1
    }

    // ₹15,000 carried, raised to ₹20,000 on the 28th of a 31-day month.
    private val raisedOn28th = (1..27).associateWith { 1_500_000L } + (28..31).associateWith { 2_000_000L }

    @Test fun `a raise late in the month earns no streak for the days before it`() {
        // ₹643 a day is over ₹15,000's line every day and under ₹20,000's.
        val spend = (1..28).associateWith { 64_300L }
        assertEquals(1, StreakMath.underPaceStreak(spend, 2_000_000, 29, 31, budgetByDayPaise = raisedOn28th))
    }

    @Test fun `a streak earned under the old budget survives a raise`() {
        val spend = (1..28).associateWith { 40_000L }   // ₹400 a day: under both lines
        assertEquals(28, StreakMath.underPaceStreak(spend, 2_000_000, 29, 31, budgetByDayPaise = raisedOn28th))
    }

    @Test fun `a day with no budget in force can't extend the streak`() {
        val setOn18th = (18..31).associateWith { 1_500_000L }
        assertEquals(1, StreakMath.underPaceStreak(emptyMap(), 1_500_000, 19, 31, budgetByDayPaise = setOn18th))
    }
}
