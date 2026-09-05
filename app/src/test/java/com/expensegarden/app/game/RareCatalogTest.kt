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

    @Test fun `picking is deterministic for the same event id`() {
        val a = RareCatalog.pick(RareTier.UNCOMMON, sourceEventId = 4242L)
        val b = RareCatalog.pick(RareTier.UNCOMMON, sourceEventId = 4242L)
        assertEquals(a, b)
    }

    @Test fun `picking spreads across the pool rather than always returning one species`() {
        val pool = RareCatalog.pool(RareTier.UNCOMMON)
        val picked = (1L..200L).map { RareCatalog.pick(RareTier.UNCOMMON, it).id }.toSet()
        assertTrue("expected >1 distinct species over 200 ids, got ${picked.size}", picked.size > 1)
        assertTrue(picked.size <= pool.size)
    }

    @Test fun `a negative event id still picks a valid species`() {
        // hashCode-derived seeds are routinely negative; a raw % would index out of bounds.
        val s = RareCatalog.pick(RareTier.UNCOMMON, sourceEventId = -99L)
        assertTrue(s in RareCatalog.pool(RareTier.UNCOMMON))
    }

    @Test fun `Int MIN_VALUE cannot break the pick`() {
        // absoluteValue of Int.MIN_VALUE is still negative in two's complement. Taking the
        // hashCode to Long BEFORE absoluteValue is what makes this safe, and this test is the
        // only thing that would catch it being reordered.
        val s = RareCatalog.pick(RareTier.RARE, sourceEventId = Int.MIN_VALUE.toLong())
        assertTrue(s in RareCatalog.pool(RareTier.RARE))
    }

    @Test fun `uncommon species name an existing archetype and rare species do not`() {
        // Uncommons are variants of something you already grow; rares are new archetypes.
        assertTrue(RareCatalog.pool(RareTier.UNCOMMON).all { it.baseArchetype != null })
        assertTrue(RareCatalog.pool(RareTier.RARE).all { it.baseArchetype == null })
        assertTrue(RareCatalog.pool(RareTier.LANDMARK).all { it.baseArchetype == null })
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
