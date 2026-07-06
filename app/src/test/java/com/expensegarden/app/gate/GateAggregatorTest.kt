package com.expensegarden.app.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GateAggregatorTest {
    // depth: overall=0, parent=1, child=2 (deeper = more specific)
    private val overall = ScopeInput(categoryId = null, label = "overall", budgetPaise = 1_000_000L, spentPaise = 0L, depth = 0)

    @Test fun `no scopes means OK and no offender`() {
        val v = GateAggregator.aggregate(emptyList(), 10_000L, 15, 30)
        assertEquals(Severity.OK, v.severity)
        assertNull(v.offender)
    }

    @Test fun `category breach beats overall ok`() {
        val food = ScopeInput(1L, "Food & Drinks", budgetPaise = 50_000L, spentPaise = 45_000L, depth = 1)
        val v = GateAggregator.aggregate(listOf(overall, food), 10_000L, 15, 30)
        assertEquals(Severity.BREACH, v.severity)
        assertEquals("Food & Drinks", v.offender?.label)
    }

    @Test fun `overall breach alone still fires`() {
        val v = GateAggregator.aggregate(listOf(overall.copy(spentPaise = 995_000L)), 10_000L, 15, 30)
        assertEquals(Severity.BREACH, v.severity)
        assertNull(v.offender?.categoryId)
    }

    @Test fun `at equal severity the deepest scope is named`() {
        // both parent and child breach; child (depth 2) must be the offender
        val parent = ScopeInput(1L, "Food & Drinks", budgetPaise = 10_000L, spentPaise = 9_000L, depth = 1)
        val child = ScopeInput(103L, "Chai & Snacks", budgetPaise = 5_000L, spentPaise = 4_500L, depth = 2)
        val v = GateAggregator.aggregate(listOf(overall, parent, child), 2_000L, 15, 30)
        assertEquals(Severity.BREACH, v.severity)
        assertEquals("Chai & Snacks", v.offender?.label)
    }

    @Test fun `pace warning surfaces when nothing breaches`() {
        // day 10/30 allowance on 10000₹ = ₹3,833.33; spent 3000₹ + 1000₹ = 4000₹ > allowance
        val v = GateAggregator.aggregate(listOf(overall.copy(spentPaise = 300_000L)), 100_000L, 10, 30)
        assertEquals(Severity.PACE_WARNING, v.severity)
        assertEquals("overall", v.offender?.label)
    }
}
