package com.expensegarden.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastMathTest {

    @Test
    fun `black on white is the maximum ratio of 21`() {
        assertEquals(21.0, ContrastMath.ratio(0x000000, 0xFFFFFF), 0.001)
    }

    @Test
    fun `a colour against itself is 1 to 1`() {
        assertEquals(1.0, ContrastMath.ratio(0x3F7A33, 0x3F7A33), 0.0001)
    }

    @Test
    fun `ratio is symmetric`() {
        assertEquals(
            ContrastMath.ratio(0x1C1B17, 0xFAF8F2),
            ContrastMath.ratio(0xFAF8F2, 0x1C1B17),
            0.0001,
        )
    }

    @Test
    fun `luminance spans zero to one`() {
        assertEquals(0.0, ContrastMath.luminance(0x000000), 0.0001)
        assertEquals(1.0, ContrastMath.luminance(0xFFFFFF), 0.0001)
    }

    @Test
    fun `a neutral grey has no chroma`() {
        assertEquals(0.0, ContrastMath.chroma(0x808080), 0.01)
    }

    @Test
    fun `hue lands on the known Lab angles for the primaries`() {
        assertEquals(40.00, ContrastMath.hue(0xFF0000), 0.1)
        assertEquals(136.02, ContrastMath.hue(0x00FF00), 0.1)
        assertEquals(306.28, ContrastMath.hue(0x0000FF), 0.1)
    }

    @Test
    fun `hue separation takes the short way round the circle`() {
        // 10 degrees and 350 degrees are 20 apart, not 340.
        assertEquals(20.0, ContrastMath.hueSeparation(0xFF0000, 0xFF00E0), 60.0)
        assertTrue(ContrastMath.hueSeparation(0xFF0000, 0x00FF00) <= 180.0)
    }

    // --- the two defects this pass exists to fix, pinned as regressions ---

    @Test
    fun `the shipped status bar contrast is detected as a failure`() {
        // White glyphs on Material baseline surface FEF7FF, measured on device 2026-09-13.
        assertTrue(ContrastMath.ratio(0xFFFFFF, 0xFEF7FF) < 3.0)
    }

    @Test
    fun `the retired pace-warning colour is detected as near-grey`() {
        // tertiary 7D5260 — chroma 20.1, which is why it read as nothing.
        assertTrue(ContrastMath.chroma(0x7D5260) < 25.0)
    }
}
