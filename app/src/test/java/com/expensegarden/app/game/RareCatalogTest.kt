package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RareCatalogTest {

    @Test fun `every tier has a non-empty pool`() {
        // A tier with an empty pool would make the modulo in RareCatalog.pick divide by zero,
        // and the failure would surface as a crash inside the fold rather than here.
        for (tier in RareTier.values()) {
            assertTrue("$tier pool is empty", RareCatalog.pool(tier).isNotEmpty())
        }
    }

    @Test fun `species ids are unique across the whole catalogue`() {
        // Ids key the album's earned-set, so a collision would silently merge two species.
        val ids = RareTier.values().flatMap { RareCatalog.pool(it) }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `sprite names are unique across the whole catalogue`() {
        // Two species sharing a sprite name would render identically with no error anywhere.
        val names = RareCatalog.all().map { it.spriteName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun `picking is deterministic for the same seed and archetype`() {
        val a = RareCatalog.pick(RareTier.UNCOMMON, Archetype.TULIP, seed = 4242L)
        val b = RareCatalog.pick(RareTier.UNCOMMON, Archetype.TULIP, seed = 4242L)
        assertEquals(a, b)
    }

    @Test fun `a negative seed still picks a valid species`() {
        // hashCode-derived seeds are routinely negative; a raw % would index out of bounds.
        val s = RareCatalog.pick(RareTier.UNCOMMON, Archetype.TULIP, seed = -99L)
        assertTrue(s in RareCatalog.poolFor(RareTier.UNCOMMON, Archetype.TULIP))
    }

    @Test fun `Int MIN_VALUE cannot break the pick`() {
        // absoluteValue of Int.MIN_VALUE is still negative in two's complement. Taking the
        // hashCode to Long BEFORE absoluteValue is what makes this safe, and this test is the
        // only thing that would catch it being reordered.
        val s = RareCatalog.pick(RareTier.RARE, Archetype.TULIP, seed = Int.MIN_VALUE.toLong())
        assertTrue(s in RareCatalog.poolFor(RareTier.RARE, Archetype.TULIP))
    }

    @Test fun `an archetype with no rare form yields null rather than a wrong species`() {
        // THE honesty rule (spec §4). Returning some other species here is what would let a
        // Groceries purchase render as a tulip and make the garden misreport what was bought.
        assertEquals(null, RareCatalog.pick(RareTier.UNCOMMON, Archetype.ZOMBIE, seed = 1L))
        assertEquals(null, RareCatalog.pick(RareTier.UNCOMMON, Archetype.THISTLE_WEED, seed = 1L))
    }

    @Test fun `every plantable species names a base archetype and landmarks do not`() {
        // A plantable rare must be a form OF something, or it could not be assigned honestly.
        assertTrue(RareCatalog.pool(RareTier.UNCOMMON).all { it.baseArchetype != null })
        assertTrue(RareCatalog.pool(RareTier.RARE).all { it.baseArchetype != null })
        assertTrue(RareCatalog.pool(RareTier.LANDMARK).all { it.baseArchetype == null })
    }

    @Test fun `a species never claims an archetype that cannot grow it`() {
        // Weeds and zombies are outcomes, not categories — nothing should decorate them.
        val forbidden = setOf(Archetype.ZOMBIE, Archetype.THISTLE_WEED, Archetype.ODD_MUSHROOM)
        assertTrue(RareCatalog.all().none { it.baseArchetype in forbidden })
    }

    @Test fun `every species reports the tier of the pool it lives in`() {
        for (tier in RareTier.values()) {
            assertTrue(RareCatalog.pool(tier).all { it.tier == tier })
        }
    }

    @Test fun `byId finds every catalogued species and nothing else`() {
        for (s in RareCatalog.all()) assertEquals(s, RareCatalog.byId(s.id))
        assertEquals(null, RareCatalog.byId("no_such_species"))
    }
}
