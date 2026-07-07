package com.expensegarden.app.render

import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.Plant
import com.expensegarden.app.game.SerpentineTiler
import com.expensegarden.app.game.SizeTier
import com.expensegarden.app.game.Weather

/** A staged full month for eyeballing art + motion without touching real data. */
object SyntheticGarden {
    fun demo(weather: Weather = Weather.SUNNY): GardenState {
        val specs = listOf(
            Archetype.PETAL_FLOWER to SizeTier.M, Archetype.HEDGE to SizeTier.L,
            Archetype.TULIP to SizeTier.S, Archetype.PERENNIAL_SHRUB to SizeTier.M,
            Archetype.THISTLE_WEED to SizeTier.M, Archetype.BELL_FLOWER to SizeTier.S,
            Archetype.BUSH to SizeTier.L, Archetype.HERB_TUFT to SizeTier.S,
            Archetype.ODD_MUSHROOM to SizeTier.S, Archetype.PETAL_FLOWER to SizeTier.L,
            Archetype.HEDGE to SizeTier.M, Archetype.TULIP to SizeTier.M,
        )
        val tiles = SerpentineTiler.tiles(specs.size)
        val plants = specs.mapIndexed { i, (arch, tier) ->
            Plant("demo-$i", arch, tier, arch == Archetype.THISTLE_WEED || arch == Archetype.ODD_MUSHROOM, tiles[i], i * 977)
        }
        return GardenState(
            monthKey = "2026-07", weather = weather, plants = plants, spentPaise = 123_456L,
            backRowTreeCount = 2, trunkTier = 8, butterflies = 2, streakDays = 4, noSpendDays = 3,
            archived = false, gridRows = SerpentineTiler.gridRows(specs.size), gridCols = SerpentineTiler.COLS,
        )
    }
}
