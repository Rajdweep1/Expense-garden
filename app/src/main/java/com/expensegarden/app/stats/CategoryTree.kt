package com.expensegarden.app.stats

import com.expensegarden.app.data.CategoryEntity

/** Pure view over the category taxonomy. Depth-agnostic, though the seed is two levels. */
class CategoryTree(categories: List<CategoryEntity>) {
    private val byId: Map<Long, CategoryEntity> = categories.associateBy { it.id }

    fun byId(id: Long): CategoryEntity? = byId[id]

    /** [self, parent, …, root]. Unknown ids yield an empty list. */
    fun ancestorChain(categoryId: Long): List<Long> {
        val chain = mutableListOf<Long>()
        var cursor = byId[categoryId]
        while (cursor != null) {
            chain += cursor.id
            cursor = cursor.parentId?.let { byId[it] }
        }
        return chain
    }

    /** Every known category id → own spend + all descendants' spend. Missing input = 0. */
    fun rollupSums(leafSums: Map<Long, Long>): Map<Long, Long> {
        val rolled = byId.keys.associateWith { 0L }.toMutableMap()
        for ((leafId, amount) in leafSums) {
            for (ancestorId in ancestorChain(leafId)) {
                rolled[ancestorId] = (rolled[ancestorId] ?: 0L) + amount
            }
        }
        return rolled
    }
}
