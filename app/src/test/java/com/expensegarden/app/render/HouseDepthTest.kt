package com.expensegarden.app.render

import com.expensegarden.app.game.Tile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseDepthTest {
    // A 7×7 island with a 3×3 house in the middle: model rows/cols 2..4. In screen ("vis")
    // coordinates depth runs with row + col (IsoMath.tileCenterY), so the house's front edges are
    // its largest vis row and vis col.
    private val side = 7
    private val foot = 3

    private fun front(row: Int, col: Int) = HouseDepth.inFront(Tile(row, col), side, foot)

    @Test fun `tiles past the front row or front column are in front`() {
        assertTrue(front(1, 3))    // model row 1 → vis row 5, past the house's vis rows 2..4
        assertTrue(front(3, 5))    // col 5, past the house's cols 2..4
        assertTrue(front(0, 6))    // the near corner
    }

    @Test fun `tiles beside or behind the house are not`() {
        assertFalse(front(3, 1))   // left of the house, same depth band: drawn before it (no banding)
        assertFalse(front(5, 3))   // model row 5 → vis row 1, behind
        assertFalse(front(6, 0))   // the far corner
        assertFalse(front(2, 4))   // on the house's own front tile
    }
}
