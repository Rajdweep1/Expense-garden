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

    companion object {
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
            val originY = topReserve + tileH
            return IsoMath(tileW, tileH, originX, originY)
        }
    }
}
