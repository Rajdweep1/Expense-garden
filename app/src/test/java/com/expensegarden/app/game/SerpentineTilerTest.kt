package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Test

class SerpentineTilerTest {
    @Test fun `fills front row left to right then snakes back`() {
        val tiles = SerpentineTiler.tiles(7)
        assertEquals(Tile(0, 0), tiles[0])
        assertEquals(Tile(0, 4), tiles[4])
        assertEquals(Tile(1, 4), tiles[5])   // next row starts from the right (serpentine)
        assertEquals(Tile(1, 3), tiles[6])
    }

    @Test fun `grid keeps a minimum 4 rows and grows beyond 20 plants`() {
        assertEquals(4, SerpentineTiler.gridRows(1))
        assertEquals(4, SerpentineTiler.gridRows(20))
        assertEquals(5, SerpentineTiler.gridRows(21))
        assertEquals(5, SerpentineTiler.COLS)
    }

    @Test fun `assignment is a pure function of index`() =
        assertEquals(SerpentineTiler.tiles(30)[12], SerpentineTiler.tiles(13)[12])
}
