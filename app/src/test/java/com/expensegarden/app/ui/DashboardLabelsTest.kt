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
        assertEquals("ahead of pace", paceLine(Severity.PACE_WARNING, 1_000_000L))
        assertEquals("over budget", paceLine(Severity.BREACH, 1_000_000L))
    }
}
