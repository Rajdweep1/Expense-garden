package com.expensegarden.app.stats

import com.expensegarden.app.data.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTreeTest {
    private fun cat(id: Long, parent: Long? = null) =
        CategoryEntity(id = id, name = "c$id", parentId = parent, isNecessity = false)

    // Seed-shaped: 1=Food (parent), 103=Chai (child of 1), 3=Transport, plus a synthetic depth-3 chain 6→601→9001
    private val tree = CategoryTree(listOf(
        cat(1), cat(3), cat(6), cat(103, parent = 1), cat(601, parent = 6), cat(9001, parent = 601),
    ))

    @Test fun `ancestor chain runs self first, root last`() =
        assertEquals(listOf(103L, 1L), tree.ancestorChain(103))

    @Test fun `ancestor chain of a root is just itself`() =
        assertEquals(listOf(3L), tree.ancestorChain(3))

    @Test fun `ancestor chain handles depth three`() =
        assertEquals(listOf(9001L, 601L, 6L), tree.ancestorChain(9001))

    @Test fun `rollup adds descendants into every ancestor`() {
        val rolled = tree.rollupSums(mapOf(103L to 2_000L, 1L to 500L, 9001L to 100L))
        assertEquals(2_500L, rolled[1L])      // own 500 + child 103's 2000
        assertEquals(2_000L, rolled[103L])
        assertEquals(100L, rolled[6L])        // grandchild rolls all the way up
        assertEquals(100L, rolled[601L])
        assertEquals(0L, rolled[3L])          // untouched category present with 0
    }
}
