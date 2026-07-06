package com.expensegarden.app.stats

import com.expensegarden.app.data.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChipOrderTest {
    private val cats = (1L..11L).map { CategoryEntity(it, "c$it", null, false) } +
        listOf(CategoryEntity(103, "chai", 1, false))

    @Test fun `used categories lead, by count desc, tie broken by lower id`() {
        val chips = ChipOrder.topChips(cats, mapOf(103L to 5, 3L to 2, 7L to 2), limit = 4)
        assertEquals(listOf(103L, 3L, 7L, 1L), chips.map { it.id })   // 3 before 7 (tie, lower id); fill with id-asc
    }

    @Test fun `thin history fills with seed order`() {
        val chips = ChipOrder.topChips(cats, emptyMap(), limit = 8)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), chips.map { it.id })
    }

    @Test fun `limit caps the list`() =
        assertEquals(8, ChipOrder.topChips(cats, mapOf(103L to 1), limit = 8).size)
}
