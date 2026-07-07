package com.expensegarden.app.render

import com.expensegarden.app.game.Tile
import org.junit.Assert.assertEquals
import org.junit.Test

class IsoMathTest {
    // tileW 80, tileH 40, origin (300, 100): classic 2:1 diamonds
    private val iso = IsoMath(tileW = 80f, tileH = 40f, originX = 300f, originY = 100f)

    @Test fun `projects rows toward the horizon and cols to the right`() {
        assertEquals(300f to 100f, iso.tileCenterX(Tile(0, 0)) to iso.tileCenterY(Tile(0, 0)))
        assertEquals(340f to 120f, iso.tileCenterX(Tile(0, 1)) to iso.tileCenterY(Tile(0, 1)))
        // higher row = further back = up-left on screen
        assertEquals(260f to 120f, iso.tileCenterX(Tile(1, 0)) to iso.tileCenterY(Tile(1, 0)))
    }

    @Test fun `inverse recovers the tile from a screen point`() {
        val t = Tile(2, 3)
        assertEquals(t, iso.tileAt(iso.tileCenterX(t), iso.tileCenterY(t)))
        assertEquals(t, iso.tileAt(iso.tileCenterX(t) + 10f, iso.tileCenterY(t) - 5f))  // within the diamond
    }

    @Test fun `draw order sorts back rows first`() {
        val order = listOf(Tile(0, 0), Tile(2, 1), Tile(1, 4)).sortedByDescending { iso.depth(it) }
        assertEquals(Tile(2, 1), order[0])   // depth = row is the primary key; front row draws last
    }

    @Test fun `fit scales the grid into a viewport`() {
        val fitted = IsoMath.fit(gridRows = 4, gridCols = 5, viewportW = 1080f, viewportH = 1400f, topReserve = 300f, bottomReserve = 300f)
        // whole 4x5 field must land inside the viewport horizontally
        val leftMost = fitted.tileCenterX(Tile(3, 0)) - fitted.tileW / 2
        val rightMost = fitted.tileCenterX(Tile(0, 4)) + fitted.tileW / 2
        assert(leftMost >= 0f && rightMost <= 1080f)
    }
}
