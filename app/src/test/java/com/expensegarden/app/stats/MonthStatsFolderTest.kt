package com.expensegarden.app.stats

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.gate.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthStatsFolderTest {
    private val categories = listOf(
        CategoryEntity(1, "Food & Drinks", null, false),
        CategoryEntity(3, "Transport", null, true),
        CategoryEntity(103, "Chai & Snacks", 1, false),
    )
    private fun budget(catId: Long?, paise: Long) = BudgetEntity(categoryId = catId, month = "2026-07", amountPaise = paise)

    @Test fun `header carries total, overall budget, projection`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 300_000L), listOf(budget(null, 1_000_000L)), 10, 30)
        assertEquals(300_000L, s.spentPaise)
        assertEquals(1_000_000L, s.overallBudgetPaise)
        assertEquals(900_000L, s.projectedPaise)                    // 3000₹ * 30/10
        assertEquals(33_333L, s.perDayPaise)                        // (10000₹-3000₹)/21 days left incl today
    }

    @Test fun `per-day uses remaining days including today`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 400_000L), listOf(budget(null, 1_000_000L)), 10, 30)
        assertEquals(28_571L, s.perDayPaise)                        // (10000-4000)/21
    }

    @Test fun `no overall budget means no per-day figure`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 400_000L), emptyList(), 10, 30)
        assertNull(s.overallBudgetPaise)
        assertNull(s.perDayPaise)
    }

    @Test fun `rows list parents always, children only when active`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 100L), emptyList(), 10, 30)
        assertEquals(listOf("Food & Drinks", "Chai & Snacks", "Transport"), s.rows.map { it.name })
        assertEquals(listOf(false, true, false), s.rows.map { it.indent })
    }

    @Test fun `inactive children are hidden`() {
        val s = MonthStatsFolder.fold(categories, emptyMap(), emptyList(), 10, 30)
        assertEquals(listOf("Food & Drinks", "Transport"), s.rows.map { it.name })
    }

    @Test fun `budgeted child appears even with zero spend and rows carry rolled sums and severity`() {
        val s = MonthStatsFolder.fold(
            categories, mapOf(103L to 60_000L), listOf(budget(1L, 50_000L), budget(103L, 100_000L)), 15, 30,
        )
        val food = s.rows.first { it.categoryId == 1L }
        assertEquals(60_000L, food.spentPaise)                      // rolled up from the child
        assertEquals(Severity.BREACH, food.severity)                // 600 > 500₹ budget
        val chai = s.rows.first { it.categoryId == 103L }
        // 600 vs own 1000₹ at day 15/30: pace line = 1000 * 15/30 * 1.15 = ₹575 < 600 → ahead of pace, not breached
        assertEquals(Severity.PACE_WARNING, chai.severity)
    }
}
