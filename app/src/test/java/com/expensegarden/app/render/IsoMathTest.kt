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

    @Test fun `fit centers the island in a tall viewport instead of hugging the top`() {
        // Real home framing: 1080x2400 phone, strip reserve 300, FAB reserve 320.
        val fitted = IsoMath.fit(gridRows = 5, gridCols = 5, viewportW = 1080f, viewportH = 2400f, topReserve = 300f, bottomReserve = 320f)
        val islandTop = fitted.tileCenterY(Tile(0, 0)) - fitted.tileH / 2
        val islandBottom = fitted.tileCenterY(Tile(4, 4)) + fitted.tileH / 2 + fitted.tileH * IsoMath.WALL_UNITS
        assert(islandTop > 600f) { "island hugs the top: $islandTop" }
        assert(islandBottom <= 2400f - 320f) { "island spills into the bottom reserve: $islandBottom" }
        // slight sky bias: a bit more slack below the slab than above the field
        val above = islandTop - 300f
        val below = (2400f - 320f) - islandBottom
        assert(above < below) { "expected sky bias, above=$above below=$below" }
    }

    // ---- 1C.6 homestead: the house anchors the world ----

    private fun houseCenter(iso: IsoMath, side: Int): Pair<Float, Float> {
        // the point between the four house tiles: row = col = side/2 - .5 in tile units
        val r = side / 2f - .5f
        return (iso.originX + 0f) to (iso.originY + (r + r) * iso.tileH / 2f)
    }

    @Test fun `fitHome equals fit for small islands`() {
        val a = IsoMath.fit(6, 6, 1080f, 2400f, 300f, 320f)
        val b = IsoMath.fitHome(6, 1080f, 2400f, 300f, 320f)
        assertEquals(a.tileW, b.tileW, 1e-3f); assertEquals(a.originX, b.originX, 1e-3f); assertEquals(a.originY, b.originY, 1e-3f)
    }

    @Test fun `fitHome freezes tile size and pins the house once past the frame`() {
        val ten = IsoMath.fitHome(10, 1080f, 2400f, 300f, 320f)
        val thirty = IsoMath.fitHome(30, 1080f, 2400f, 300f, 320f)
        assertEquals(ten.tileW, thirty.tileW, 1e-3f)                 // history never shrinks the world
        val (hx10, hy10) = houseCenter(ten, 10)
        val (hx30, hy30) = houseCenter(thirty, 30)
        assertEquals(hx10, hx30, 1e-2f); assertEquals(hy10, hy30, 1e-2f)   // the anchor does not move
        assertEquals(540f, hx10, 1e-2f)
        assertEquals(300f + (2400f - 300f - 320f) * .45f, hy10, 1e-2f)
    }

    @Test fun `a planted tile keeps its world position when a ring is added`() {
        // plant 0 sits at (0,0) on side 10 and (1,1) on side 12 — same spot relative to the house
        val a = IsoMath.fitHome(10, 1080f, 2400f, 300f, 320f)
        val b = IsoMath.fitHome(12, 1080f, 2400f, 300f, 320f)
        assertEquals(a.tileCenterX(Tile(0, 0)), b.tileCenterX(Tile(1, 1)), 1e-2f)
        assertEquals(a.tileCenterY(Tile(0, 0)), b.tileCenterY(Tile(1, 1)), 1e-2f)
    }

    @Test fun `islandRect encloses every tile of the square field plus the slab wall`() {
        val fitted = IsoMath.fitHome(20, 1080f, 2400f, 300f, 320f)
        val r = fitted.islandRect(gridRows = 20, gridCols = 20)
        for (row in listOf(0, 10, 19)) for (col in listOf(0, 10, 19)) {
            val x = fitted.tileCenterX(Tile(row, col)); val y = fitted.tileCenterY(Tile(row, col))
            assert(x in r.left..r.right && y in r.top..r.bottom) { "tile ($row,$col) at ($x,$y) outside $r" }
        }
        assert(r.bottom >= fitted.tileCenterY(Tile(19, 19)) + fitted.tileH * IsoMath.WALL_UNITS)
    }

    @Test fun `house centroid screen position is independent of footprint`() {
        // 1C.7 §1: the block is centered, so its centroid index is (side−1)/2 for every
        // footprint — the f cancels. This is exactly why fitHome takes no footprint argument.
        // side ≡ f (mod 2) always, so an even side is only ever paired with an even f.
        val side = 12
        val iso = IsoMath.fitHome(side, 1080f, 2400f, 300f, 320f)
        fun centroid(f: Int): Pair<Float, Float> {
            val lo = (side - f) / 2
            val tiles = buildList {
                for (r in lo until lo + f) for (c in lo until lo + f) add(Tile(r, c))
            }
            return tiles.map { iso.tileCenterX(it) }.average().toFloat() to
                tiles.map { iso.tileCenterY(it) }.average().toFloat()
        }
        assertEquals(centroid(2).first, centroid(4).first, 0.01f)
        assertEquals(centroid(2).second, centroid(4).second, 0.01f)
    }
}
