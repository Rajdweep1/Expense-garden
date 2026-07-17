package com.expensegarden.app.render

/** Pure camera math for the garden scene — floats only, JVM-testable (same convention as IsoMath).
 *  The island layer draws as translate(pan) then scale(zoom @ viewport center); these helpers keep
 *  the gesture state inside bounds and map touches back through that transform. */
object CameraMath {
    const val MIN_ZOOM = 0.85f
    const val WORLD_MIN_ZOOM = 0.7f      // the endless island earns a wider survey zoom
    const val MAX_ZOOM = 2.2f

    fun clampZoom(zoom: Float, min: Float = MIN_ZOOM): Float = zoom.coerceIn(min, MAX_ZOOM)

    /** How far the world may pan from center, per axis. Grows with zoom so a
     *  zoomed-in garden can still reach its far corners. */
    fun panBoundX(zoom: Float, viewportW: Float): Float = viewportW * .38f * zoom
    fun panBoundY(zoom: Float, viewportH: Float): Float = viewportH * .30f * zoom

    /** Hard clamp — the settle target once a gesture ends. */
    fun clampPan(v: Float, bound: Float): Float = v.coerceIn(-bound, bound)

    /** Live-gesture clamp: linear inside the bound, 40% resistance beyond it,
     *  so dragging past the edge stretches instead of walling. */
    fun rubberBand(v: Float, bound: Float): Float = when {
        v > bound -> bound + (v - bound) * .4f
        v < -bound -> -bound + (v + bound) * .4f
        else -> v
    }

    /** Inverse of the island draw transform: screen touch → world (pre-camera) space. */
    fun screenToWorldX(x: Float, panX: Float, zoom: Float, centerX: Float): Float =
        (x - panX - centerX) / zoom + centerX

    fun screenToWorldY(y: Float, panY: Float, zoom: Float, centerY: Float): Float =
        (y - panY - centerY) / zoom + centerY

    // ---- 1C.5 world mode: the island can outgrow the screen, so bounds come from its
    // ---- extent, not the viewport. pan = -(worldPoint - center) * zoom centers a point;
    // ---- the range lets either extreme reach center and ALWAYS contains 0 so the
    // ---- default frontier framing is a legal resting state.
    fun panRange(extentMin: Float, extentMax: Float, center: Float, zoom: Float): ClosedFloatingPointRange<Float> {
        val lo = minOf(-(extentMax - center) * zoom, 0f)
        val hi = maxOf(-(extentMin - center) * zoom, 0f)
        return lo..hi
    }

    fun clampPan(v: Float, range: ClosedFloatingPointRange<Float>): Float =
        v.coerceIn(range.start, range.endInclusive)

    fun rubberBand(v: Float, range: ClosedFloatingPointRange<Float>): Float = when {
        v > range.endInclusive -> range.endInclusive + (v - range.endInclusive) * .4f
        v < range.start -> range.start + (v - range.start) * .4f
        else -> v
    }
}
