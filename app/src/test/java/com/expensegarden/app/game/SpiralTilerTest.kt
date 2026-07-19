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
}
