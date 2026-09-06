package com.expensegarden.app.render

import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.Plant
import com.expensegarden.app.game.SizeTier
import com.expensegarden.app.game.Tile
import com.expensegarden.app.game.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpriteDemandTest {

    private fun plant(archetype: Archetype, variant: Int) =
        Plant("t-${archetype.name}-$variant", archetype, SizeTier.M, false, Tile(0, 0), 1, variant)

    private fun state(plants: List<Plant>, backRowTreeCount: Int = 0) = GardenState(
        monthKey = "2026-09", weather = Weather.SUNNY, plants = plants, spentPaise = 0,
        backRowTreeCount = backRowTreeCount, trunkTier = 0, butterflies = 0, streakDays = 0,
        noSpendDays = 0, archived = false, gridRows = 4, gridCols = 4,
    )

    @Test fun `every planted archetype and variant is demanded`() {
        val keys = SpriteDemand.of(state(listOf(plant(Archetype.TULIP, 2), plant(Archetype.HEDGE, 0))))
        assertTrue(keys.contains(Archetype.TULIP to 2))
        assertTrue(keys.contains(Archetype.HEDGE to 0))
    }

    @Test fun `variant zero is demanded alongside a rare variant`() {
        // SpritePainter falls back to `archetype to 0` before giving up on sprites entirely,
        // so warming a rare without its base leaves the fallback path decoding during a frame.
        val keys = SpriteDemand.of(state(listOf(plant(Archetype.TULIP, 3))))
        assertTrue("base look must be warmed too", keys.contains(Archetype.TULIP to 0))
    }

    @Test fun `the tree is always demanded`() {
        // GardenCanvas synthesises Plant(..., Archetype.TREE, ...) for the back row and the
        // grove at lines 378 and 555 — neither is in state.plants, and both use variant 0.
        assertTrue(SpriteDemand.of(state(emptyList())).contains(Archetype.TREE to 0))
    }

    @Test fun `duplicate plants collapse to one key`() {
        val many = (1..20).map { plant(Archetype.TULIP, 1) }
        val tulips = SpriteDemand.of(state(many)).filter { it.first == Archetype.TULIP }
        assertEquals(setOf(Archetype.TULIP to 1, Archetype.TULIP to 0), tulips.toSet())
    }

    @Test fun `an empty garden still demands only the tree`() {
        assertEquals(setOf(Archetype.TREE to 0), SpriteDemand.of(state(emptyList())))
    }
}
