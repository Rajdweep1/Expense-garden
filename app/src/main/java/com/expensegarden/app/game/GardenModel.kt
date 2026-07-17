package com.expensegarden.app.game

enum class Weather { SUNNY, OVERCAST, DROUGHT }

enum class Archetype {
    PETAL_FLOWER, TULIP, BELL_FLOWER, HERB_TUFT, BUSH,      // discretionary families
    HEDGE, PERENNIAL_SHRUB,                                  // necessities — dignified
    TREE,                                                    // investments, back row
    THISTLE_WEED, ODD_MUSHROOM,                              // weeds — grew during a breach
    ZOMBIE,                                                  // a regretted purchase, risen; revives when marked worth-it
}

enum class SizeTier { S, M, L }

data class Tile(val row: Int, val col: Int)                  // row 0 = front (nearest viewer)

data class Plant(
    val txnUuid: String,
    val archetype: Archetype,
    val sizeTier: SizeTier,
    val isWeed: Boolean,
    val tile: Tile,
    val seed: Int,                                           // uuid hash — all jitter derives from this
    val variant: Int = 0,                                    // which look within the archetype's sprite set
)

/** Where a month's first plant stands — the all-time island renders a signpost here. */
data class MonthMarker(val monthKey: String, val tile: Tile)

data class GardenState(
    val monthKey: String,
    val weather: Weather,
    val plants: List<Plant>,
    val spentPaise: Long,                                    // month total — greenhouse cards + strip reuse it
    val backRowTreeCount: Int,                               // cumulative investments, never reset (spec §9.3)
    val trunkTier: Int,                                      // thickens with every SIP
    val butterflies: Int,                                    // gate dodges this month, capped 5
    val streakDays: Int,
    val noSpendDays: Int,
    val archived: Boolean,
    val gridRows: Int,
    val gridCols: Int,
    val monthMarkers: List<MonthMarker> = emptyList(),       // all-time fold only; monthly folds leave it empty
)
