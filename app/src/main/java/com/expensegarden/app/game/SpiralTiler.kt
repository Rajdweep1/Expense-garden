package com.expensegarden.app.game

/** 1C.6 homestead tiling: a square island that grows in chronological rings around a centered
 *  house block. Ring 1's back edge is the backyard (grove); both are reserved.
 *
 *  1C.7: the house block is f×f and f grows with houseLevel. Every function defaults to f = 2,
 *  so all 1C.6 behaviour is exactly the f = 2 case — SpiralTilerTest passes unchanged.
 *
 *  Note side = f + 2k, so side and f ALWAYS share parity and (side − f) / 2 is exact. */
object SpiralTiler {
    /** House side in tiles per house level (spec §1). Hut and cottage share the 2×2 plot, so
     *  levelling 1→2 is a rebuild, not a land grab, and triggers no re-layout. */
    fun footprint(houseLevel: Int): Int = when (houseLevel.coerceIn(1, 4)) {
        1, 2 -> 2
        3 -> 3
        else -> 4
    }

    /** How many landmark plots exist at a given footprint (4B spec §1).
     *
     *  Landmarks are earned at house levels 3 and 4 — which are exactly the levels where
     *  [footprint] grows. The count therefore falls out of the f the tiler is already being
     *  handed, so nothing new has to be threaded through rings/gridSide/capacity, and no
     *  landmark state is stored anywhere. */
    fun landmarkCount(f: Int): Int = maxOf(0, f - 2)

    /** The reserved landmark plots, in earn order: index i is the plot for landmark ordinal i.
     *
     *  A List, not a Set like [houseTiles] and [backyardTiles]. Those two are pure membership
     *  tests — is this tile plantable — and a set says that precisely. These need membership
     *  AND order, and a set would drop exactly the information placement depends on.
     *
     *  The specific tiles were chosen by rendering, not by reasoning. Screen-x tracks
     *  (row + col) and screen-y tracks (col − row), so a tile flanks the house at its own
     *  height only when col − row matches the house centre's. The obvious-looking pair
     *  (lo, lo−1) / (lo, lo+f) fails that and puts one landmark visually behind the other.
     *  Both of these sit on the house's own diagonal, two x-units outside its screen corners.
     *
     *  Only the first plot is reserved until the second landmark is earned, so there is never
     *  an empty plot advertising a reward that has not arrived. */
    fun landmarkTiles(side: Int, f: Int = 2): List<Tile> {
        val lo = (side - f) / 2
        return listOf(Tile(lo - 1, lo - 1), Tile(lo + f, lo + f)).take(landmarkCount(f))
    }

    fun rings(plantCount: Int, f: Int = 2): Int {
        var k = 1
        while (capacity(k, f) < plantCount) k++
        return k
    }

    fun gridSide(plantCount: Int, f: Int = 2): Int = f + 2 * rings(plantCount, f)

    /** Plantable tiles through ring k around an f×f core. Ring i holds (f+2i)² − (f+2i−2)²
     *  = 4f + 8i − 4 tiles; summing i=1..k gives 4k² + 4fk. Minus the 4 reserved grove tiles.
     *  f = 2 reduces to the 1C.6 formula 4k² + 8k − 4.
     *
     *  4B: also minus the reserved landmark plots, which is zero at f = 2 — so every 1C.6
     *  expectation in SpiralTilerTest holds unchanged. */
    fun capacity(k: Int, f: Int = 2): Int = 4 * k * k + 4 * f * k - 4 - landmarkCount(f)

    fun houseTiles(side: Int, f: Int = 2): Set<Tile> {
        val lo = (side - f) / 2
        return buildSet { for (r in lo until lo + f) for (c in lo until lo + f) add(Tile(r, c)) }
    }

    /** 4 tiles on the row directly behind the house. The left edge lo + f/2 − 2 (INTEGER
     *  division) reproduces the 1C.6 placement exactly at f = 2 and lands flush on the house
     *  at f = 4; at f = 3 it is one column left-biased, which is invisible in isometric. */
    fun backyardTiles(side: Int, f: Int = 2): Set<Tile> {
        val lo = (side - f) / 2
        val c0 = lo + f / 2 - 2
        return (c0 until c0 + 4).map { Tile(lo + f, it) }.toSet()
    }

    fun tiles(plantCount: Int, f: Int = 2): List<Tile> {
        val side = gridSide(plantCount, f)
        val origin = (side - f) / 2                               // house block spans origin..origin+f-1
        val out = ArrayList<Tile>(plantCount)
        // Both reserved plots, in house-relative coords to match the ring walk below.
        // capacity() only makes the island BIG enough to hold the reservation; refusing to
        // plant on it is this set's job. Updating one without the other grows the island and
        // then plants on the plot anyway — which is exactly what happened first time.
        val skip = (backyardTiles(side, f) + landmarkTiles(side, f))
            .map { Tile(it.row - origin, it.col - origin) }.toSet()
        var k = 1
        while (out.size < plantCount) {
            // ring k around the house block, in house-relative coords (house cells are 0..f-1)
            val lo = -k
            val hi = f - 1 + k
            val walk = buildList {
                for (c in lo..hi) add(Tile(lo, c))                // front edge, left → right
                for (r in lo + 1..hi) add(Tile(r, hi))            // right edge, front → back
                for (c in hi - 1 downTo lo) add(Tile(hi, c))      // back edge, right → left
                for (r in hi - 1 downTo lo + 1) add(Tile(r, lo))  // left edge, back → front
            }
            for (t in walk) {
                if (t in skip) continue
                if (out.size == plantCount) break
                out += Tile(t.row + origin, t.col + origin)
            }
            k++
        }
        return out
    }
}
