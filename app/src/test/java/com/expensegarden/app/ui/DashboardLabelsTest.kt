package com.expensegarden.app.ui

import com.expensegarden.app.gate.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardLabelsTest {
    @Test fun `there is no pace without a budget`() {
        // Severity is OK when there is no budget, which the dashboard used to print as "on pace".
        assertNull(paceLine(Severity.OK, null))
        assertEquals("on pace", paceLine(Severity.OK, 1_000_000L))
        assertEquals("over pace", paceLine(Severity.PACE_WARNING, 1_000_000L))
        assertEquals("over budget", paceLine(Severity.BREACH, 1_000_000L))
    }

    @Test fun `the strip shows the streak only while the month is on pace`() {
        assertEquals("₹15,000 · on pace · 🌱17d  ›", stripLabel(1_500_000L, Severity.OK, streakDays = 17))
        // Over today's line already: today can't extend the streak, so showing it reads as good news.
        assertEquals("₹15,000 · over pace  ›", stripLabel(1_500_000L, Severity.PACE_WARNING, streakDays = 17))
        assertEquals("₹15,000 · over budget  ›", stripLabel(1_500_000L, Severity.BREACH, streakDays = 17))
        assertEquals("₹15,000 · on pace  ›", stripLabel(1_500_000L, Severity.OK, streakDays = 0))
    }

    @Test fun `the strip asks for a budget when there is none`() {
        assertEquals("set a budget  ›", stripLabel(null, Severity.OK, streakDays = 0))
    }

    @Test fun `unlogged payments are counted before the arrow`() {
        assertEquals("₹15,000 · over pace · 1 payment not logged  ›", stripLabel(1_500_000L, Severity.PACE_WARNING, 0, unlogged = 1))
        assertEquals("set a budget · 2 payments not logged  ›", stripLabel(null, Severity.OK, 0, unlogged = 2))
    }
}
