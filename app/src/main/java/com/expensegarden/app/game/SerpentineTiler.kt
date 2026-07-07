package com.expensegarden.app.game

/** Chronological serpentine tiling: plant i (by occurredAt, uuid) → tile. Row 0 = front; the bed grows toward the horizon. */
object SerpentineTiler {
    const val COLS = 5
    private const val MIN_ROWS = 4

    fun gridRows(plantCount: Int): Int = maxOf(MIN_ROWS, (plantCount + COLS - 1) / COLS)

    fun tiles(plantCount: Int): List<Tile> = (0 until plantCount).map { i ->
        val row = i / COLS
        val within = i % COLS
        val col = if (row % 2 == 0) within else COLS - 1 - within
        Tile(row, col)
    }
}
