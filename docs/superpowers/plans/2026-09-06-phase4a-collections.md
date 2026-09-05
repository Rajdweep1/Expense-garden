# Phase 4A — Collections & Rare Species Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A reward ladder you fill by spending less — earned by restraint and variety, revealed by chance, surfaced in the greenhouse.

**Architecture:** Everything is derived. `RareEngine` is a pure fold over the existing `game_event` log that detects earns and enforces once-per-scope; `RarePairing` assigns each earn to the next qualifying transaction. No new table, no new event type, no schema migration. Rendering reuses `SpritePainter`'s existing `<archetype>_<variant>.png` convention.

**Tech Stack:** Kotlin, Compose, existing Room schema (v4, unchanged), `tools/art/` FLUX pipeline for sprites.

**Spec:** `docs/superpowers/specs/2026-09-06-phase4a-collections-design.md`

---

## Guardrails (read before Task 1)

- **Do NOT add Android dependencies.** `gradle/libs.versions.toml` is deliberately pinned.

- **Every Gradle command needs JDK 17.** The default `java` here is 11; AGP 8.5.2 needs 17, and each shell starts fresh:

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```

- **Gradle task names:** `testDebugUnitTest --tests '<pattern>'` for JVM; `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>` for instrumented. Plain `test --tests` fails — it is a lifecycle task.

- **`connectedDebugAndroidTest` uninstalls both APKs when it finishes.** Install last, alone.

- **Instrumented test names are snake_case.** minSdk 26 → D8 rejects spaces in method names. Backticks-with-spaces are fine in `app/src/test`, fatal in `app/src/androidTest`.

- **NO Room migration in this phase.** If you find yourself editing `Entities.kt`, stop — the design is derived precisely so the schema does not change. Schema stays at v4.

- **`Math.random()` and any wall-clock read are forbidden inside the fold.** Spec §4.1: the garden is a pure fold, so a runtime roll would make replays diverge and the greenhouse's archived months drift. Every "random" choice derives from an event id.

- **If a step's output doesn't match its Expected line: STOP and report.** Do not improvise.

- Commits: plain messages, no `Co-Authored-By` / Claude / AI attribution. Never `git push` unless asked. Never commit files under `docs/`.

## File structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/.../game/RareModel.kt` | **create** | `RareTier`, `RareSpecies`, `Earn`, `RareCatalog` — pure types |
| `app/src/main/java/.../game/RareEngine.kt` | **create** | pure fold: events → earns, with once-per-scope enforcement |
| `app/src/main/java/.../game/RarePairing.kt` | **create** | pure: earns + ordered txns → uuid→species assignment |
| `app/src/main/java/.../game/PlantMapper.kt` | modify | accept an optional rare assignment; unchanged when absent |
| `app/src/main/java/.../game/GardenFolder.kt` | modify | run the engine, thread assignments into the mapper |
| `app/src/main/java/.../game/GardenModel.kt` | modify | `Plant` gains a nullable rare species; new archetypes |
| `app/src/main/java/.../render/ProceduralPainter.kt` | modify | exhaustive `when` gains the new archetypes |
| `app/src/main/java/.../data/GardenRepository.kt` | modify | expose earned collection for the album |
| `app/src/main/java/.../ui/GreenhouseScreen.kt` | modify | Collection section |
| `tools/art/briefs.py` | modify | briefs for ~10 variants + 4 species |
| `app/src/main/assets/garden/*.png` | **create** | the generated sprites |

---

## Task 1: The rare catalogue — pure types

Spec §2, §3. Nothing here touches Room, Compose, or the fold. Pure Kotlin so it is JVM-testable.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/game/RareModel.kt`
- Test: `app/src/test/java/com/expensegarden/app/game/RareCatalogTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/game/RareCatalogTest.kt`:

```kotlin
package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RareCatalogTest {

    @Test fun `every tier has a non-empty pool`() {
        // A tier with an empty pool would make the modulo in RareCatalog.pick divide by zero,
        // and the failure would surface as a crash inside the fold rather than here.
        for (tier in RareTier.values()) {
            assertTrue("$tier pool is empty", RareCatalog.pool(tier).isNotEmpty())
        }
    }

    @Test fun `species ids are unique across the whole catalogue`() {
        // Ids key the album's earned-set, so a collision would silently merge two species.
        val ids = RareTier.values().flatMap { RareCatalog.pool(it) }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `picking is deterministic for the same event id`() {
        val a = RareCatalog.pick(RareTier.UNCOMMON, sourceEventId = 4242L)
        val b = RareCatalog.pick(RareTier.UNCOMMON, sourceEventId = 4242L)
        assertEquals(a, b)
    }

    @Test fun `picking spreads across the pool rather than always returning one species`() {
        val pool = RareCatalog.pool(RareTier.UNCOMMON)
        val picked = (1L..200L).map { RareCatalog.pick(RareTier.UNCOMMON, it).id }.toSet()
        assertTrue("expected >1 distinct species over 200 ids, got ${picked.size}", picked.size > 1)
        assertTrue(picked.size <= pool.size)
    }

    @Test fun `a negative event id still picks a valid species`() {
        // hashCode-derived ids can be negative; a raw % would yield a negative index.
        val s = RareCatalog.pick(RareTier.UNCOMMON, sourceEventId = -99L)
        assertTrue(s in RareCatalog.pool(RareTier.UNCOMMON))
    }

    @Test fun `uncommon species name an existing archetype and rare species do not`() {
        // Uncommons are variants of something you already grow; rares are new archetypes.
        assertTrue(RareCatalog.pool(RareTier.UNCOMMON).all { it.baseArchetype != null })
        assertTrue(RareCatalog.pool(RareTier.RARE).all { it.baseArchetype == null })
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.RareCatalogTest'`

Expected: BUILD FAILED — `Unresolved reference 'RareTier'`.

- [ ] **Step 3: Write the model**

Create `app/src/main/java/com/expensegarden/app/game/RareModel.kt`:

```kotlin
package com.expensegarden.app.game

import kotlin.math.absoluteValue

/** The reward ladder (spec §2). The three forms a rare can take ARE the tiers — that is what
 *  lets the island read as a record of your best behaviour rather than a flat pile of trophies. */
enum class RareTier { UNCOMMON, RARE, LANDMARK }

/**
 * One collectable.
 *
 * @param baseArchetype non-null for UNCOMMON — the species it is a special variant OF, which
 *   is why uncommons need no renderer change: SpritePainter already loads
 *   "<archetype>_<variant>.png" and this just names a higher variant index. Null for RARE and
 *   LANDMARK, which are their own archetypes.
 * @param spriteName the asset base name in assets/garden/, without the .png.
 */
data class RareSpecies(
    val id: String,
    val displayName: String,
    val tier: RareTier,
    val spriteName: String,
    val baseArchetype: Archetype? = null,
)

/** What earned a rare, for the album's "how you got it" line. */
enum class RareTrigger {
    STREAK_7, STREAK_30, GATE_DODGES, NO_SPEND_DAYS, MONTH_UNDER_BUDGET, CATEGORY_BREADTH,
    REDEEMED, HOUSE_LEVEL,
}

/** A detected award. Derived by RareEngine — never stored, never emitted (spec §4.2).
 *
 *  @param scopeKey the once-ever key from spec §3.3. Two earns with the same scopeKey are the
 *    same earn; this is what stops a re-tagged regret from farming rewards.
 *  @param sourceEventId the game_event that triggered it — also the seed for the species roll,
 *    which is what keeps the choice deterministic across replays (spec §4.1).
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

    /** Specified now so the earning engine is built once, but not rendered until 4B — a pond
     *  is not a grid cell and SpiralTiler cannot place one (spec §8). Earned landmarks record
     *  into the album and wait. */
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

    /** Which rare you get, derived rather than rolled (spec §4.1).
     *
     *  `Math.random()` here would make every replay of the log produce a different island and
     *  the greenhouse's archived months drift — the same defect class as a wall-clock
     *  watermark, which this project has been bitten by twice. `absoluteValue` matters:
     *  event ids are Longs and a hashed negative would index out of bounds. */
    fun pick(tier: RareTier, sourceEventId: Long): RareSpecies {
        val p = pool(tier)
        return p[(sourceEventId.hashCode().toLong().absoluteValue % p.size).toInt()]
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.RareCatalogTest'`

Expected: BUILD SUCCESSFUL, 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/RareModel.kt app/src/test/
git commit -m "feat: rare species catalogue with a deterministic species roll"
```

---

## Task 2: RareEngine — the pure earning fold

Spec §3, §3.3. This is the heart of the phase and the place the anti-farming guarantee lives.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/game/RareEngine.kt`
- Test: `app/src/test/java/com/expensegarden/app/game/RareEngineTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/game/RareEngineTest.kt`:

```kotlin
package com.expensegarden.app.game

import com.expensegarden.app.data.GameEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RareEngineTest {
    private var nextId = 0L
    private fun ev(type: String, payload: String, at: Long = 1_000L) =
        GameEventEntity(id = ++nextId, type = type, payloadJson = payload, transactionUuid = null, createdAt = at)

    private fun evFor(type: String, uuid: String, at: Long = 1_000L) =
        GameEventEntity(id = ++nextId, type = type, payloadJson = """{"uuid":"$uuid"}""",
            transactionUuid = uuid, createdAt = at)

    private fun earns(events: List<GameEventEntity>, noSpendByMonth: Map<String, Int> = emptyMap(),
                      breadthByMonth: Map<String, Int> = emptyMap(), houseLevel: Int = 1) =
        RareEngine.earns(events, noSpendByMonth, breadthByMonth, houseLevel)

    // ---------- individual triggers ----------

    @Test fun `a seven day streak earns an uncommon`() {
        val e = earns(listOf(ev("streak.hit", """{"month":"2026-09","days":7}""")))
        assertEquals(1, e.size)
        assertEquals(RareTrigger.STREAK_7, e[0].trigger)
        assertEquals(RareTier.UNCOMMON, e[0].tier)
    }

    @Test fun `a thirty day streak earns a rare`() {
        val e = earns(listOf(ev("streak.hit", """{"month":"2026-09","days":30}""")))
        assertEquals(RareTier.RARE, e.single().tier)
    }

    @Test fun `streak thresholds that are not seven or thirty earn nothing`() {
        // Reconciler emits 3 and 14 too; those are progress, not rewards.
        val e = earns(listOf(
            ev("streak.hit", """{"month":"2026-09","days":3}"""),
            ev("streak.hit", """{"month":"2026-09","days":14}"""),
        ))
        assertTrue(e.isEmpty())
    }

    @Test fun `three gate dodges in a month earn one uncommon`() {
        val e = earns(List(3) { ev("gate.dodged", "{}") })
        assertEquals(1, e.size)
        assertEquals(RareTrigger.GATE_DODGES, e.single().trigger)
    }

    @Test fun `two gate dodges earn nothing`() {
        assertTrue(earns(List(2) { ev("gate.dodged", "{}") }).isEmpty())
    }

    @Test fun `a month closed under budget earns a rare`() {
        val e = earns(listOf(ev("month.closed",
            """{"month":"2026-08","spentPaise":5000,"overallBudgetPaise":10000}""")))
        assertEquals(RareTier.RARE, e.single().tier)
        assertEquals(RareTrigger.MONTH_UNDER_BUDGET, e.single().trigger)
    }

    @Test fun `a month closed over budget earns nothing`() {
        assertTrue(earns(listOf(ev("month.closed",
            """{"month":"2026-08","spentPaise":15000,"overallBudgetPaise":10000}"""))).isEmpty())
    }

    @Test fun `a month closed with no budget set earns nothing`() {
        // overallBudgetPaise is JSONObject.NULL when no budget existed. Under-spending an
        // absent budget is not an achievement, and treating null as 0 would make it one.
        assertTrue(earns(listOf(ev("month.closed",
            """{"month":"2026-08","spentPaise":15000,"overallBudgetPaise":null}"""))).isEmpty())
    }

    @Test fun `seven no spend days in a month earn an uncommon`() {
        val e = earns(emptyList(), noSpendByMonth = mapOf("2026-09" to 7))
        assertEquals(RareTrigger.NO_SPEND_DAYS, e.single().trigger)
    }

    @Test fun `six no spend days earn nothing`() {
        assertTrue(earns(emptyList(), noSpendByMonth = mapOf("2026-09" to 6)).isEmpty())
    }

    @Test fun `eight root categories in a month earn a rare`() {
        val e = earns(emptyList(), breadthByMonth = mapOf("2026-09" to 8))
        assertEquals(RareTier.RARE, e.single().tier)
        assertEquals(RareTrigger.CATEGORY_BREADTH, e.single().trigger)
    }

    @Test fun `house level three and four each earn a landmark`() {
        val e = earns(emptyList(), houseLevel = 4)
        assertEquals(2, e.size)
        assertTrue(e.all { it.tier == RareTier.LANDMARK })
        assertEquals(setOf("house:3", "house:4"), e.map { it.scopeKey }.toSet())
    }

    @Test fun `house level two earns no landmark`() {
        assertTrue(earns(emptyList(), houseLevel = 2).isEmpty())
    }

    // ---------- the excluded trigger (spec 3.2) ----------

    @Test fun `a month with no regrets tagged earns nothing for that fact alone`() {
        // Regression for spec 3.2: rewarding zero-regrets would reward NOT tagging, which
        // corrupts the ledger. Only redemption is rewarded, never abstention.
        val e = earns(listOf(ev("transaction.logged", "{}"), ev("transaction.logged", "{}")))
        assertTrue(e.isEmpty())
    }

    @Test fun `redeeming a regret earns an uncommon`() {
        val e = earns(listOf(evFor("transaction.regret_cleared", "u1")))
        assertEquals(RareTrigger.REDEEMED, e.single().trigger)
    }

    // ---------- anti-farming (spec 3.3) ----------

    @Test fun `re-tagging the same transaction repeatedly earns exactly one uncommon`() {
        // THE farming vector. setRegret only no-ops on an unchanged value, so
        // REGRET -> WORTH_IT -> REGRET -> WORTH_IT emits regret_cleared twice. Two taps,
        // repeated, would otherwise farm rewards without limit.
        val e = earns(listOf(
            evFor("transaction.regret_cleared", "u1"),
            evFor("transaction.regret_cleared", "u1"),
            evFor("transaction.regret_cleared", "u1"),
        ))
        assertEquals(1, e.size)
    }

    @Test fun `redeeming two different transactions earns two uncommons`() {
        val e = earns(listOf(
            evFor("transaction.regret_cleared", "u1"),
            evFor("transaction.regret_cleared", "u2"),
        ))
        assertEquals(2, e.size)
    }

    @Test fun `ten gate dodges in one month still earn only one uncommon`() {
        assertEquals(1, earns(List(10) { ev("gate.dodged", "{}") }).size)
    }

    @Test fun `gate dodges in two different months earn one each`() {
        val sep = List(3) { ev("gate.dodged", "{}", at = monthMillis(9)) }
        val oct = List(3) { ev("gate.dodged", "{}", at = monthMillis(10)) }
        assertEquals(2, earns(sep + oct).size)
    }

    @Test fun `a duplicated streak event earns only once`() {
        val e = earns(listOf(
            ev("streak.hit", """{"month":"2026-09","days":7}"""),
            ev("streak.hit", """{"month":"2026-09","days":7}"""),
        ))
        assertEquals(1, e.size)
    }

    // ---------- determinism (spec 4.1) ----------

    @Test fun `the same event list yields identical earns on repeated folds`() {
        val events = listOf(
            ev("streak.hit", """{"month":"2026-09","days":7}"""),
            ev("month.closed", """{"month":"2026-08","spentPaise":1,"overallBudgetPaise":10}"""),
        )
        val a = RareEngine.earns(events, emptyMap(), emptyMap(), 1)
        val b = RareEngine.earns(events, emptyMap(), emptyMap(), 1)
        assertEquals(a, b)
        assertEquals(a.map { it.species.id }, b.map { it.species.id })
    }

    @Test fun `a malformed payload is skipped rather than crashing the fold`() {
        val e = earns(listOf(
            ev("streak.hit", "not json at all"),
            ev("streak.hit", """{"month":"2026-09","days":7}"""),
        ))
        assertEquals(1, e.size)
    }

    private fun monthMillis(month: Int): Long =
        java.time.LocalDate.of(2026, month, 15)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.RareEngineTest'`

Expected: BUILD FAILED — `Unresolved reference 'RareEngine'`.

- [ ] **Step 3: Write the engine**

Create `app/src/main/java/com/expensegarden/app/game/RareEngine.kt`:

```kotlin
package com.expensegarden.app.game

import com.expensegarden.app.data.GameEventEntity
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Detects earned rares from the event log (spec §3). Pure: no IO, no clock, no randomness.
 *
 *  Earns are DERIVED, never emitted or stored (spec §4.2). That is not a stylistic choice —
 *  an emitter sees only the current window and therefore cannot enforce "once ever", while a
 *  fold over all of history can. The once-per-scope guarantee in §3.3 is the whole reason the
 *  collection cannot be farmed, and it only holds here.
 *
 *  NOTE ON org.json: this parses payloads, so it cannot be unit-tested on the JVM unless the
 *  test supplies well-formed input — org.json is an Android stub that throws "not mocked"
 *  only for the METHODS it lacks; JSONObject parsing itself works under Robolectric-free JVM
 *  tests in this project because the payloads are plain. Malformed input is caught. */
object RareEngine {

    private const val DODGES_FOR_EARN = 3
    private const val NO_SPEND_DAYS_FOR_EARN = 7
    private const val ROOT_CATEGORIES_FOR_EARN = 8
    private val LANDMARK_HOUSE_LEVELS = listOf(3, 4)

    /**
     * @param events the full game_event log, any order.
     * @param noSpendByMonth month key → no-spend days elapsed, from StreakMath.noSpendDays.
     * @param breadthByMonth month key → distinct root categories spent in.
     * @param houseLevel the current level from GardenFolder.houseLevel.
     */
    fun earns(
        events: List<GameEventEntity>,
        noSpendByMonth: Map<String, Int>,
        breadthByMonth: Map<String, Int>,
        houseLevel: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Earn> {
        val ordered = events.sortedBy { it.id }
        val out = mutableListOf<Earn>()

        // --- streaks: only 7 and 30 are rewards; 3 and 14 are progress ---
        for (e in ordered.filter { it.type == "streak.hit" }) {
            val o = parse(e) ?: continue
            val days = o.optInt("days", -1)
            val month = o.optString("month", "")
            val tier = when (days) {
                7 -> RareTier.UNCOMMON
                30 -> RareTier.RARE
                else -> null
            } ?: continue
            val trigger = if (days == 7) RareTrigger.STREAK_7 else RareTrigger.STREAK_30
            out += Earn(trigger, "streak$days:$month", tier, e.id, e.createdAt)
        }

        // --- gate dodges: N per month, once per month ---
        ordered.filter { it.type == "gate.dodged" }
            .groupBy { monthOf(it, zone) }
            .filterValues { it.size >= DODGES_FOR_EARN }
            .forEach { (month, group) ->
                // Seeded on the event that COMPLETED the earn, so the species is stable even
                // if more dodges follow.
                val trigger = group.sortedBy { it.id }[DODGES_FOR_EARN - 1]
                out += Earn(RareTrigger.GATE_DODGES, "dodges:$month", RareTier.UNCOMMON, trigger.id, trigger.createdAt)
            }

        // --- month closed under budget ---
        for (e in ordered.filter { it.type == "month.closed" }) {
            val o = parse(e) ?: continue
            // A null budget means none was set. Under-spending a budget that does not exist is
            // not an achievement, and treating null as 0 would invert the test.
            if (o.isNull("overallBudgetPaise")) continue
            val budget = o.optLong("overallBudgetPaise", -1L)
            val spent = o.optLong("spentPaise", Long.MAX_VALUE)
            if (budget <= 0L || spent > budget) continue
            val month = o.optString("month", "")
            out += Earn(RareTrigger.MONTH_UNDER_BUDGET, "under:$month", RareTier.RARE, e.id, e.createdAt)
        }

        // --- redemption: ONCE PER TRANSACTION, not per event (spec §3.3) ---
        ordered.filter { it.type == "transaction.regret_cleared" }
            .distinctBy { it.transactionUuid ?: it.id.toString() }
            .forEach { e ->
                val key = e.transactionUuid ?: e.id.toString()
                out += Earn(RareTrigger.REDEEMED, "redeem:$key", RareTier.UNCOMMON, e.id, e.createdAt)
            }

        // --- derived month facts: no clock involved, the caller supplies them ---
        for ((month, days) in noSpendByMonth) {
            if (days < NO_SPEND_DAYS_FOR_EARN) continue
            out += Earn(RareTrigger.NO_SPEND_DAYS, "nospend:$month", RareTier.UNCOMMON,
                seedFrom("nospend:$month"), 0L)
        }
        for ((month, roots) in breadthByMonth) {
            if (roots < ROOT_CATEGORIES_FOR_EARN) continue
            out += Earn(RareTrigger.CATEGORY_BREADTH, "breadth:$month", RareTier.RARE,
                seedFrom("breadth:$month"), 0L)
        }
        for (level in LANDMARK_HOUSE_LEVELS) {
            if (houseLevel < level) continue
            out += Earn(RareTrigger.HOUSE_LEVEL, "house:$level", RareTier.LANDMARK,
                seedFrom("house:$level"), 0L)
        }

        // One earn per scope key, and a stable order so the pairing in RarePairing is
        // reproducible across folds.
        return out.distinctBy { it.scopeKey }.sortedWith(compareBy({ it.atMillis }, { it.scopeKey }))
    }

    /** Seed for earns that come from derived facts rather than a single event. Uses the scope
     *  key itself so it is stable forever — the same month always yields the same species. */
    private fun seedFrom(scopeKey: String): Long = scopeKey.hashCode().toLong()

    private fun parse(e: GameEventEntity): JSONObject? =
        runCatching { JSONObject(e.payloadJson) }.getOrNull()

    private fun monthOf(e: GameEventEntity, zone: ZoneId): String =
        YearMonth.from(Instant.ofEpochMilli(e.createdAt).atZone(zone)).toString()
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.RareEngineTest'`

Expected: BUILD SUCCESSFUL, 21 tests passing.

If the malformed-payload or month.closed tests fail with "not mocked", `org.json` is unavailable in this JVM test context. Do NOT add `testOptions.unitTests.isReturnDefaultValues` — that setting is deliberately absent — see the 1D design spec's testing section. Instead, move payload parsing behind a small injected parser the way `DigestRepository` does, and report the change.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/RareEngine.kt app/src/test/
git commit -m "feat: rare earning engine with once-per-scope anti-farming"
```

---

## Task 3: RarePairing — banking a seed onto the next qualifying purchase

Spec §4, §4.2.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/game/RarePairing.kt`
- Test: `app/src/test/java/com/expensegarden/app/game/RarePairingTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/game/RarePairingTest.kt`:

```kotlin
package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RarePairingTest {

    private fun earn(at: Long, key: String = "k$at", tier: RareTier = RareTier.UNCOMMON) =
        Earn(RareTrigger.STREAK_7, key, tier, sourceEventId = at, atMillis = at)

    /** uuid, occurredAt, and whether it can carry a rare. */
    private fun cand(uuid: String, at: Long, eligible: Boolean = true) =
        RarePairing.Candidate(uuid, at, eligible)

    @Test fun `an earn attaches to the next transaction after it`() {
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("a", 50), cand("b", 150)))
        assertNull(m["a"])
        assertEquals("k100", m["b"]?.scopeKey)
    }

    @Test fun `an earn with no later transaction stays pending`() {
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("a", 50)))
        assertTrue(m.isEmpty())
    }

    @Test fun `two earns attach to two different transactions`() {
        val m = RarePairing.assign(
            listOf(earn(100), earn(200)),
            listOf(cand("a", 150), cand("b", 250)),
        )
        assertEquals("k100", m["a"]?.scopeKey)
        assertEquals("k200", m["b"]?.scopeKey)
    }

    @Test fun `an ineligible transaction is skipped and the seed waits`() {
        // A weed or zombie purchase must not consume a rare seed (spec §4).
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("weed", 150, eligible = false), cand("ok", 200)))
        assertNull(m["weed"])
        assertEquals("k100", m["ok"]?.scopeKey)
    }

    @Test fun `landmarks are never paired to a transaction`() {
        // Landmarks are island features, not plants (spec §8) — they record into the album only.
        val m = RarePairing.assign(listOf(earn(100, tier = RareTier.LANDMARK)), listOf(cand("a", 150)))
        assertTrue(m.isEmpty())
    }

    @Test fun `one transaction carries at most one rare`() {
        val m = RarePairing.assign(listOf(earn(100), earn(110)), listOf(cand("only", 150)))
        assertEquals(1, m.size)
        assertEquals("k100", m["only"]?.scopeKey)
    }

    @Test fun `pairing is stable across repeated calls`() {
        val earns = listOf(earn(100), earn(200))
        val cands = listOf(cand("a", 150), cand("b", 250))
        assertEquals(RarePairing.assign(earns, cands), RarePairing.assign(earns, cands))
    }

    @Test fun `transactions are consumed in occurredAt order regardless of input order`() {
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("late", 300), cand("early", 150)))
        assertEquals("k100", m["early"]?.scopeKey)
        assertNull(m["late"])
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.RarePairingTest'`

Expected: BUILD FAILED — `Unresolved reference 'RarePairing'`.

- [ ] **Step 3: Write the pairing**

Create `app/src/main/java/com/expensegarden/app/game/RarePairing.kt`:

```kotlin
package com.expensegarden.app.game

/** Banks each earned seed onto the next qualifying purchase (spec §4).
 *
 *  This is what preserves the app's most load-bearing property: every plant is exactly one
 *  real transaction. Rewarding restraint collides with that — a no-spend week has no purchase
 *  behind it — and the resolution is to bank the reward rather than to plant something
 *  fictional. "A week of restraint makes the next thing you buy grow into something better."
 *
 *  Pure and order-stable, so the fold reproduces the same island on every replay. */
object RarePairing {

    /**
     * @param eligible false for investments, weeds and zombies. An ineligible purchase does
     *   not consume the seed — it waits for a clean one, so a breach or a regret never eats a
     *   reward you earned by behaving well.
     */
    data class Candidate(val uuid: String, val occurredAt: Long, val eligible: Boolean)

    /** @return uuid → the Earn that transaction should render as. Absent = grows normally. */
    fun assign(earns: List<Earn>, candidates: List<Candidate>): Map<String, Earn> {
        // Landmarks are island features, not plants — they belong to the album and, from 4B,
        // to the island itself. They must never consume a plant slot.
        val plantable = earns.filter { it.tier != RareTier.LANDMARK }
            .sortedWith(compareBy({ it.atMillis }, { it.scopeKey }))
        if (plantable.isEmpty()) return emptyMap()

        val ordered = candidates.sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
        val out = LinkedHashMap<String, Earn>()
        var next = 0

        for (c in ordered) {
            if (next >= plantable.size) break
            if (!c.eligible) continue
            val earn = plantable[next]
            // Strictly after: a purchase made before the earn cannot retroactively become it.
            if (c.occurredAt <= earn.atMillis) continue
            out[c.uuid] = earn
            next++
        }
        return out
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.RarePairingTest'`

Expected: BUILD SUCCESSFUL, 8 tests passing.

- [ ] **Step 5: Run the whole JVM suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest`

Expected: BUILD SUCCESSFUL. The suite was 181 before this phase; it should now be 181 + 6 + 21 + 8 = 216.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/RarePairing.kt app/src/test/
git commit -m "feat: bank an earned rare onto the next qualifying purchase"
```

---

## Task 4: Wire the engine into the fold

Spec §4.2. `GardenFolder` runs the engine and threads assignments into `PlantMapper`.

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/GardenModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/game/PlantMapper.kt`
- Modify: `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt`
- Test: `app/src/test/java/com/expensegarden/app/game/RareFoldTest.kt`

- [ ] **Step 1: Add the rare field to Plant**

In `GardenModel.kt`, add to `data class Plant` as the last field:

```kotlin
    /** Non-null when this purchase grew as an earned rare (spec §4). Null for ordinary plants,
     *  which is every plant that existed before Phase 4A — the field is additive and the
     *  renderer falls back to the archetype sprite when it is absent. */
    val rare: RareSpecies? = null,
```

The Kotlin default keeps every existing construction site compiling — unlike `updatedAt` in 2A, there is no invariant here that requires the compiler to force the issue.

- [ ] **Step 2: Let PlantMapper accept an assignment**

In `PlantMapper.kt`, change the signature and the return:

```kotlin
    fun map(txn: TransactionEntity, tree: CategoryTree, rare: RareSpecies? = null): MappedPlant? {
```

and, at the end of the function, replace the return with:

```kotlin
        // A rare overrides the LOOK, never the semantics. A rare that is later tagged as a
        // regret still becomes a zombie (spec §6) — the honesty of the garden outranks the
        // prettiness of the collection, so the zombie/weed branches above win.
        val effective = if (isZombie || isWeed) null else rare
        return MappedPlant(
            txn.uuid,
            effective?.baseArchetype ?: archetype,
            tier,
            isWeed,
            seed,
            variant,
            rare = effective,
        )
```

Add `val rare: RareSpecies? = null` as the last field of `MappedPlant`.

- [ ] **Step 3: Write the failing fold test**

Create `app/src/test/java/com/expensegarden/app/game/RareFoldTest.kt`:

```kotlin
package com.expensegarden.app.game

import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import com.expensegarden.app.stats.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RareFoldTest {
    private val categories = listOf(
        CategoryEntity(1, "Food & Drinks", null, false, updatedAt = 1L),
        CategoryEntity(7, "Shopping", null, false, updatedAt = 1L),
        CategoryEntity(2, "Groceries", null, true, updatedAt = 1L),
    )
    private val tree = CategoryTree(categories)

    private fun txn(uuid: String, categoryId: Long, regret: Regret = Regret.UNRATED, breached: Boolean = false) =
        TransactionEntity(
            uuid = uuid, amountPaise = 5_000, payeeId = 1, categoryId = categoryId,
            source = TxnSource.MANUAL, status = TxnStatus.LOGGED, regret = regret,
            breachedAtLogging = breached, note = null, occurredAt = 1_000L, createdAt = 1_000L,
            updatedAt = 1L,
        )

    private val golden = RareCatalog.byId("golden_tulip")!!

    @Test fun `a mapped plant carries its rare and renders the base archetype`() {
        val m = PlantMapper.map(txn("a", 7), tree, rare = golden)!!
        assertEquals(golden, m.rare)
        assertEquals(Archetype.TULIP, m.archetype)
    }

    @Test fun `a plant with no assignment has no rare`() {
        assertNull(PlantMapper.map(txn("a", 7), tree).rare)
    }

    @Test fun `a regretted rare still becomes a zombie`() {
        // Spec §6: no exemptions. The garden's honesty outranks the collection.
        val m = PlantMapper.map(txn("a", 7, regret = Regret.REGRET), tree, rare = golden)!!
        assertEquals(Archetype.ZOMBIE, m.archetype)
        assertNull(m.rare)
    }

    @Test fun `a breach purchase does not render as a rare`() {
        val m = PlantMapper.map(txn("a", 7, breached = true), tree, rare = golden)!!
        assertNull(m.rare)
    }

    @Test fun `a necessity can carry a rare`() {
        // Necessities are never shamed; excluding them would quietly imply they are lesser.
        assertNotNull(PlantMapper.map(txn("a", 2), tree, rare = golden)!!.rare)
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.RareFoldTest'`

Expected: BUILD FAILED — `No value passed for parameter` or `Unresolved reference 'rare'` until Steps 1–2 are complete. If they are complete, this should pass.

- [ ] **Step 5: Thread it through GardenFolder**

In `GardenFolder.foldAllTime`, after `monthsTracked` and `level` are computed and before `mapped` is built, add:

```kotlin
        // Spec §4.2: earns are derived from the log on every fold, never stored. At one user's
        // lifetime volume this is a few thousand events, and foldAllTime already walks every
        // transaction anyway.
        val noSpendByMonth = ordered.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)).toString() }
            .mapValues { (_, txns) ->
                val days = txns.map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfMonth }.toSet()
                val monthLength = YearMonth.parse(
                    YearMonth.from(Instant.ofEpochMilli(txns.first().occurredAt).atZone(zone)).toString()
                ).lengthOfMonth()
                (1..monthLength).count { it !in days }
            }
        val breadthByMonth = ordered.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)).toString() }
            .mapValues { (_, txns) -> txns.mapNotNull { tree.ancestorChain(it.categoryId).lastOrNull() }.distinct().size }
        val earns = RareEngine.earns(allEvents, noSpendByMonth, breadthByMonth, level, zone)
        val assignment = RarePairing.assign(earns, ordered.map {
            val m = PlantMapper.map(it, tree)
            RarePairing.Candidate(it.uuid, it.occurredAt, eligible = m != null && !m.isWeed && m.archetype != Archetype.ZOMBIE)
        })
```

then change the `mapped` construction to pass the assignment:

```kotlin
        val mapped = ordered.mapNotNull { t ->
            PlantMapper.map(t, tree, rare = assignment[t.uuid]?.species)?.let { m ->
                m to YearMonth.from(Instant.ofEpochMilli(t.occurredAt).atZone(zone)).toString()
            }
        }
```

and carry `rare` into the `Plant` construction:

```kotlin
        val plants = mapped.mapIndexed { i, (m, _) ->
            Plant(m.txnUuid, m.archetype, m.sizeTier, m.isWeed, tiles[i], m.seed, m.variant, rare = m.rare)
        }
```

`foldAllTime` needs the full event log for this. It currently receives `currentMonthEvents`; add a parameter `allEvents: List<GameEventEntity> = currentMonthEvents` and pass the real all-time list from `GardenRepository.observeAllTimeGarden` via a new `gameEventDao().observeEventsBetween(0L, Long.MAX_VALUE)`.

- [ ] **Step 6: Verify the whole suite still passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest`

Expected: BUILD SUCCESSFUL, 221 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ app/src/test/
git commit -m "feat: fold derives earned rares and banks them onto purchases"
```

---

## Task 5: Determinism on a real database

Spec §9. The guarantee everything rests on, verified rather than assumed.

**Files:**
- Test: `app/src/androidTest/java/com/expensegarden/app/data/RareDeterminismTest.kt`

- [ ] **Step 1: Write the test**

Create `app/src/androidTest/java/com/expensegarden/app/data/RareDeterminismTest.kt` (snake_case names — backticks with spaces are fatal here):

```kotlin
package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Spec §4.1: the island must be identical on every replay of the log. If a rare were rolled
 *  at runtime this would fail, and the greenhouse's archived months would silently drift. */
@RunWith(AndroidJUnit4::class)
class RareDeterminismTest {
    private lateinit var db: AppDatabase
    private lateinit var garden: GardenRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        garden = GardenRepository(db, LedgerRepository(db))
    }

    @After fun tearDown() = db.close()

    @Test fun the_same_log_folds_to_the_same_rares_twice() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "P", vpa = null, defaultCategoryId = null, updatedAt = 1L))
        repeat(3) {
            db.gameEventDao().insert(GameEventEntity(
                type = "gate.dodged", payloadJson = "{}", transactionUuid = null,
                createdAt = System.currentTimeMillis(),
            ))
        }
        repeat(4) { i ->
            db.transactionDao().insert(TransactionEntity(
                uuid = UUID.randomUUID().toString(), amountPaise = 5_000L, payeeId = payeeId,
                categoryId = 103, source = TxnSource.MANUAL, status = TxnStatus.LOGGED,
                breachedAtLogging = false, note = null,
                occurredAt = System.currentTimeMillis() + i, createdAt = System.currentTimeMillis(),
                updatedAt = 1L,
            ))
        }

        val first = garden.observeAllTimeGarden().first().plants.map { it.txnUuid to it.rare?.id }
        val second = garden.observeAllTimeGarden().first().plants.map { it.txnUuid to it.rare?.id }

        assertEquals(first, second)
    }

    @Test fun a_dodge_earn_lands_on_a_later_purchase() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "P", vpa = null, defaultCategoryId = null, updatedAt = 1L))
        val t0 = System.currentTimeMillis()
        repeat(3) {
            db.gameEventDao().insert(GameEventEntity(
                type = "gate.dodged", payloadJson = "{}", transactionUuid = null, createdAt = t0,
            ))
        }
        db.transactionDao().insert(TransactionEntity(
            uuid = "later", amountPaise = 5_000L, payeeId = payeeId, categoryId = 103,
            source = TxnSource.MANUAL, status = TxnStatus.LOGGED, breachedAtLogging = false,
            note = null, occurredAt = t0 + 60_000, createdAt = t0 + 60_000, updatedAt = 1L,
        ))

        val plants = garden.observeAllTimeGarden().first().plants
        assertEquals(1, plants.count { it.rare != null })
    }
}
```

- [ ] **Step 2: Run it**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.RareDeterminismTest`

Expected: 2 tests, 0 failures.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/
git commit -m "test: the rare fold is deterministic across replays"
```

---

## Task 6: The sprites

Spec §7. The pipeline is intact — verified 2026-09-06: the 9 GB quantized model at `~/.cache/mflux-models/flux1-schnell-q4` and the venv at `~/.cache/expense-garden-art-venv`.

**Files:**
- Modify: `tools/art/briefs.py`
- Create: `app/src/main/assets/garden/*.png`

- [ ] **Step 1: Read the existing brief format**

```bash
sed -n '1,60p' tools/art/briefs.py
```

Expected: a dict of sprite name → prompt text. Match its shape exactly; `gen.py` reads it directly.

- [ ] **Step 2: Add the new briefs**

Append entries for the 8 uncommon variants and 4 rare species named in `RareCatalog`. Each must use the **exact `spriteName`** from the catalogue as its key — `tulip_3`, `bell_flower_2`, `hedge_3`, `berry_bush_2`, `succulent_2`, `petal_flower_3`, `chai_cluster_2`, `vegetable_row_2`, `bonsai_0`, `lotus_0`, `night_orchid_0`, `firefly_fern_0` — because `SpritePainter` loads by that name and a typo renders nothing with no error.

Follow the established style in the file: the same isometric angle, the same chroma-key background rule, and **warm-palette sprites shoot on cyan, not magenta** — 1C.6 learned that magenta despill bleaches pinks and reds white.

- [ ] **Step 3: Generate**

```bash
cd tools/art && ~/.cache/expense-garden-art-venv/bin/python3 gen.py tulip_3 && cd "$(git rev-parse --show-toplevel)"
```

Expected: ~94s, and `app/src/main/assets/garden/tulip_3.png` appears. Open it with the Read tool and check: a recognisable golden tulip creature, saturated colour, clean transparent background, no cyan fringe, no baked shadow ellipse.

**Do not batch all twelve before looking at one.** If the first is wrong, the other eleven are wrong the same way.

- [ ] **Step 4: Generate the rest, reviewing each**

Run `gen.py <name>` for each remaining sprite. Inspect every output.

- [ ] **Step 5: Register the new archetypes**

The four RARE species are new `Archetype` values. Add `BONSAI, LOTUS, NIGHT_ORCHID, FIREFLY_FERN` to the enum in `GardenModel.kt`.

`ProceduralPainter`'s `when (archetype)` is exhaustive, so this is a compile error until each is handled — which is the desired behaviour. Give each a simple procedural fallback so the app still works when the sprite pack is absent.

- [ ] **Step 6: Verify**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add tools/art/briefs.py app/src/main/assets/garden/ app/src/main/java/com/expensegarden/app/
git commit -m "feat: rare species sprites and their archetypes"
```

---

## Task 7: The Collection album

Spec §5.

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/GardenViewModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/GreenhouseScreen.kt`

- [ ] **Step 1: Expose the earned set**

In `GardenRepository`, add:

```kotlin
    /** Everything earned so far, newest first — the album's data (spec §5). Derived from the
     *  same engine the fold uses, so the album can never disagree with the island. */
    suspend fun earnedRares(): List<Earn> {
        val garden = observeAllTimeGarden().first()
        val events = db.gameEventDao().eventsBetween(0L, Long.MAX_VALUE)
        // Reuses the fold's own derivation rather than a second code path.
        return RareEngine.earns(events, noSpendByMonth(), breadthByMonth(), garden.houseLevel)
            .sortedByDescending { it.atMillis }
    }
```

with `noSpendByMonth()` and `breadthByMonth()` extracted from the Task 4 fold logic into private helpers on the repository so both callers share one implementation.

- [ ] **Step 2: Add the ViewModel accessor**

In `GardenViewModel`:

```kotlin
    suspend fun collection(): List<Earn> = container.garden.earnedRares()
```

- [ ] **Step 3: Add the Collection section**

In `GreenhouseScreen`, above the months `LazyColumn`, add a collapsible Collection card:

```kotlin
        var earned by remember { mutableStateOf<List<Earn>?>(null) }
        LaunchedEffect(Unit) { earned = runCatching { gardenVm.collection() }.getOrNull() }

        earned?.let { list ->
            val earnedIds = list.map { it.species.id }.toSet()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Collection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${earnedIds.size} of ${RareCatalog.all().size} found",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    // Silhouettes with their condition (spec §5). Every condition is a
                    // behaviour the app already wants — there is nothing here a user could
                    // satisfy by spending more.
                    for (tier in RareTier.values()) {
                        Text(tier.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall)
                        for (s in RareCatalog.pool(tier)) {
                            val got = s.id in earnedIds
                            Text(
                                if (got) "· ${s.displayName}" else "· ??? — ${conditionFor(s)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (got) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
```

- [ ] **Step 4: Add the condition text**

At file level in `GreenhouseScreen.kt`:

```kotlin
/** What a locked slot tells you. Kept next to the UI rather than on RareSpecies so the
 *  catalogue stays free of presentation strings. */
private fun conditionFor(s: RareSpecies): String = when (s.tier) {
    RareTier.UNCOMMON -> "a 7-day streak, 3 gate dodges, 7 no-spend days, or redeeming a regret"
    RareTier.RARE -> "close a month under budget, a 30-day streak, or spend across 8 categories"
    RareTier.LANDMARK -> "keep tracking — 6 and 12 months"
}
```

- [ ] **Step 5: Verify on device**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew installDebug`

Open the greenhouse. Expected: a Collection card showing `0 of 14 found` on a fresh install, with every species listed as a silhouette and its condition.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/
git commit -m "feat: collection album in the greenhouse with locked-slot conditions"
```

---

## Task 8: Device verification

- [ ] **Step 1: Full suites**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest && ./gradlew connectedDebugAndroidTest && ./gradlew installDebug
```

Expected: all green. `installDebug` last and alone — the connected task uninstalls both APKs.

- [ ] **Step 2: Earn one for real**

On the device: set a small budget, start a payment that breaches it, back out at the gate. Repeat three times. Then log an ordinary transaction.

Expected: the new plant renders as a rare, and the greenhouse Collection shows `1 of 14 found` with that species named.

- [ ] **Step 3: Confirm the anti-farming rule holds on device**

Tag a transaction as a regret, then re-tag it worth-it, then regret, then worth-it again.

Expected: the collection count goes up by **exactly one**, not two. This is the §3.3 guarantee, verified where it actually runs.

- [ ] **Step 4: Commit**

```bash
git commit --allow-empty -m "test: phase 4a verified on device"
```

---

## Execution log (2026-09-06)

Tasks 1–5 and 7 built inline; Task 6's sprites generated from the pipeline. Commits
`295942c` → `7ae303d`. **251 JVM tests green, 62 instrumented green.**

Four design defects surfaced during implementation. All four were in the *spec*, not the code —
which is the useful part: each was a plausible-sounding decision that only failed when
something real ran.

**1. The engine could not have parsed JSON.** Task 2 as planned had `RareEngine` reading
`payloadJson` with `org.json`. That class is an Android stub which throws "not mocked" in JVM
tests, and `testOptions.unitTests.isReturnDefaultValues` is deliberately absent — verified: no
JVM test in the repo touches `org.json`, and every file that does lives in `data/`. The engine
would have been untestable off-device, which for the component holding the anti-farming
guarantee is unacceptable. Fixed by introducing `RareSignal`, a typed projection, with parsing
in `GardenRepository` — the same split `DigestEvent` / `DigestRepository.project` already uses.

**2. "7 no-spend days" was arithmetic, not restraint.** The spec explicitly dismissed
run-length math as "a distinction nobody will feel". Wrong within minutes: with one purchase in
a twenty-day stretch you already have nineteen no-spend days, so the trigger fired for
essentially every month and started handing out rares in three pre-existing fold tests. Now a
**seven-day consecutive run**, which is a thing you actually had to do.

**3. Rares re-labelled the plant.** The catalogue let an earn carry its species independently,
so `effective?.baseArchetype ?: archetype` could render a Groceries purchase as a Golden Tulip.
The plant would still be a real transaction, but it would misreport the category — and "the
garden is your spending" stops being true in the way that matters. Every plantable rare now
names a `baseArchetype` and is only ever assigned to a purchase that already grows it; a seed
declines anything else and waits. A rare decorates, it never re-labels.

Fixing that also removed work: because a rare is now just a higher **variant index** on its own
archetype, and `SpritePainter` already keys on `(archetype, variant)`, the renderer needed no
change at all beyond raising `MAX_VARIANTS`. Guarded by a test that rare variants never fall
inside the range `PlantMapper` rolls by chance — otherwise the reward would leak out for free.

**4. The first sprite came out pink.** `tulip_3`'s brief was `_DIVA + "burnished gold"`, and
`_DIVA` opens with "glossy pink bulb as the whole head". The model followed the earlier,
concrete clause and produced an ordinary tulip — a rare visually identical to its common form,
which defeats the collection entirely. Rare briefs are now standalone, stating their colour once
with the contradicting words removed.

**A false alarm worth recording.** Five failures were chased for a while that turned out to be
**stale test XML**: the Kotlin test compile was failing, so Gradle never re-ran anything and the
previous run's results were still on disk. Always confirm `compileDebugUnitTestKotlin` succeeded
before believing a failure list.

### Deferred from 4A

- **Landmark rendering** — 4B. Earned landmarks record into the album; a pond is not a grid cell.
- **"How you got it"** on each album entry. The `Earn` carries its trigger, but the album
  currently shows conditions per tier rather than per species.
