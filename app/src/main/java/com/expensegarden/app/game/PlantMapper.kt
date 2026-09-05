package com.expensegarden.app.game

import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.stats.CategoryTree
import kotlin.math.abs

/** Everything before tiling: txn → what grows. Returns null for investments (they go to the back row). */
data class MappedPlant(
    val txnUuid: String,
    val archetype: Archetype,
    val sizeTier: SizeTier,
    val isWeed: Boolean,
    val seed: Int,
    val variant: Int = 0,
    /** 4A: the earned rare this purchase grew as, if any. */
    val rare: RareSpecies? = null,
)

object PlantMapper {
    const val INVESTMENTS_ROOT = 10L

    private val discretionaryByRoot = mapOf(
        1L to Archetype.PETAL_FLOWER,   // Food & Drinks
        6L to Archetype.BELL_FLOWER,    // Entertainment
        7L to Archetype.TULIP,          // Shopping
        8L to Archetype.HERB_TUFT,      // Personal
        11L to Archetype.BUSH,          // Misc
    )
    /** 1C.7: a subcategory may override its root's family — consulted BEFORE the root maps.
     *  Food & Drinks is the highest-volume root, so its subcats earn distinct looks. */
    private val archetypeBySubcat = mapOf(
        102L to Archetype.CURL_VINE,      // Delivery — something that arrived
        103L to Archetype.CHAI_CLUSTER,   // Chai & Snacks — small and frequent
    )

    /** Sprite variants per archetype (matching the asset pack); everything else has one look. */
    private val variantCounts = mapOf(
        Archetype.PETAL_FLOWER to 3, Archetype.TULIP to 3, Archetype.BELL_FLOWER to 2,
        Archetype.HERB_TUFT to 2, Archetype.BUSH to 2, Archetype.HEDGE to 3,
        Archetype.PERENNIAL_SHRUB to 2, Archetype.TREE to 2, Archetype.ZOMBIE to 3,
        Archetype.VEGETABLE_ROW to 2, Archetype.SUCCULENT to 2, Archetype.BERRY_BUSH to 2,
        Archetype.CURL_VINE to 2, Archetype.CHAI_CLUSTER to 2,
    )

    fun variantCount(archetype: Archetype): Int = variantCounts[archetype] ?: 1

    /** Landmark bills read at a glance: Rent is always the grand topiary, Utilities the
     *  square trim, Fuel the sturdy shrub. High-frequency spends stay seed-varied. */
    private val variantBySubcat = mapOf(401L to 1, 402L to 2, 301L to 1)

    private val necessityByRoot = mapOf(
        2L to Archetype.VEGETABLE_ROW,  // Groceries   (1C.7: was HEDGE)
        3L to Archetype.PERENNIAL_SHRUB,// Transport
        4L to Archetype.HEDGE,          // Housing — the topiary IS the rent landmark
        5L to Archetype.SUCCULENT,      // Health      (1C.7: was PERENNIAL_SHRUB)
        9L to Archetype.BERRY_BUSH,     // Family      (1C.7: was HEDGE)
    )

    fun map(txn: TransactionEntity, tree: CategoryTree, rare: RareSpecies? = null): MappedPlant? {
        val chain = tree.ancestorChain(txn.categoryId)
        val root = chain.lastOrNull() ?: txn.categoryId
        if (root == INVESTMENTS_ROOT) return null

        val seed = txn.uuid.hashCode()
        val ownNecessity = tree.byId(txn.categoryId)?.isNecessity ?: false
        // 1C.6: the old weed rule split in two — a regret VERDICT rises as a zombie on its
        // tile; a breach at logging (circumstance) still grows a weed. Necessities do neither.
        val isZombie = !ownNecessity && txn.regret == Regret.REGRET
        val isWeed = !ownNecessity && !isZombie && txn.breachedAtLogging

        val archetype = when {
            isZombie -> Archetype.ZOMBIE
            isWeed -> if (abs(seed) % 2 == 0) Archetype.THISTLE_WEED else Archetype.ODD_MUSHROOM
            archetypeBySubcat.containsKey(txn.categoryId) -> archetypeBySubcat.getValue(txn.categoryId)
            ownNecessity -> necessityByRoot[root] ?: Archetype.HEDGE
            else -> discretionaryByRoot[root] ?: Archetype.BUSH
        }
        val tier = when {
            txn.amountPaise < 10_000L -> SizeTier.S      // < ₹100
            txn.amountPaise < 100_000L -> SizeTier.M     // < ₹1000
            else -> SizeTier.L
        }
        val variant = when {
            isZombie -> tier.ordinal                     // zombie size = the tier of what died
            isWeed -> 0
            else -> variantBySubcat[txn.categoryId] ?: (abs(seed / 31) % variantCount(archetype))
        }
        // A rare decorates; it never re-labels. The archetype is left exactly as the category
        // decided, so the garden cannot misreport what was bought — a rare is the plant you
        // would have grown anyway, grown better. And a rare later tagged as a regret still
        // becomes a zombie (spec §6): the honesty of the garden outranks the prettiness of the
        // collection, so the zombie and weed branches above win outright.
        val effective = if (isZombie || isWeed) null else rare
        // A rare simply occupies a variant index above the archetype's ordinary ones, which is
        // why the renderer needs no special case: SpritePainter already keys on
        // (archetype, variant).
        return MappedPlant(
            txn.uuid, archetype, tier, isWeed, seed,
            effective?.variant ?: variant,
            rare = effective,
        )
    }
}
