package com.expensegarden.app.stats

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.gate.GateEvaluator
import com.expensegarden.app.gate.Severity

data class ScopeStat(
    val categoryId: Long?,
    val name: String,
    val indent: Boolean,
    val spentPaise: Long,
    val budgetPaise: Long?,
    val severity: Severity,          // state severity (candidate 0); OK when unbudgeted
)

data class MonthStats(
    val spentPaise: Long,
    val overallBudgetPaise: Long?,
    val overallSeverity: Severity,
    val projectedPaise: Long,
    val perDayPaise: Long?,
    val rows: List<ScopeStat>,
)

object MonthStatsFolder {
    fun fold(
        categories: List<CategoryEntity>,
        leafSums: Map<Long, Long>,
        budgets: List<BudgetEntity>,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): MonthStats {
        val tree = CategoryTree(categories)
        val rolled = tree.rollupSums(leafSums)
        val budgetByScope = budgets.associateBy { it.categoryId }
        val total = leafSums.values.sum()
        val overall = budgetByScope[null]?.amountPaise

        fun stateSeverity(spent: Long, budget: Long?): Severity =
            GateEvaluator.evaluate(spent, budget, 0L, dayOfMonth, daysInMonth)

        fun rowFor(cat: CategoryEntity, indent: Boolean): ScopeStat {
            val spent = rolled[cat.id] ?: 0L
            val budget = budgetByScope[cat.id]?.amountPaise
            return ScopeStat(cat.id, cat.name, indent, spent, budget, stateSeverity(spent, budget))
        }

        val parents = categories.filter { it.parentId == null }.sortedBy { it.id }
        val rows = buildList {
            for (parent in parents) {
                add(rowFor(parent, indent = false))
                categories.filter { it.parentId == parent.id }.sortedBy { it.id }.forEach { child ->
                    val active = (rolled[child.id] ?: 0L) > 0L || budgetByScope.containsKey(child.id)
                    if (active) add(rowFor(child, indent = true))
                }
            }
        }

        return MonthStats(
            spentPaise = total,
            overallBudgetPaise = overall,
            overallSeverity = stateSeverity(total, overall),
            projectedPaise = PaceProjector.projectedMonthEndPaise(total, dayOfMonth, daysInMonth),
            perDayPaise = overall?.let { PaceProjector.perDayToStayUnderPaise(total, it, dayOfMonth, daysInMonth) },
            rows = rows,
        )
    }
}
