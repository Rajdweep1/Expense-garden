package com.expensegarden.app.game

import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.stats.CategoryTree
import kotlin.math.abs

/** Everything before tiling: txn → what grows. Returns null for investments (they go to the back row). */
data class MappedPlant(val txnUuid: String, val archetype: Archetype, val sizeTier: SizeTier, val isWeed: Boolean, val seed: Int)

object PlantMapper {
    const val INVESTMENTS_ROOT = 10L

    private val discretionaryByRoot = mapOf(
        1L to Archetype.PETAL_FLOWER,   // Food & Drinks
        6L to Archetype.BELL_FLOWER,    // Entertainment
        7L to Archetype.TULIP,          // Shopping
        8L to Archetype.HERB_TUFT,      // Personal
        11L to Archetype.BUSH,          // Misc
    )
    private val necessityByRoot = mapOf(
        2L to Archetype.HEDGE,          // Groceries
        3L to Archetype.PERENNIAL_SHRUB,// Transport
        4L to Archetype.HEDGE,          // Housing
        5L to Archetype.PERENNIAL_SHRUB,// Health
        9L to Archetype.HEDGE,          // Family
    )

    fun map(txn: TransactionEntity, tree: CategoryTree): MappedPlant? {
        val chain = tree.ancestorChain(txn.categoryId)
        val root = chain.lastOrNull() ?: txn.categoryId
        if (root == INVESTMENTS_ROOT) return null

        val seed = txn.uuid.hashCode()
        val ownNecessity = tree.byId(txn.categoryId)?.isNecessity ?: false
        val isWeed = !ownNecessity && (txn.breachedAtLogging || txn.regret == Regret.REGRET)

        val archetype = when {
            isWeed -> if (abs(seed) % 2 == 0) Archetype.THISTLE_WEED else Archetype.ODD_MUSHROOM
            ownNecessity -> necessityByRoot[root] ?: Archetype.HEDGE
            else -> discretionaryByRoot[root] ?: Archetype.BUSH
        }
        val tier = when {
            txn.amountPaise < 10_000L -> SizeTier.S      // < ₹100
            txn.amountPaise < 100_000L -> SizeTier.M     // < ₹1000
            else -> SizeTier.L
        }
        return MappedPlant(txn.uuid, archetype, tier, isWeed, seed)
    }
}
