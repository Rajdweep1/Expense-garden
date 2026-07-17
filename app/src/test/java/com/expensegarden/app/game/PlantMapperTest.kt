package com.expensegarden.app.game

import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import com.expensegarden.app.stats.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantMapperTest {
    // Mirror of the seed shape: necessity parents/children + discretionary families + investments (10)
    private val tree = CategoryTree(listOf(
        CategoryEntity(1, "Food & Drinks", null, false),
        CategoryEntity(103, "Chai & Snacks", 1, false),
        CategoryEntity(2, "Groceries", null, true),
        CategoryEntity(3, "Transport", null, true),
        CategoryEntity(302, "Cab & Auto", 3, false),
        CategoryEntity(301, "Fuel", 3, true),
        CategoryEntity(4, "Housing", null, true),
        CategoryEntity(401, "Rent", 4, true),
        CategoryEntity(402, "Utilities", 4, true),
        CategoryEntity(6, "Entertainment", null, false),
        CategoryEntity(7, "Shopping", null, false),
        CategoryEntity(8, "Personal", null, false),
        CategoryEntity(10, "Investments", null, true),
        CategoryEntity(11, "Misc", null, false),
    ))

    private fun txn(cat: Long, paise: Long = 5_000, breached: Boolean = false, regret: Regret = Regret.UNRATED) =
        TransactionEntity(
            uuid = "u-$cat-$paise-$breached-$regret", amountPaise = paise, payeeId = 1,
            categoryId = cat, source = TxnSource.MANUAL, status = TxnStatus.LOGGED,
            breachedAtLogging = breached, regret = regret, note = null, occurredAt = 0L, createdAt = 0L,
        )

    @Test fun `discretionary maps to its family flower`() {
        assertEquals(Archetype.PETAL_FLOWER, PlantMapper.map(txn(103), tree)!!.archetype)   // Food family via child
        assertEquals(Archetype.BELL_FLOWER, PlantMapper.map(txn(6), tree)!!.archetype)
        assertEquals(Archetype.TULIP, PlantMapper.map(txn(7), tree)!!.archetype)
        assertEquals(Archetype.HERB_TUFT, PlantMapper.map(txn(8), tree)!!.archetype)
        assertEquals(Archetype.BUSH, PlantMapper.map(txn(11), tree)!!.archetype)
    }

    @Test fun `necessities are hedges or perennials and can never be weeds`() {
        val groceries = PlantMapper.map(txn(2, breached = true, regret = Regret.REGRET), tree)!!
        assertEquals(Archetype.HEDGE, groceries.archetype)
        assertFalse(groceries.isWeed)
        val transport = PlantMapper.map(txn(3), tree)!!
        assertEquals(Archetype.PERENNIAL_SHRUB, transport.archetype)
    }

    @Test fun `weed rule needs discretionary AND breach or regret`() {
        assertFalse(PlantMapper.map(txn(103), tree)!!.isWeed)
        assertTrue(PlantMapper.map(txn(103, breached = true), tree)!!.isWeed)
        assertTrue(PlantMapper.map(txn(103, regret = Regret.REGRET), tree)!!.isWeed)
        // discretionary child under a necessity parent: own flag decides
        assertTrue(PlantMapper.map(txn(302, breached = true), tree)!!.isWeed)
    }

    @Test fun `weeds get a weed archetype chosen deterministically by uuid`() {
        val w = PlantMapper.map(txn(103, breached = true), tree)!!
        assertTrue(w.archetype == Archetype.THISTLE_WEED || w.archetype == Archetype.ODD_MUSHROOM)
        assertEquals(w.archetype, PlantMapper.map(txn(103, breached = true), tree)!!.archetype) // stable
    }

    @Test fun `size tiers split at 100 and 1000 rupees`() {
        assertEquals(SizeTier.S, PlantMapper.map(txn(103, paise = 9_999), tree)!!.sizeTier)
        assertEquals(SizeTier.M, PlantMapper.map(txn(103, paise = 10_000), tree)!!.sizeTier)
        assertEquals(SizeTier.M, PlantMapper.map(txn(103, paise = 99_999), tree)!!.sizeTier)
        assertEquals(SizeTier.L, PlantMapper.map(txn(103, paise = 100_000), tree)!!.sizeTier)
    }

    @Test fun `investments are not plot plants`() =
        assertNull(PlantMapper.map(txn(10, paise = 500_000), tree))

    // ---- 1C.5 variants ----

    @Test fun `subcategory-forced variants give landmark bills a fixed look`() {
        assertEquals(1, PlantMapper.map(txn(401), tree)!!.variant)   // Rent: the grand topiary
        assertEquals(2, PlantMapper.map(txn(402), tree)!!.variant)   // Utilities: square trim
        assertEquals(1, PlantMapper.map(txn(301), tree)!!.variant)   // Fuel: shrub variant
    }

    @Test fun `seed picks a stable variant within the archetype's count`() {
        val a = PlantMapper.map(txn(103), tree)!!
        assertEquals(a.variant, PlantMapper.map(txn(103), tree)!!.variant)
        assertTrue(a.variant in 0 until PlantMapper.variantCount(Archetype.PETAL_FLOWER))
        val seen = (1..60).map { PlantMapper.map(txn(103, paise = 1_000L + it), tree)!!.variant }.toSet()
        assertEquals(setOf(0, 1, 2), seen)                            // spread across all three looks
    }

    @Test fun `weeds stay single-variant`() {
        assertEquals(0, PlantMapper.map(txn(103, regret = Regret.REGRET), tree)!!.variant)
        assertEquals(1, PlantMapper.variantCount(Archetype.THISTLE_WEED))
    }
}
