package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiralTilerTest {

    @Test fun `side grows by rings with min one ring`() {
        assertEquals(4, SpiralTiler.gridSide(0))
        assertEquals(4, SpiralTiler.gridSide(8))     // C(1) = 8 fills ring 1
        assertEquals(6, SpiralTiler.gridSide(9))
        assertEquals(6, SpiralTiler.gridSide(28))    // C(2) = 28
        assertEquals(8, SpiralTiler.gridSide(29))
        assertEquals(10, SpiralTiler.gridSide(62))   // demo DB: C(3)=56 < 62 <= C(4)=92
    }

    @Test fun `reserved house and backyard tiles are never planted`() {
        val side = SpiralTiler.gridSide(60)
        val reserved = SpiralTiler.houseTiles(side) + SpiralTiler.backyardTiles(side)
        val tiles = SpiralTiler.tiles(60)
        assertEquals(60, tiles.size)
        assertTrue(tiles.none { it in reserved })
        assertEquals(4, SpiralTiler.houseTiles(side).size)
        assertEquals(4, SpiralTiler.backyardTiles(side).size)
    }

    @Test fun `ring one starts along the front edge like the old front row`() {
        assertEquals(
            listOf(Tile(0, 0), Tile(0, 1), Tile(0, 2), Tile(0, 3), Tile(1, 3), Tile(2, 3), Tile(2, 0), Tile(1, 0)),
            SpiralTiler.tiles(8),
        )
    }

    @Test fun `positions are stable relative to the house as the island grows`() {
        val small = SpiralTiler.tiles(9); val sSmall = SpiralTiler.gridSide(9)
        val big = SpiralTiler.tiles(60); val sBig = SpiralTiler.gridSide(60)
        fun rel(t: Tile, side: Int) = Tile(t.row - side / 2, t.col - side / 2)
        assertEquals(small.map { rel(it, sSmall) }, big.take(9).map { rel(it, sBig) })
    }

    @Test fun `every tile is unique and inside the grid`() {
        val n = 200
        val side = SpiralTiler.gridSide(n)
        val tiles = SpiralTiler.tiles(n)
        assertEquals(n, tiles.toSet().size)
        assertTrue(tiles.all { it.row in 0 until side && it.col in 0 until side })
    }

    // ---------- 4B: landmark plots ----------

    @Test fun `landmark count follows the house footprint ladder`() {
        // Landmarks are earned at house levels 3 and 4, which are exactly the levels where
        // footprint() grows. That makes the count derivable from f — no new parameter.
        assertEquals(0, SpiralTiler.landmarkCount(SpiralTiler.footprint(1)))
        assertEquals(0, SpiralTiler.landmarkCount(SpiralTiler.footprint(2)))
        assertEquals(1, SpiralTiler.landmarkCount(SpiralTiler.footprint(3)))
        assertEquals(2, SpiralTiler.landmarkCount(SpiralTiler.footprint(4)))
    }

    @Test fun `landmark plots are the tiles flanking the house on its own diagonal`() {
        // Order is load-bearing: index i is the plot for landmark ordinal i, which is why this
        // returns a List and not a Set. Asserting the tiles explicitly also catches a swapped
        // pair — the left plot must stay the left plot, or a landmark the user already owns
        // would jump sides the day the second one is earned.
        val f3 = 3
        val side3 = SpiralTiler.gridSide(20, f3)
        val lo3 = (side3 - f3) / 2
        assertEquals(listOf(Tile(lo3 - 1, lo3 - 1)), SpiralTiler.landmarkTiles(side3, f3))

        val f4 = 4
        val side4 = SpiralTiler.gridSide(40, f4)
        val lo4 = (side4 - f4) / 2
        assertEquals(
            listOf(Tile(lo4 - 1, lo4 - 1), Tile(lo4 + f4, lo4 + f4)),
            SpiralTiler.landmarkTiles(side4, f4),
        )
        // Screen-x tracks (row + col), so the first plot is genuinely the LEFT one: its
        // 2*lo - 2 sits below the house centre's 2*lo + f.
        assertTrue((lo4 - 1) + (lo4 - 1) < lo4 + lo4 + f4)
    }

    @Test fun `landmark plots never collide with the house the grove or the grid edge`() {
        // The exhaustive check from spec §2, kept as a regression. A collision here would
        // either hide the landmark under the house or delete a grove tree.
        for (level in 1..4) {
            val f = SpiralTiler.footprint(level)
            for (n in 0..120) {
                val side = SpiralTiler.gridSide(n, f)
                val house = SpiralTiler.houseTiles(side, f)
                val yard = SpiralTiler.backyardTiles(side, f)
                for (t in SpiralTiler.landmarkTiles(side, f)) {
                    assertTrue("$t collides with the house at level $level, n=$n", t !in house)
                    assertTrue("$t collides with the grove at level $level, n=$n", t !in yard)
                    assertTrue(
                        "$t is outside the ${side}x$side grid at level $level, n=$n",
                        t.row in 0 until side && t.col in 0 until side,
                    )
                }
            }
        }
    }

    @Test fun `landmark plots are never planted`() {
        val f = SpiralTiler.footprint(4)
        val side = SpiralTiler.gridSide(40, f)
        val tiles = SpiralTiler.tiles(40, f)
        assertEquals(40, tiles.size)
        assertTrue(tiles.none { it in SpiralTiler.landmarkTiles(side, f) })
    }

    @Test fun `capacity accounts for the reserved landmark plots`() {
        // 4k^2 + 4fk minus 4 grove tiles minus the landmark plots.
        assertEquals(4 + 12 - 5, SpiralTiler.capacity(1, 3))     // f=3: one landmark plot
        assertEquals(4 + 16 - 6, SpiralTiler.capacity(1, 4))     // f=4: two
    }

    @Test fun `the f equals two case is untouched by landmark reservation`() {
        // Every pre-4B assertion in this file uses the default f = 2. landmarkCount(2) is 0,
        // so the old capacity formula must hold exactly. If this fails, the reservation has
        // leaked into the 1C.6 case — fix the code, not the test.
        assertEquals(0, SpiralTiler.landmarkTiles(SpiralTiler.gridSide(20, 2), 2).size)
        for (k in 1..5) assertEquals(4 * k * k + 8 * k - 4, SpiralTiler.capacity(k, 2))
    }
}
