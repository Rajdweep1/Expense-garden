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
        CategoryEntity(1, "Food & Drinks", null, false, updatedAt = 1L),
        CategoryEntity(101, "Restaurants", 1, false, updatedAt = 1L),
        CategoryEntity(102, "Delivery", 1, false, updatedAt = 1L),
        CategoryEntity(103, "Chai & Snacks", 1, false, updatedAt = 1L),
        CategoryEntity(2, "Groceries", null, true, updatedAt = 1L),
        CategoryEntity(3, "Transport", null, true, updatedAt = 1L),
        CategoryEntity(302, "Cab & Auto", 3, false, updatedAt = 1L),
        CategoryEntity(301, "Fuel", 3, true, updatedAt = 1L),
        CategoryEntity(4, "Housing", null, true, updatedAt = 1L),
        CategoryEntity(401, "Rent", 4, true, updatedAt = 1L),
        CategoryEntity(402, "Utilities", 4, true, updatedAt = 1L),
        CategoryEntity(5, "Health", null, true, updatedAt = 1L),
        CategoryEntity(6, "Entertainment", null, false, updatedAt = 1L),
        CategoryEntity(7, "Shopping", null, false, updatedAt = 1L),
        CategoryEntity(8, "Personal", null, false, updatedAt = 1L),
        CategoryEntity(9, "Family", null, true, updatedAt = 1L),
        CategoryEntity(10, "Investments", null, true, updatedAt = 1L),
        CategoryEntity(11, "Misc", null, false, updatedAt = 1L),
    ))

    private fun txn(cat: Long, paise: Long = 5_000, breached: Boolean = false, regret: Regret = Regret.UNRATED) =
        TransactionEntity(
            uuid = "u-$cat-$paise-$breached-$regret", amountPaise = paise, payeeId = 1,
            categoryId = cat, source = TxnSource.MANUAL, status = TxnStatus.LOGGED,
            breachedAtLogging = breached, regret = regret, note = null, occurredAt = 0L, createdAt = 0L, updatedAt = 1L,
        )

    @Test fun `discretionary maps to its family flower`() {
        assertEquals(Archetype.PETAL_FLOWER, PlantMapper.map(txn(101), tree)!!.archetype)   // Food family via child
        assertEquals(Archetype.BELL_FLOWER, PlantMapper.map(txn(6), tree)!!.archetype)
        assertEquals(Archetype.TULIP, PlantMapper.map(txn(7), tree)!!.archetype)
        assertEquals(Archetype.HERB_TUFT, PlantMapper.map(txn(8), tree)!!.archetype)
        assertEquals(Archetype.BUSH, PlantMapper.map(txn(11), tree)!!.archetype)
    }

    @Test fun `necessities get their root's dignified family and can never be weeds`() {
        val groceries = PlantMapper.map(txn(2, breached = true, regret = Regret.REGRET), tree)!!
        assertEquals(Archetype.VEGETABLE_ROW, groceries.archetype)
        assertFalse(groceries.isWeed)
        val transport = PlantMapper.map(txn(3), tree)!!
        assertEquals(Archetype.PERENNIAL_SHRUB, transport.archetype)
    }

    @Test fun `weed rule needs discretionary AND breach without regret`() {
        assertFalse(PlantMapper.map(txn(103), tree)!!.isWeed)
        assertTrue(PlantMapper.map(txn(103, breached = true), tree)!!.isWeed)
        assertFalse(PlantMapper.map(txn(103, regret = Regret.REGRET), tree)!!.isWeed)  // regret rises as a zombie, not a weed
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
        // Restaurants (101), not Chai & Snacks — 1C.7 gave 103 its own two-variant family, and
        // this test is about a three-variant archetype spreading across all its looks.
        val a = PlantMapper.map(txn(101), tree)!!
        assertEquals(a.variant, PlantMapper.map(txn(101), tree)!!.variant)
        assertTrue(a.variant in 0 until PlantMapper.variantCount(Archetype.PETAL_FLOWER))
        val seen = (1..60).map { PlantMapper.map(txn(101, paise = 1_000L + it), tree)!!.variant }.toSet()
        assertEquals(setOf(0, 1, 2), seen)                            // spread across all three looks
    }

    @Test fun `weeds stay single-variant`() {
        assertEquals(0, PlantMapper.map(txn(103, breached = true), tree)!!.variant)
        assertEquals(1, PlantMapper.variantCount(Archetype.THISTLE_WEED))
    }

    // ---- 1C.6 zombies: a regretted purchase rises on its own tile ----

    @Test fun `regret raises a zombie and beats both the category archetype and the weed rule`() {
        val z = PlantMapper.map(txn(103, regret = Regret.REGRET), tree)!!
        assertEquals(Archetype.ZOMBIE, z.archetype)
        assertFalse(z.isWeed)                                 // zombies are their own state, not weeds
        val zb = PlantMapper.map(txn(103, breached = true, regret = Regret.REGRET), tree)!!
        assertEquals(Archetype.ZOMBIE, zb.archetype)          // regret wins over breach
    }

    @Test fun `zombie size variant follows the tier of what died`() {
        assertEquals(0, PlantMapper.map(txn(103, paise = 5_000, regret = Regret.REGRET), tree)!!.variant)   // S
        assertEquals(1, PlantMapper.map(txn(103, paise = 50_000, regret = Regret.REGRET), tree)!!.variant)  // M
        assertEquals(2, PlantMapper.map(txn(103, paise = 500_000, regret = Regret.REGRET), tree)!!.variant) // L
        assertEquals(3, PlantMapper.variantCount(Archetype.ZOMBIE))
    }

    @Test fun `necessities can never zombify`() {
        val g = PlantMapper.map(txn(2, regret = Regret.REGRET), tree)!!
        assertEquals(Archetype.VEGETABLE_ROW, g.archetype)
    }

    @Test fun `breach without regret still weeds exactly as before`() {
        val w = PlantMapper.map(txn(103, breached = true), tree)!!
        assertTrue(w.isWeed)
        assertTrue(w.archetype == Archetype.THISTLE_WEED || w.archetype == Archetype.ODD_MUSHROOM)
    }

    // ---- 1C.7: the expanded cast ----

    @Test fun `necessity roots each grow their own family`() {
        // Before this, Groceries/Housing/Family all grew HEDGE and Transport/Health both grew
        // PERENNIAL_SHRUB — the island couldn't tell rent from a grocery run.
        assertEquals(Archetype.VEGETABLE_ROW, PlantMapper.map(txn(2), tree)!!.archetype)
        assertEquals(Archetype.PERENNIAL_SHRUB, PlantMapper.map(txn(3), tree)!!.archetype)
        assertEquals(Archetype.HEDGE, PlantMapper.map(txn(4), tree)!!.archetype)
        assertEquals(Archetype.SUCCULENT, PlantMapper.map(txn(5), tree)!!.archetype)
        assertEquals(Archetype.BERRY_BUSH, PlantMapper.map(txn(9), tree)!!.archetype)
    }

    @Test fun `a subcategory archetype overrides its root family`() {
        // Food & Drinks (root 1) is PETAL_FLOWER, but two of its subcats now differ.
        assertEquals(Archetype.PETAL_FLOWER, PlantMapper.map(txn(101), tree)!!.archetype)
        assertEquals(Archetype.CURL_VINE, PlantMapper.map(txn(102), tree)!!.archetype)
        assertEquals(Archetype.CHAI_CLUSTER, PlantMapper.map(txn(103), tree)!!.archetype)
        // An unmapped subcat still falls through to its root.
        assertEquals(Archetype.BELL_FLOWER, PlantMapper.map(txn(6), tree)!!.archetype)
    }

    @Test fun `a subcategory archetype still loses to regret and to the weed rule`() {
        // Precedence must stay: state (zombie/weed) beats identity (category family).
        assertEquals(Archetype.ZOMBIE, PlantMapper.map(txn(102, regret = Regret.REGRET), tree)!!.archetype)
        val w = PlantMapper.map(txn(102, breached = true), tree)!!
        assertTrue(w.isWeed)
        assertTrue(w.archetype == Archetype.THISTLE_WEED || w.archetype == Archetype.ODD_MUSHROOM)
    }

    @Test fun `every new archetype declares two sprite variants`() {
        listOf(
            Archetype.VEGETABLE_ROW, Archetype.SUCCULENT, Archetype.BERRY_BUSH,
            Archetype.CURL_VINE, Archetype.CHAI_CLUSTER,
        ).forEach { assertEquals(it.name, 2, PlantMapper.variantCount(it)) }
    }
}
