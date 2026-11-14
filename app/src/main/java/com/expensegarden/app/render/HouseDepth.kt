package com.expensegarden.app.render

import com.expensegarden.app.game.Tile

/** Which plants stand in front of the homestead block. Pure, so the draw-order rule is JVM-tested.
 *
 *  The house is a single tall billboard drawn whole (GardenCanvas): plants beside or behind it are
 *  painted first, so none can band across its walls. But a plant past the block's front edges is
 *  nearer the viewer than any part of the house; painted before it, the house's dirt plinth buried
 *  it and the house seemed to float above the garden (phone, 2026-11-14). Those are painted after. */
object HouseDepth {
    /** [tile] in model coordinates on a [side]×[side] island with a centred [foot]×[foot] house.
     *  Screen depth grows with vis row + col (IsoMath.tileCenterY), vis row = side − 1 − row. */
    fun inFront(tile: Tile, side: Int, foot: Int): Boolean {
        val lo = (side - foot) / 2
        val visRow = side - 1 - tile.row
        return visRow > side - 1 - lo || tile.col > lo + foot - 1
    }
}
