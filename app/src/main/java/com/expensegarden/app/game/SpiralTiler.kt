package com.expensegarden.app.game

/** 1C.6 homestead tiling: a square island that grows in chronological rings around a
 *  fixed 2×2 house block. Ring 1's back edge is the backyard (grove); both are reserved.
 *  Coordinates are absolute per side, but positions are invariant RELATIVE to the house —
 *  the canvas anchors the house on screen, so growth never moves a planted tile visually. */
object SpiralTiler {
    fun rings(plantCount: Int): Int {
        var k = 1
        while (capacity(k) < plantCount) k++
        return k
    }

    fun gridSide(plantCount: Int): Int = 2 + 2 * rings(plantCount)

    /** Plantable tiles through ring k: ring i holds 4+8i, minus ring 1's 4 backyard tiles. */
    fun capacity(k: Int): Int = 4 * k * k + 8 * k - 4

    fun houseTiles(side: Int): Set<Tile> {
        val lo = side / 2 - 1
        return setOf(Tile(lo, lo), Tile(lo, lo + 1), Tile(lo + 1, lo), Tile(lo + 1, lo + 1))
    }

    fun backyardTiles(side: Int): Set<Tile> {
        val r = side / 2 + 1
        return (side / 2 - 2..side / 2 + 1).map { Tile(r, it) }.toSet()
    }

    fun tiles(plantCount: Int): List<Tile> {
        val side = gridSide(plantCount)
        val center = side / 2                                     // house block spans center-1..center
        val out = ArrayList<Tile>(plantCount)
        val skip = backyardTiles(side).map { Tile(it.row - center, it.col - center) }.toSet()
        var k = 1
        while (out.size < plantCount) {
            // ring k around the house block, in house-relative coords (house cells are rows/cols -1 and 0)
            val lo = -1 - k
            val hi = k
            val walk = buildList {
                for (c in lo..hi) add(Tile(lo, c))                // front edge, left → right
                for (r in lo + 1..hi) add(Tile(r, hi))            // right edge, front → back
                for (c in hi - 1 downTo lo) add(Tile(hi, c))      // back edge, right → left
                for (r in hi - 1 downTo lo + 1) add(Tile(r, lo))  // left edge, back → front
            }
            for (t in walk) {
                if (t in skip) continue
                if (out.size == plantCount) break
                out += Tile(t.row + center, t.col + center)
            }
            k++
        }
        return out
    }
}
