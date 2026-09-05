package com.expensegarden.app.game

import kotlin.math.absoluteValue

/** The reward ladder (spec §2).
 *
 *  The three forms a rare can take ARE the tiers, rather than three parallel systems. That is
 *  what lets the island read as a record of your best behaviour — the same job the house
 *  ladder already does for months tracked — instead of a flat pile of trophies where a
 *  three-day streak and a year of tracking both just produce "a rare". */
enum class RareTier { UNCOMMON, RARE, LANDMARK }

/**
 * One collectable.
 *
 * @param baseArchetype non-null for UNCOMMON — the species this is a special variant OF. That
 *   is why uncommons need no renderer change at all: `SpritePainter` already loads
 *   `<archetype>_<variant>.png`, so a golden tulip is just a higher variant index on TULIP.
 *   Null for RARE and LANDMARK, which are archetypes in their own right.
 * @param spriteName the asset base name under `assets/garden/`, without the `.png`. Must match
 *   the generated sprite exactly — `SpritePainter` looks up by name and a typo renders nothing
 *   with no error anywhere.
 */
data class RareSpecies(
    val id: String,
    val displayName: String,
    val tier: RareTier,
    val spriteName: String,
    val baseArchetype: Archetype? = null,
)

/**
 * A typed projection of the one game_event kind the engine cares about.
 *
 * The engine takes these rather than raw `GameEventEntity` for a hard reason, not a stylistic
 * one: reading a payload means `org.json`, which is an Android stub that throws "not mocked"
 * in JVM unit tests — `testOptions.unitTests.isReturnDefaultValues` is deliberately absent from
 * this project. Keeping the engine JSON-free by construction is what makes the anti-farming
 * rules in spec §3.3 unit-testable at all. Projection happens in the data layer, which is
 * instrumented-tested. This is the same split `DigestEvent` / `DigestRepository.project` uses.
 */
sealed interface RareSignal {
    val eventId: Long
    val atMillis: Long

    data class StreakHit(
        override val eventId: Long,
        override val atMillis: Long,
        val days: Int,
        val month: String,
    ) : RareSignal

    data class MonthClosed(
        override val eventId: Long,
        override val atMillis: Long,
        val month: String,
        val spentPaise: Long,
        /** Null when no overall budget was set that month — NOT zero. Under-spending a budget
         *  that does not exist is not an achievement. */
        val budgetPaise: Long?,
    ) : RareSignal

    data class GateDodged(
        override val eventId: Long,
        override val atMillis: Long,
    ) : RareSignal

    data class RegretCleared(
        override val eventId: Long,
        override val atMillis: Long,
        val txnUuid: String,
    ) : RareSignal
}

/** What earned a rare — the album's "how you got it" line (spec §3). */
enum class RareTrigger {
    STREAK_7, STREAK_30, GATE_DODGES, NO_SPEND_DAYS, MONTH_UNDER_BUDGET, CATEGORY_BREADTH,
    REDEEMED, HOUSE_LEVEL,
}

/**
 * A detected award. Derived by [RareEngine] on every fold — never stored, never emitted
 * (spec §4.2).
 *
 * @param scopeKey the once-ever key from spec §3.3. Two earns sharing a scopeKey are the same
 *   earn. This is the single thing standing between the design and a farming exploit: without
 *   it, re-tagging one regret repeatedly would mint Uncommons forever.
 * @param sourceEventId the event that triggered it, which doubles as the seed for the species
 *   roll. Deriving the species from an id rather than rolling at runtime is what keeps the
 *   fold pure — see [RareCatalog.pick].
 */
data class Earn(
    val trigger: RareTrigger,
    val scopeKey: String,
    val tier: RareTier,
    val sourceEventId: Long,
    val atMillis: Long,
) {
    val species: RareSpecies get() = RareCatalog.pick(tier, sourceEventId)
}

object RareCatalog {

    private val UNCOMMONS = listOf(
        RareSpecies("golden_tulip", "Golden Tulip", RareTier.UNCOMMON, "tulip_3", Archetype.TULIP),
        RareSpecies("moonlit_bell", "Moonlit Bell", RareTier.UNCOMMON, "bell_flower_2", Archetype.BELL_FLOWER),
        RareSpecies("flowering_hedge", "Flowering Hedge", RareTier.UNCOMMON, "hedge_3", Archetype.HEDGE),
        RareSpecies("heavy_berry", "Heavy Berry Bush", RareTier.UNCOMMON, "berry_bush_2", Archetype.BERRY_BUSH),
        RareSpecies("silver_succulent", "Silver Succulent", RareTier.UNCOMMON, "succulent_2", Archetype.SUCCULENT),
        RareSpecies("sunlit_petal", "Sunlit Bloom", RareTier.UNCOMMON, "petal_flower_3", Archetype.PETAL_FLOWER),
        RareSpecies("spiced_chai", "Spiced Chai Cluster", RareTier.UNCOMMON, "chai_cluster_2", Archetype.CHAI_CLUSTER),
        RareSpecies("ripe_row", "Ripe Vegetable Row", RareTier.UNCOMMON, "vegetable_row_2", Archetype.VEGETABLE_ROW),
    )

    private val RARES = listOf(
        RareSpecies("bonsai", "Bonsai", RareTier.RARE, "bonsai_0"),
        RareSpecies("lotus", "Lotus", RareTier.RARE, "lotus_0"),
        RareSpecies("night_orchid", "Night Orchid", RareTier.RARE, "night_orchid_0"),
        RareSpecies("firefly_fern", "Firefly Fern", RareTier.RARE, "firefly_fern_0"),
    )

    /** Specified now so the earning engine is built once, but not rendered until 4B: a pond is
     *  not a grid cell and `SpiralTiler` cannot place one (spec §8). Earned landmarks record
     *  into the album and wait — [RarePairing] never assigns them to a transaction. */
    private val LANDMARKS = listOf(
        RareSpecies("koi_pond", "Koi Pond", RareTier.LANDMARK, "koi_pond_0"),
        RareSpecies("stone_lantern", "Stone Lantern", RareTier.LANDMARK, "stone_lantern_0"),
    )

    fun pool(tier: RareTier): List<RareSpecies> = when (tier) {
        RareTier.UNCOMMON -> UNCOMMONS
        RareTier.RARE -> RARES
        RareTier.LANDMARK -> LANDMARKS
    }

    fun all(): List<RareSpecies> = UNCOMMONS + RARES + LANDMARKS

    fun byId(id: String): RareSpecies? = all().firstOrNull { it.id == id }

    /** Which rare you get — derived, never rolled (spec §4.1).
     *
     *  `Math.random()` here would be a real bug, not a style choice: the garden is a pure fold,
     *  so a runtime roll makes every replay of the log produce a different island and the
     *  greenhouse's archived months drift. Same defect class as a wall-clock watermark, which
     *  this project has now been bitten by twice.
     *
     *  The `.toLong()` before `.absoluteValue` is load-bearing. `Int.MIN_VALUE.absoluteValue`
     *  is still `Int.MIN_VALUE` in two's complement, so taking the absolute value while still
     *  an Int would yield a negative index for one unlucky seed. */
    fun pick(tier: RareTier, sourceEventId: Long): RareSpecies {
        val p = pool(tier)
        val index = (sourceEventId.hashCode().toLong().absoluteValue % p.size).toInt()
        return p[index]
    }
}
