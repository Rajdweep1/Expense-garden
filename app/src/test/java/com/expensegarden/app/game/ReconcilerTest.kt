package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class ReconcilerTest {
    @Test fun `closes every elapsed month with data exactly once`() {
        val out = Reconciler.decide(
            currentMonth = YearMonth.of(2026, 7),
            monthsWithData = listOf(YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7)),
            closedMonths = setOf("2026-05"),
            currentStreakDays = 0,
            streakHitDaysThisMonth = emptySet(),
        )
        assertEquals(listOf("2026-06"), out.monthsToClose)
    }

    @Test fun `current month never closes`() {
        val out = Reconciler.decide(
            currentMonth = YearMonth.of(2026, 7),
            monthsWithData = listOf(YearMonth.of(2026, 7)),
            closedMonths = emptySet(),
            currentStreakDays = 0,
            streakHitDaysThisMonth = emptySet(),
        )
        assertTrue(out.monthsToClose.isEmpty())
    }

    @Test fun `streak thresholds fire once each as the streak grows`() {
        val first = Reconciler.decide(
            currentMonth = YearMonth.of(2026, 7), monthsWithData = emptyList(), closedMonths = emptySet(),
            currentStreakDays = 8, streakHitDaysThisMonth = emptySet(),
        )
        assertEquals(listOf(3, 7), first.streakHitsToEmit)
        val second = Reconciler.decide(
            currentMonth = YearMonth.of(2026, 7), monthsWithData = emptyList(), closedMonths = emptySet(),
            currentStreakDays = 8, streakHitDaysThisMonth = setOf(3, 7),
        )
        assertTrue(second.streakHitsToEmit.isEmpty())
    }
}
