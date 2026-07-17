package com.expensegarden.app.render

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraMathTest {

    @Test
    fun `zoom clamps to the allowed range`() {
        assertEquals(CameraMath.MIN_ZOOM, CameraMath.clampZoom(0.2f), 1e-4f)
        assertEquals(CameraMath.MAX_ZOOM, CameraMath.clampZoom(9f), 1e-4f)
        assertEquals(1.3f, CameraMath.clampZoom(1.3f), 1e-4f)
    }

    @Test
    fun `pan bound grows with zoom so far corners stay reachable`() {
        val atRest = CameraMath.panBoundX(1f, 1080f)
        val zoomed = CameraMath.panBoundX(2f, 1080f)
        assert(zoomed > atRest)
        assert(CameraMath.panBoundY(1f, 2400f) > 0f)
    }

    @Test
    fun `pan clamps hard at the bound`() {
        assertEquals(400f, CameraMath.clampPan(650f, 400f), 1e-4f)
        assertEquals(-400f, CameraMath.clampPan(-99999f, 400f), 1e-4f)
        assertEquals(123f, CameraMath.clampPan(123f, 400f), 1e-4f)
    }

    @Test
    fun `rubber band resists overshoot at 40 percent beyond the bound`() {
        assertEquals(400f + 40f, CameraMath.rubberBand(500f, 400f), 1e-3f)
        assertEquals(-400f - 40f, CameraMath.rubberBand(-500f, 400f), 1e-3f)
        assertEquals(250f, CameraMath.rubberBand(250f, 400f), 1e-4f)
    }

    @Test
    fun `screenToWorld inverts the island draw transform`() {
        // Island layer draws as: screen = (world - center) * zoom + center + pan
        val worldX = 613f; val worldY = 425f
        val zoom = 1.6f; val panX = 90f; val panY = -40f
        val cX = 540f; val cY = 1200f
        val screenX = (worldX - cX) * zoom + cX + panX
        val screenY = (worldY - cY) * zoom + cY + panY
        assertEquals(worldX, CameraMath.screenToWorldX(screenX, panX, zoom, cX), 1e-3f)
        assertEquals(worldY, CameraMath.screenToWorldY(screenY, panY, zoom, cY), 1e-3f)
    }

    // ---- 1C.5 world mode: range-based pan bounds over an island bigger than the screen ----

    @Test
    fun `panRange can center both island extremes and scales with zoom`() {
        // Island spans world y 400..4800, viewport center 1200: centering the bottom needs
        // pan = -(4800-1200)*zoom, centering the top needs pan = -(400-1200)*zoom.
        val r1 = CameraMath.panRange(extentMin = 400f, extentMax = 4800f, center = 1200f, zoom = 1f)
        assertEquals(-3600f, r1.start, 1e-3f)
        assertEquals(800f, r1.endInclusive, 1e-3f)
        val r2 = CameraMath.panRange(400f, 4800f, 1200f, zoom = 2f)
        assertEquals(-7200f, r2.start, 1e-3f)
    }

    @Test
    fun `panRange always contains zero so the default framing is legal`() {
        // A short island sitting above center would otherwise demand a positive-only range.
        val r = CameraMath.panRange(extentMin = 300f, extentMax = 900f, center = 1200f, zoom = 1f)
        assert(r.start <= 0f && r.endInclusive >= 0f)
    }

    @Test
    fun `range clamp and rubber band mirror the symmetric versions`() {
        val r = CameraMath.panRange(400f, 4800f, 1200f, 1f)     // -3600..800
        assertEquals(800f, CameraMath.clampPan(2000f, r), 1e-3f)
        assertEquals(-3600f, CameraMath.clampPan(-9999f, r), 1e-3f)
        assertEquals(-100f, CameraMath.clampPan(-100f, r), 1e-3f)
        assertEquals(800f + 40f, CameraMath.rubberBand(900f, r), 1e-3f)
        assertEquals(-3600f - 40f, CameraMath.rubberBand(-3700f, r), 1e-3f)
        assertEquals(-3599f, CameraMath.rubberBand(-3599f, r), 1e-3f)
    }
}
