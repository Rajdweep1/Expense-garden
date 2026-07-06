package com.expensegarden.app.stats

import com.expensegarden.app.data.CategoryEntity

/** Quick-pick order: usage count desc (LOGGED txns, last 90 days), then seed order (id asc) as filler. */
object ChipOrder {
    fun topChips(categories: List<CategoryEntity>, usageCounts: Map<Long, Int>, limit: Int = 8): List<CategoryEntity> =
        categories
            .sortedWith(compareByDescending<CategoryEntity> { usageCounts[it.id] ?: 0 }.thenBy { it.id })
            .take(limit)
}
