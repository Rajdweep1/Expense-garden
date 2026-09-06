package com.expensegarden.app.stats

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.gate.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthStatsFolderTest {
    private val categories = listOf(
        CategoryEntity(1, "Food & Drinks", null, false, updatedAt = 1L),
        CategoryEntity(3, "Transport", null, true, updatedAt = 1L),
        CategoryEntity(103, "Chai & Snacks", 1, false, updatedAt = 1L),
    )
    private fun budget(catId: Long?, paise: Long) = BudgetEntity(categoryId = catId, month = "2026-07", amountPaise = paise, updatedAt = 1L)

    @Test fun `header carries total, overall budget, projection`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 300_000L), listOf(budget(null, 1_000_000L)), 0L, 10, 30)
        assertEquals(300_000L, s.spentPaise)
        assertEquals(1_000_000L, s.overallBudgetPaise)
        assertEquals(900_000L, s.projectedPaise)                    // 3000₹ * 30/10
        assertEquals(33_333L, s.perDayPaise)                        // (10000₹-3000₹)/21 days left incl today
    }

    @Test fun `per-day uses remaining days including today`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 400_000L), listOf(budget(null, 1_000_000L)), 0L, 10, 30)
        assertEquals(28_571L, s.perDayPaise)                        // (10000-4000)/21
    }

    @Test fun `no overall budget means no per-day figure`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 400_000L), emptyList(), 0L, 10, 30)
        assertNull(s.overallBudgetPaise)
        assertNull(s.perDayPaise)
    }

    @Test fun `rows list parents always, children only when active`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 100L), emptyList(), 0L, 10, 30)
        assertEquals(listOf("Food & Drinks", "Chai & Snacks", "Transport"), s.rows.map { it.name })
        assertEquals(listOf(false, true, false), s.rows.map { it.indent })
    }

    @Test fun `inactive children are hidden`() {
        val s = MonthStatsFolder.fold(categories, emptyMap(), emptyList(), 0L, 10, 30)
        assertEquals(listOf("Food & Drinks", "Transport"), s.rows.map { it.name })
    }

    @Test fun `budgeted child appears even with zero spend and rows carry rolled sums and severity`() {
        val s = MonthStatsFolder.fold(
            categories, mapOf(103L to 60_000L), listOf(budget(1L, 50_000L), budget(103L, 100_000L)), 0L, 15, 30,
        )
        val food = s.rows.first { it.categoryId == 1L }
        assertEquals(60_000L, food.spentPaise)                      // rolled up from the child
        assertEquals(Severity.BREACH, food.severity)                // 600 > 500₹ budget
        val chai = s.rows.first { it.categoryId == 103L }
        // 600 vs own 1000₹ at day 15/30: pace line = 1000 * 15/30 * 1.15 = ₹575 < 600 → ahead of pace, not breached
        assertEquals(Severity.PACE_WARNING, chai.severity)
    }

    @Test fun `the projection adds fixed costs once instead of extrapolating them`() {
        // The dashboard's headline, on the real numbers from the 2026-09-06 review: ₹33,061
        // spent by day 6, of which ₹26,174 is rent, utilities and the SIP. The old formula
        // showed ₹1,65,305 by month end against actual months of ₹43-49k.
        val s = MonthStatsFolder.fold(
            categories, mapOf(103L to 3_306_100L), listOf(budget(null, 4_200_000L)),
            2_617_400L, 6, 30,
        )
        assertEquals(6_060_900L, s.projectedPaise)
    }

    @Test fun `a moderate spender is no longer warned just because rent landed on day one`() {
        // ₹18,000 rent plus ₹1,800 of variable spend by day 6. Before the fix the allowance was
        // ₹9,660 and this warned; the variable line is what should be judged, and ₹1,800 is
        // comfortably under it.
        val s = MonthStatsFolder.fold(
            categories, mapOf(103L to 1_980_000L), listOf(budget(null, 4_200_000L)),
            1_800_000L, 6, 30,
        )
        assertEquals(Severity.OK, s.overallSeverity)
    }

    @Test fun `genuinely overspending the variable half still warns`() {
        // The fix must not become a mute button. Same fixed rent, but ₹6,887 of variable spend
        // in six days against a ₹15,826 variable budget — that is real, and it should be said.
        val s = MonthStatsFolder.fold(
            categories, mapOf(103L to 3_306_100L), listOf(budget(null, 4_200_000L)),
            2_617_400L, 6, 30,
        )
        assertEquals(Severity.PACE_WARNING, s.overallSeverity)
    }

    @Test fun `a category row is never given the overall fixed allowance`() {
        // Rent is not inside Shopping. Handing every scope the overall exclusion would quietly
        // disable the per-category gate.
        val s = MonthStatsFolder.fold(
            categories, mapOf(103L to 60_000L), listOf(budget(103L, 100_000L)),
            2_617_400L, 15, 30,
        )
        assertEquals(Severity.PACE_WARNING, s.rows.first { it.categoryId == 103L }.severity)
    }
}
