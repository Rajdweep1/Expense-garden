package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.7: the house block is f×f. SpiralTilerTest covers f = 2 (the 1C.6 case) and must keep
 *  passing untouched — this file covers the new f = 3 and f = 4 rungs. */
class SpiralTilerFootprintTest {

    @Test fun `footprint ladder is 2 2 3 4`() {
        assertEquals(2, SpiralTiler.footprint(1))
        assertEquals(2, SpiralTiler.footprint(2))
        assertEquals(3, SpiralTiler.footprint(3))
        assertEquals(4, SpiralTiler.footprint(4))
        assertEquals(2, SpiralTiler.footprint(0))    // clamped
        assertEquals(4, SpiralTiler.footprint(9))    // clamped
    }

    @Test fun `capacity counts the full square minus every reserved plot`() {
        // side = f + 2k, so side² − f² − 4 grove tiles − the landmark plots must equal
        // capacity(k, f). 4B added the last term: this test parameterises f over {2, 3, 4},
        // so unlike everything in SpiralTilerTest it does see the new reservation. At f = 2
        // landmarkCount is 0 and the original identity is unchanged, which is what says the
        // reservation has not leaked into the 1C.6 case.
        for (f in listOf(2, 3, 4)) {
            for (k in 1..4) {
                val side = f + 2 * k
                val reserved = 4 + SpiralTiler.landmarkCount(f)
                assertEquals("f=$f k=$k", side * side - f * f - reserved, SpiralTiler.capacity(k, f))
            }
        }
    }

    @Test fun `side and footprint always share parity`() {
        for (f in listOf(2, 3, 4)) {
            for (n in listOf(0, 1, 12, 40, 200)) {
                val side = SpiralTiler.gridSide(n, f)
                assertEquals("f=$f n=$n", f % 2, side % 2)
            }
        }
    }

    @Test fun `house block is centered and f by f`() {
        assertEquals(9, SpiralTiler.houseTiles(5, 3).size)
        assertEquals(16, SpiralTiler.houseTiles(6, 4).size)
        assertTrue(SpiralTiler.houseTiles(5, 3).all { it.row in 1..3 && it.col in 1..3 })
        assertTrue(SpiralTiler.houseTiles(6, 4).all { it.row in 1..4 && it.col in 1..4 })
    }

    @Test fun `grove is four tiles on the row behind the house`() {
        // Placement table from spec §1.
        assertEquals((0..3).map { Tile(3, it) }.toSet(), SpiralTiler.backyardTiles(4, 2))
        assertEquals((0..3).map { Tile(4, it) }.toSet(), SpiralTiler.backyardTiles(5, 3))
        assertEquals((1..4).map { Tile(5, it) }.toSet(), SpiralTiler.backyardTiles(6, 4))
    }

    @Test fun `reserved tiles are never planted at any footprint`() {
        for (f in listOf(3, 4)) {
            val n = 40
            val side = SpiralTiler.gridSide(n, f)
            val reserved = SpiralTiler.houseTiles(side, f) + SpiralTiler.backyardTiles(side, f)
            val tiles = SpiralTiler.tiles(n, f)
            assertEquals("f=$f", n, tiles.size)
            assertEquals("f=$f unique", n, tiles.toSet().size)
            assertTrue("f=$f reserved", tiles.none { it in reserved })
            assertTrue("f=$f bounds", tiles.all { it.row in 0 until side && it.col in 0 until side })
        }
    }

    @Test fun `chronological order is preserved across a footprint change`() {
        // The re-layout shuffles plants outward but must not reorder them: plant i stays plant i.
        val small = SpiralTiler.tiles(20, 2)
        val big = SpiralTiler.tiles(20, 3)
        assertEquals(20, small.size)
        assertEquals(20, big.size)
        assertEquals(small.size, big.size)
        // Ring index (Chebyshev distance from the house block) must be non-decreasing in both.
        fun ring(t: Tile, side: Int, f: Int): Int {
            val lo = (side - f) / 2
            val dr = maxOf(lo - t.row, t.row - (lo + f - 1), 0)
            val dc = maxOf(lo - t.col, t.col - (lo + f - 1), 0)
            return maxOf(dr, dc)
        }
        val rSmall = small.map { ring(it, SpiralTiler.gridSide(20, 2), 2) }
        val rBig = big.map { ring(it, SpiralTiler.gridSide(20, 3), 3) }
        assertEquals(rSmall.sorted(), rSmall)
        assertEquals(rBig.sorted(), rBig)
    }
}
