package com.expensegarden.app.render

import com.expensegarden.app.game.Tile
import kotlin.math.floor

/** 2:1 isometric projection. Row 0/col 0 tile center sits at (originX, originY);
 *  rows recede up-left (toward the horizon), cols run down-right. Pure floats — JVM-testable. */
class IsoMath(val tileW: Float, val tileH: Float, val originX: Float, val originY: Float) {
    fun tileCenterX(tile: Tile): Float = originX + (tile.col - tile.row) * tileW / 2f
    fun tileCenterY(tile: Tile): Float = originY + (tile.col + tile.row) * tileH / 2f

    /** Higher = further back = drawn earlier. Row recedes, so depth is row-major then col. */
    fun depth(tile: Tile): Int = tile.row * 1000 + tile.col

    fun tileAt(x: Float, y: Float): Tile {
        val dx = (x - originX) / (tileW / 2f)
        val dy = (y - originY) / (tileH / 2f)
        val col = floor((dx + dy) / 2f + .5f).toInt()
        val row = floor((dy - dx) / 2f + .5f).toInt()
        return Tile(row, col)
    }

    /** Bounding box of the whole island block (field diamond + slab wall) in world space.
     *  Rows here are VISUAL rows — the same space tileCenterX/Y project. */
    fun islandRect(gridRows: Int, gridCols: Int): WorldRect = WorldRect(
        left = tileCenterX(Tile(gridRows - 1, 0)) - tileW / 2f,
        top = tileCenterY(Tile(0, 0)) - tileH / 2f,
        right = tileCenterX(Tile(0, gridCols - 1)) + tileW / 2f,
        bottom = tileCenterY(Tile(gridRows - 1, gridCols - 1)) + tileH / 2f + tileH * WALL_UNITS,
    )

    companion object {
        /** Slab wall height in tileH units — shared by fit() and the canvas so framing math
         *  and the drawn island agree on the island block's true height. */
        const val WALL_UNITS = 1.05f

        /** World-mode framing window: tile size is taken from an island this many rows
         *  deep, then FROZEN — more history extends the island, never shrinks the tiles. */
        const val FRAME_ROWS = 8

        /** Scale + center a rows×cols field into the viewport between the reserved bands. */
        fun fit(gridRows: Int, gridCols: Int, viewportW: Float, viewportH: Float, topReserve: Float, bottomReserve: Float): IsoMath {
            val unitsW = (gridCols + gridRows) / 2f          // field width in tileW units
            val unitsH = (gridCols + gridRows) / 2f          // field height in tileH units
            val availH = viewportH - topReserve - bottomReserve
            val tileW = minOf(viewportW * .92f / unitsW, availH * 2f * .92f / unitsH / 2f * 2f).coerceAtLeast(24f)
            val tileH = tileW / 2f
            // Field's horizontal extent: leftmost = origin - gridRows*tileW/2 + tileW/2 … center it.
            val fieldLeftUnits = (gridRows - 1) * .5f
            val fieldRightUnits = (gridCols - 1) * .5f
            val originX = viewportW / 2f + (fieldLeftUnits - fieldRightUnits) * tileW / 2f
            // Center the island block (field diamond + slab wall) inside the band with a
            // slight sky bias — FC frames its city mid-screen; water is a margin, not the
            // subject. Tight bands (greenhouse cards) get slack 0 = old top-aligned fit.
            val islandH = unitsH * tileH + tileH * WALL_UNITS
            val slack = (availH - islandH).coerceAtLeast(0f)
            val originY = topReserve + slack * .42f + tileH / 2f
            return IsoMath(tileW, tileH, originX, originY)
        }

        /** 1C.5 world mode: width-driven fit for the endless island. Small gardens frame
         *  exactly like fit(); past FRAME_ROWS the tile size freezes — while the island
         *  still fits the band vertically it stays centered (whole-field, both axes), and
         *  once it truly outgrows the screen the frontier (visual row 0 = newest) pins
         *  near the top band and the tail runs off-screen for the camera to explore. */
        fun fitWidth(gridRows: Int, gridCols: Int, viewportW: Float, viewportH: Float, topReserve: Float, bottomReserve: Float): IsoMath {
            if (gridRows <= FRAME_ROWS) return fit(gridRows, gridCols, viewportW, viewportH, topReserve, bottomReserve)
            val frame = fit(FRAME_ROWS, gridCols, viewportW, viewportH, topReserve, bottomReserve)
            val availH = viewportH - topReserve - bottomReserve
            val islandH = (gridCols + gridRows) / 2f * frame.tileH + frame.tileH * WALL_UNITS
            return if (islandH <= availH) {
                val originX = viewportW / 2f + ((gridRows - 1) * .5f - (gridCols - 1) * .5f) * frame.tileW / 2f
                val originY = topReserve + (availH - islandH) * .42f + frame.tileH / 2f
                IsoMath(frame.tileW, frame.tileH, originX, originY)
            } else {
                val originX = viewportW / 2f - (gridCols - 1) * frame.tileW / 4f
                val originY = topReserve + frame.tileH * 2.6f
                IsoMath(frame.tileW, frame.tileH, originX, originY)
            }
        }
    }
}

/** Axis-aligned world-space rect (island bounds feed camera ranges and culling). */
data class WorldRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
