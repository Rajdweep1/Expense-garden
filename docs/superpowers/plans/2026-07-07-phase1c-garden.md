# Phase 1C: The Garden — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The garden takes over the home surface — a lively isometric Compose-Canvas scene folded purely from ledger history, with sprite-pack flora (Rajdweep-generated) behind a painter seam, greenhouse album, and an on-open reconciler for time-based events.

**Architecture:** `game/` is a pure fold (`GardenFolder`: txns + budgets + events → `GardenState`, uuid-seeded determinism, no new tables). `render/` draws one Canvas via `IsoMath` + a `PlantPainter` seam with two implementations (procedural placeholder first, sprites after the asset pack lands). UI: home becomes `GardenHomeScreen`, recent list moves to the dashboard, `GreenhouseScreen` shows archived months via the same fold.

**Tech Stack:** Existing pinned matrix only. Zero new dependencies, zero schema changes. Sprite PNGs are repo-committed under `app/src/main/assets/garden/`.

**Spec:** `docs/superpowers/specs/2026-07-06-phase1c-garden-design.md` (approved 2026-07-07, incl. sprite-track revision)

---

## Agent guardrails (binding; carried from 1A/1B)

- Every Gradle command: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` first. `adb` needs `export PATH="$PATH:/Users/rajdweep/Library/Android/sdk/platform-tools"`.
- JVM filter: `./gradlew testDebugUnitTest --tests "..."`. Instrumented filter: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` (emulator running).
- **`connectedDebugAndroidTest` uninstalls the app and wipes its data afterwards** (1B amendment #3). Reinstall with `installDebug` before any on-device smoke; recreate smoke data through the UI.
- Emulator automation lore: cold start swallows first taps → poll-tap loop until the expected text appears; dismiss IME with `keyevent 4` and re-dump before tapping below the fold; uiautomator can return "null root node" mid-animation → sleep 2 and retry.
- No version bumps, no dependencies, no deprecation fixes. `@OptIn(ExperimentalMaterial3Api::class)`/`ExperimentalLayoutApi` are expected.
- Tasks 11 and 17 are **user checkpoints**: STOP, present the screenshot(s), and wait for Rajdweep's approval before continuing. Task 0 ends with a **hand-off reminder to Rajdweep** (he asked to be reminded).
- If a step's output doesn't match its Expected line: STOP and report. Log deviations in Execution amendments.
- Plain commit messages. Never push. The full 1A+1B suite (46 JVM + 20 instrumented) stays green after every task.

## File structure

```
docs/assets/garden-sprite-brief.md            CREATE  Task 0 — the hand-off doc for Rajdweep
app/src/main/java/com/expensegarden/app/
  game/GardenModel.kt                         CREATE  enums + GardenState/Plant/Tile (pure)
  game/PlantMapper.kt                         CREATE  txn → archetype/size/weed (pure)
  game/SerpentineTiler.kt                     CREATE  plant count → grid + tiles (pure)
  game/StreakMath.kt                          CREATE  no-spend + under-pace streak (pure)
  game/GardenFolder.kt                        CREATE  the fold (pure)
  game/Reconciler.kt                          CREATE  month.closed/streak.hit decisions (pure)
  data/Daos.kt                                MODIFY  garden read queries (+TxnRow by uuid)
  data/GardenRepository.kt                    CREATE  fold assembly, observe flows, reconciler append
  GardenApp.kt                                MODIFY  AppContainer gains gardenRepo
  render/IsoMath.kt                           CREATE  projection/inverse/z-order/fit (pure)
  render/GardenPalette.kt                     CREATE  weather gradients + plant hues
  render/PlantPainter.kt                      CREATE  seam interface + ProceduralPainter
  render/SpritePainter.kt                     CREATE  Task 16 — asset-pack painter + loader
  render/GardenCanvas.kt                      CREATE  the scene composable
  ui/GardenViewModel.kt                       CREATE  garden state, reconciler kick, plant info
  ui/GardenHomeScreen.kt                      CREATE  home takeover (replaces HomeScreen.kt)
  ui/HomeScreen.kt                            DELETE  in Task 13 (content superseded)
  ui/DashboardViewModel.kt                    MODIFY  gains recent + setRegret
  ui/DashboardScreen.kt                       MODIFY  gains Recent section (rows + regret dialog)
  ui/GreenhouseScreen.kt                      CREATE  album
  MainActivity.kt                             MODIFY  gardenVm, routes garden/greenhouse
app/src/main/assets/garden/                   CREATE  Rajdweep's PNGs land here (Task 16)
app/src/test/java/com/expensegarden/app/
  game/PlantMapperTest.kt, game/SerpentineTilerTest.kt, game/StreakMathTest.kt,
  game/GardenFolderTest.kt, game/ReconcilerTest.kt, render/IsoMathTest.kt      CREATE (JVM)
app/src/androidTest/java/com/expensegarden/app/data/
  GardenDaoTest.kt, GardenRepositoryTest.kt                                    CREATE
```

---

### Task 0: Asset brief + hand-off to Rajdweep (parallel track — do this FIRST)

**Files:**
- Create: `docs/assets/garden-sprite-brief.md`

- [ ] **Step 1: Write the brief**

```markdown
# Garden Sprite Pack — Asset Brief (Phase 1C)

Generate with any free image AI (one prompt per sprite works well; keep a fixed style
preamble so the pack stays coherent). Fallback source if generation disappoints:
CC0 packs (e.g. kenney.nl). Drop finished files into `app/src/main/assets/garden/`
with EXACTLY these names — the loader matches by filename and silently falls back
to procedural art for anything missing, so a partial pack is fine.

## Style preamble (prepend to every prompt)
"Cute cozy mobile-game sprite, 2:1 isometric view seen slightly from above,
soft cartoon shading with a single key light from the top-left, thick rounded
shapes, pastel palette, crisp silhouette, single object centered on a fully
transparent background, no ground, no shadow, no text, high detail, 512x512"

## Palette anchors (keep the pack in this family)
grass greens #8cc968/#a7dd7f · foliage #5da24b–#93d47e · sky blue #8fd3ff
accent yellow #ffd54d · petal pink #ff9bb0 · weed plum #8a5fa0 · soil #7c5233

## Sprite inventory (10 files, PNG, 512×512, transparent)
| File | Subject |
|---|---|
| petal_flower.png | round daisy-like flower, yellow-orange head, two leaves |
| tulip.png | pink tulip, single bloom, one leaf |
| bell_flower.png | violet bellflower, two hanging bells |
| herb_tuft.png | small green herb bundle, ties of leaves |
| bush.png | round leafy bush with tiny white blossoms |
| hedge.png | neat rectangular trimmed hedge (dignified — this is rent & groceries) |
| perennial_shrub.png | sturdy flowering shrub, woody stem |
| tree.png | friendly round-canopy tree with visible trunk |
| thistle_weed.png | scraggly purple thistle, clearly "off" but cute-ugly, not gross |
| odd_mushroom.png | crooked pink-capped mushroom with pale dots |

## Hard format rules
- 512×512, fully transparent background, PNG.
- Subject fills ~80% of canvas height, horizontally centered.
- The plant's stem/base touches the bottom-center — the renderer anchors there
  and draws its own ground shadow. NO baked-in shadow or ground patch.
- Same camera angle and light direction across all 10 (batch-generate with the
  same preamble; regenerate any sprite that breaks the set's coherence).
```

- [ ] **Step 2: Commit**

```bash
git add docs/assets/garden-sprite-brief.md
git commit -m "docs: garden sprite pack asset brief for 1c"
```

- [ ] **Step 3: HAND-OFF — remind Rajdweep (he asked for this reminder)**

Tell him, verbatim enough: *"The sprite brief is ready at `docs/assets/garden-sprite-brief.md` — whenever you have 20 minutes, run the 10 prompts through any free image AI and drop the PNGs into `app/src/main/assets/garden/` with the exact filenames. Everything else proceeds in parallel; the sprites get wired in Task 16, and anything missing just renders procedurally."* Do NOT block — continue to Task 1 immediately.

---

### Task 1: game model + PlantMapper (TDD)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/game/GardenModel.kt`
- Create: `app/src/main/java/com/expensegarden/app/game/PlantMapper.kt`
- Create: `app/src/test/java/com/expensegarden/app/game/PlantMapperTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.game

import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import com.expensegarden.app.stats.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantMapperTest {
    // Mirror of the seed shape: necessity parents/children + discretionary families + investments (10)
    private val tree = CategoryTree(listOf(
        CategoryEntity(1, "Food & Drinks", null, false),
        CategoryEntity(103, "Chai & Snacks", 1, false),
        CategoryEntity(2, "Groceries", null, true),
        CategoryEntity(3, "Transport", null, true),
        CategoryEntity(302, "Cab & Auto", 3, false),
        CategoryEntity(6, "Entertainment", null, false),
        CategoryEntity(7, "Shopping", null, false),
        CategoryEntity(8, "Personal", null, false),
        CategoryEntity(10, "Investments", null, true),
        CategoryEntity(11, "Misc", null, false),
    ))

    private fun txn(cat: Long, paise: Long = 5_000, breached: Boolean = false, regret: Regret = Regret.UNRATED) =
        TransactionEntity(
            uuid = "u-$cat-$paise-$breached-$regret", amountPaise = paise, payeeId = 1,
            categoryId = cat, source = TxnSource.MANUAL, status = TxnStatus.LOGGED,
            breachedAtLogging = breached, regret = regret, note = null, occurredAt = 0L, createdAt = 0L,
        )

    @Test fun `discretionary maps to its family flower`() {
        assertEquals(Archetype.PETAL_FLOWER, PlantMapper.map(txn(103), tree)!!.archetype)   // Food family via child
        assertEquals(Archetype.BELL_FLOWER, PlantMapper.map(txn(6), tree)!!.archetype)
        assertEquals(Archetype.TULIP, PlantMapper.map(txn(7), tree)!!.archetype)
        assertEquals(Archetype.HERB_TUFT, PlantMapper.map(txn(8), tree)!!.archetype)
        assertEquals(Archetype.BUSH, PlantMapper.map(txn(11), tree)!!.archetype)
    }

    @Test fun `necessities are hedges or perennials and can never be weeds`() {
        val groceries = PlantMapper.map(txn(2, breached = true, regret = Regret.REGRET), tree)!!
        assertEquals(Archetype.HEDGE, groceries.archetype)
        assertFalse(groceries.isWeed)
        val transport = PlantMapper.map(txn(3), tree)!!
        assertEquals(Archetype.PERENNIAL_SHRUB, transport.archetype)
    }

    @Test fun `weed rule needs discretionary AND breach or regret`() {
        assertFalse(PlantMapper.map(txn(103), tree)!!.isWeed)
        assertTrue(PlantMapper.map(txn(103, breached = true), tree)!!.isWeed)
        assertTrue(PlantMapper.map(txn(103, regret = Regret.REGRET), tree)!!.isWeed)
        // discretionary child under a necessity parent: own flag decides
        assertTrue(PlantMapper.map(txn(302, breached = true), tree)!!.isWeed)
    }

    @Test fun `weeds get a weed archetype chosen deterministically by uuid`() {
        val w = PlantMapper.map(txn(103, breached = true), tree)!!
        assertTrue(w.archetype == Archetype.THISTLE_WEED || w.archetype == Archetype.ODD_MUSHROOM)
        assertEquals(w.archetype, PlantMapper.map(txn(103, breached = true), tree)!!.archetype) // stable
    }

    @Test fun `size tiers split at 100 and 1000 rupees`() {
        assertEquals(SizeTier.S, PlantMapper.map(txn(103, paise = 9_999), tree)!!.sizeTier)
        assertEquals(SizeTier.M, PlantMapper.map(txn(103, paise = 10_000), tree)!!.sizeTier)
        assertEquals(SizeTier.M, PlantMapper.map(txn(103, paise = 99_999), tree)!!.sizeTier)
        assertEquals(SizeTier.L, PlantMapper.map(txn(103, paise = 100_000), tree)!!.sizeTier)
    }

    @Test fun `investments are not plot plants`() =
        assertNull(PlantMapper.map(txn(10, paise = 500_000), tree))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.game.PlantMapperTest"`
Expected: FAILED — `unresolved reference: Archetype`

- [ ] **Step 3: Implement GardenModel.kt**

```kotlin
package com.expensegarden.app.game

enum class Weather { SUNNY, OVERCAST, DROUGHT }

enum class Archetype {
    PETAL_FLOWER, TULIP, BELL_FLOWER, HERB_TUFT, BUSH,      // discretionary families
    HEDGE, PERENNIAL_SHRUB,                                  // necessities — dignified
    TREE,                                                    // investments, back row
    THISTLE_WEED, ODD_MUSHROOM,                              // weeds — distinct, mildly embarrassing
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
)

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
)
```

- [ ] **Step 4: Implement PlantMapper.kt**

```kotlin
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
```

- [ ] **Step 5: Run to verify pass** — same command. Expected: 6 tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/GardenModel.kt app/src/main/java/com/expensegarden/app/game/PlantMapper.kt app/src/test/java/com/expensegarden/app/game/PlantMapperTest.kt
git commit -m "feat: garden model and plant mapper - archetypes, weed rule, size tiers"
```

---

### Task 2: SerpentineTiler (TDD)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/game/SerpentineTiler.kt`
- Create: `app/src/test/java/com/expensegarden/app/game/SerpentineTilerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Test

class SerpentineTilerTest {
    @Test fun `fills front row left to right then snakes back`() {
        val tiles = SerpentineTiler.tiles(7)
        assertEquals(Tile(0, 0), tiles[0])
        assertEquals(Tile(0, 4), tiles[4])
        assertEquals(Tile(1, 4), tiles[5])   // next row starts from the right (serpentine)
        assertEquals(Tile(1, 3), tiles[6])
    }

    @Test fun `grid keeps a minimum 4 rows and grows beyond 20 plants`() {
        assertEquals(4, SerpentineTiler.gridRows(1))
        assertEquals(4, SerpentineTiler.gridRows(20))
        assertEquals(5, SerpentineTiler.gridRows(21))
        assertEquals(5, SerpentineTiler.COLS)
    }

    @Test fun `assignment is a pure function of index`() =
        assertEquals(SerpentineTiler.tiles(30)[12], SerpentineTiler.tiles(13)[12])
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.game.SerpentineTilerTest"`
Expected: FAILED — `unresolved reference: SerpentineTiler`

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/SerpentineTiler.kt app/src/test/java/com/expensegarden/app/game/SerpentineTilerTest.kt
git commit -m "feat: serpentine tiler - chronological front-to-back tile assignment"
```

---

### Task 3: StreakMath (TDD)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/game/StreakMath.kt`
- Create: `app/src/test/java/com/expensegarden/app/game/StreakMathTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Test

class StreakMathTest {
    // dayTotals[d] = paise spent on day d (1-based). Month of 30 days, ₹3000 budget → allowance/day grows 115/day-ish.
    private val budget = 300_000L

    @Test fun `no-spend days count fully past zero days only`() {
        // today = day 5; days 1..4 count, day 5 (today) excluded even at zero
        val totals = mapOf(2 to 10_000L)     // spent only on day 2
        assertEquals(3, StreakMath.noSpendDays(totals, today = 5))
    }

    @Test fun `streak counts consecutive under-pace days ending yesterday`() {
        // days 1..4 all under pace (tiny spends), today = 5 → streak 4
        val totals = mapOf(1 to 1_000L, 3 to 1_000L)
        assertEquals(4, StreakMath.underPaceStreak(totals, budget, today = 5, daysInMonth = 30))
    }

    @Test fun `a breach day resets the streak`() {
        // day 3 blows past day-3 allowance (300000*3/30*1.15 = 34500): spend 40000 that day
        val totals = mapOf(3 to 40_000L)
        assertEquals(1, StreakMath.underPaceStreak(totals, budget, today = 5, daysInMonth = 30))  // only day 4 counts
    }

    @Test fun `no budget means no streak but no-spend still counts`() {
        assertEquals(0, StreakMath.underPaceStreak(emptyMap(), null, today = 5, daysInMonth = 30))
        assertEquals(4, StreakMath.noSpendDays(emptyMap(), today = 5))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.game.StreakMathTest"`
Expected: FAILED — `unresolved reference: StreakMath`

- [ ] **Step 3: Implement**

```kotlin
package com.expensegarden.app.game

import com.expensegarden.app.gate.GateEvaluator

/** Day-level derivations for the live month. `today` is 1-based; only fully-past days (1..today-1) count. */
object StreakMath {
    fun noSpendDays(dayTotalsPaise: Map<Int, Long>, today: Int): Int =
        (1 until today).count { (dayTotalsPaise[it] ?: 0L) == 0L }

    /** Consecutive days ending yesterday whose cumulative spend stayed ≤ that day's pace allowance. */
    fun underPaceStreak(dayTotalsPaise: Map<Int, Long>, budgetPaise: Long?, today: Int, daysInMonth: Int): Int {
        if (budgetPaise == null || budgetPaise <= 0) return 0
        var cumulative = 0L
        val underByDay = (1 until today).map { day ->
            cumulative += dayTotalsPaise[day] ?: 0L
            cumulative <= GateEvaluator.paceAllowancePaise(budgetPaise, day, daysInMonth)
        }
        return underByDay.takeLastWhile { it }.size
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/StreakMath.kt app/src/test/java/com/expensegarden/app/game/StreakMathTest.kt
git commit -m "feat: streak math - no-spend days and under-pace streak"
```

---

### Task 4: GardenFolder — the fold (TDD)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt`
- Create: `app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.game

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GardenFolderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val categories = listOf(
        CategoryEntity(1, "Food & Drinks", null, false),
        CategoryEntity(103, "Chai & Snacks", 1, false),
        CategoryEntity(2, "Groceries", null, true),
        CategoryEntity(10, "Investments", null, true),
    )
    private fun at(day: Int) = LocalDate.of(2026, 7, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private var n = 0
    private fun txn(cat: Long, day: Int, paise: Long = 5_000, breached: Boolean = false, regret: Regret = Regret.UNRATED) =
        TransactionEntity(
            uuid = "u${n++}", amountPaise = paise, payeeId = 1, categoryId = cat,
            source = TxnSource.MANUAL, status = TxnStatus.LOGGED, breachedAtLogging = breached,
            regret = regret, note = null, occurredAt = at(day), createdAt = at(day),
        )
    private fun dodge(day: Int) = GameEventEntity(
        id = n++.toLong(), type = "gate.dodged", payloadJson = "{}", transactionUuid = null, createdAt = at(day),
    )

    private fun fold(
        txns: List<TransactionEntity>,
        budgets: List<BudgetEntity> = emptyList(),
        events: List<GameEventEntity> = emptyList(),
        allTimeInvestmentCount: Int = 0,
        today: LocalDate = LocalDate.of(2026, 7, 10),
    ) = GardenFolder.fold("2026-07", txns, categories, budgets, events, allTimeInvestmentCount, today, zone)

    @Test fun `every logged txn plants in chronological order`() {
        val g = fold(listOf(txn(103, day = 3), txn(2, day = 1), txn(103, day = 2)))
        assertEquals(3, g.plants.size)
        assertEquals(Tile(0, 0), g.plants.first { it.archetype == Archetype.HEDGE }.tile)  // day-1 groceries planted first
        assertEquals(15_000L, g.spentPaise)
    }

    @Test fun `weather follows overall severity`() {
        val budget = listOf(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 100_000))
        assertEquals(Weather.SUNNY, fold(listOf(txn(103, 2, paise = 1_000)), budget).weather)
        // day 10/31 allowance = 1000*10/31*1.15 = ₹370.96; spend ₹500 → OVERCAST
        assertEquals(Weather.OVERCAST, fold(listOf(txn(103, 2, paise = 50_000)), budget).weather)
        assertEquals(Weather.DROUGHT, fold(listOf(txn(103, 2, paise = 150_000)), budget).weather)
        assertEquals(Weather.SUNNY, fold(listOf(txn(103, 2, paise = 1_000)), emptyList()).weather)  // no budget = sunny
    }

    @Test fun `regret flip re-folds a flower into a weed and back`() {
        val flower = fold(listOf(txn(103, 2)))
        assertTrue(flower.plants.single().archetype != Archetype.THISTLE_WEED)
        val weed = fold(listOf(txn(103, 2, regret = Regret.REGRET)))
        assertTrue(weed.plants.single().isWeed)
    }

    @Test fun `investments feed the back row not the plot`() {
        val g = fold(listOf(txn(10, 2, paise = 500_000)), allTimeInvestmentCount = 12)
        assertEquals(0, g.plants.size)
        assertEquals(2, g.backRowTreeCount)      // 10..24 SIPs → 2 trees
        assertEquals(12, g.trunkTier)
    }

    @Test fun `butterflies count dodges capped at five`() {
        val g = fold(emptyList(), events = (1..9).map { dodge(it) })
        assertEquals(5, g.butterflies)
    }

    @Test fun `archived month freezes at final day state`() {
        val budget = listOf(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 100_000))
        val g = GardenFolder.fold(
            "2026-07", listOf(txn(103, 2, paise = 150_000)), categories, budget, emptyList(),
            allTimeInvestmentCount = 0, today = LocalDate.of(2026, 8, 15), zone = zone,
        )
        assertTrue(g.archived)
        assertEquals(Weather.DROUGHT, g.weather)
    }

    @Test fun `identical inputs fold to identical state`() {
        val txns = listOf(txn(103, 2), txn(2, 3))
        assertEquals(fold(txns), fold(txns))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.game.GardenFolderTest"`
Expected: FAILED — `unresolved reference: GardenFolder`

- [ ] **Step 3: Implement**

```kotlin
package com.expensegarden.app.game

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.gate.Severity
import com.expensegarden.app.stats.CategoryTree
import com.expensegarden.app.stats.MonthStatsFolder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The garden is a pure function of history. Same inputs → identical GardenState (jitter is uuid-seeded). */
object GardenFolder {
    fun fold(
        monthKey: String,
        monthTxns: List<TransactionEntity>,          // LOGGED, occurredAt inside the month
        categories: List<CategoryEntity>,
        budgets: List<BudgetEntity>,                 // that month's rows
        monthEvents: List<GameEventEntity>,          // createdAt inside the month
        allTimeInvestmentCount: Int,
        today: LocalDate,
        zone: ZoneId,
    ): GardenState {
        val ym = YearMonth.parse(monthKey)
        val archived = YearMonth.from(today) > ym
        val daysInMonth = ym.lengthOfMonth()
        // For archived months everything freezes at the final day; live months use today.
        val effectiveDay = if (archived) daysInMonth else today.dayOfMonth

        val tree = CategoryTree(categories)
        val ordered = monthTxns.sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
        val mapped = ordered.mapNotNull { PlantMapper.map(it, tree) }
        val tiles = SerpentineTiler.tiles(mapped.size)
        val plants = mapped.mapIndexed { i, m ->
            Plant(m.txnUuid, m.archetype, m.sizeTier, m.isWeed, tiles[i], m.seed)
        }

        val leafSums = ordered.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val severity = MonthStatsFolder.fold(categories, leafSums, budgets, effectiveDay, daysInMonth).overallSeverity
        val weather = when (severity) {
            Severity.OK -> Weather.SUNNY
            Severity.PACE_WARNING -> Weather.OVERCAST
            Severity.BREACH -> Weather.DROUGHT
        }

        val dayTotals = ordered.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfMonth }
            .mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val overall = budgets.firstOrNull { it.categoryId == null }?.amountPaise
        val streakToday = if (archived) daysInMonth + 1 else today.dayOfMonth

        return GardenState(
            monthKey = monthKey,
            weather = weather,
            plants = plants,
            spentPaise = leafSums.values.sum(),
            backRowTreeCount = treeCount(allTimeInvestmentCount),
            trunkTier = allTimeInvestmentCount,
            butterflies = minOf(5, monthEvents.count { it.type == "gate.dodged" }),
            streakDays = StreakMath.underPaceStreak(dayTotals, overall, streakToday, daysInMonth),
            noSpendDays = StreakMath.noSpendDays(dayTotals, streakToday),
            archived = archived,
            gridRows = SerpentineTiler.gridRows(mapped.size),
            gridCols = SerpentineTiler.COLS,
        )
    }

    /** Tunable grove growth: 0 SIPs → no trees; then 1, 2 (≥10), 3 (≥25). Trunk thickens with every SIP. */
    private fun treeCount(sips: Int) = when {
        sips == 0 -> 0
        sips < 10 -> 1
        sips < 25 -> 2
        else -> 3
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: 7 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/GardenFolder.kt app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt
git commit -m "feat: garden folder - pure fold from ledger history to garden state"
```

---

### Task 5: Reconciler decisions (TDD)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/game/Reconciler.kt`
- Create: `app/src/test/java/com/expensegarden/app/game/ReconcilerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class ReconcilerTest {
    @Test fun `closes every elapsed month with data exactly once`() {
        val out = Reconciler.decide(
            currentMonth = YearMonth.of(2026, 7),
            monthsWithData = listOf(YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7)),
            closedMonths = setOf("2026-05"),
            currentStreakDays = 0,
            streakHitDaysThisMonth = emptySet(),
        )
        assertEquals(listOf("2026-06"), out.monthsToClose)
    }

    @Test fun `current month never closes`() {
        val out = Reconciler.decide(
            currentMonth = YearMonth.of(2026, 7),
            monthsWithData = listOf(YearMonth.of(2026, 7)),
            closedMonths = emptySet(),
            currentStreakDays = 0,
            streakHitDaysThisMonth = emptySet(),
        )
        assertTrue(out.monthsToClose.isEmpty())
    }

    @Test fun `streak thresholds fire once each as the streak grows`() {
        val first = Reconciler.decide(
            currentMonth = YearMonth.of(2026, 7), monthsWithData = emptyList(), closedMonths = emptySet(),
            currentStreakDays = 8, streakHitDaysThisMonth = emptySet(),
        )
        assertEquals(listOf(3, 7), first.streakHitsToEmit)
        val second = Reconciler.decide(
            currentMonth = YearMonth.of(2026, 7), monthsWithData = emptyList(), closedMonths = emptySet(),
            currentStreakDays = 8, streakHitDaysThisMonth = setOf(3, 7),
        )
        assertTrue(second.streakHitsToEmit.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.game.ReconcilerTest"`
Expected: FAILED — `unresolved reference: Reconciler`

- [ ] **Step 3: Implement**

```kotlin
package com.expensegarden.app.game

import java.time.YearMonth

/** Pure decisions for the on-open reconciler — the local-first answer to "no server, no cron".
 *  The repository turns these into appended game_events; both outputs are idempotent by construction. */
object Reconciler {
    val STREAK_THRESHOLDS = listOf(3, 7, 14, 30)

    data class Decisions(val monthsToClose: List<String>, val streakHitsToEmit: List<Int>)

    fun decide(
        currentMonth: YearMonth,
        monthsWithData: List<YearMonth>,
        closedMonths: Set<String>,
        currentStreakDays: Int,
        streakHitDaysThisMonth: Set<Int>,
    ): Decisions = Decisions(
        monthsToClose = monthsWithData
            .filter { it < currentMonth && it.toString() !in closedMonths }
            .sorted()
            .map { it.toString() },
        streakHitsToEmit = STREAK_THRESHOLDS.filter { it <= currentStreakDays && it !in streakHitDaysThisMonth },
    )
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/Reconciler.kt app/src/test/java/com/expensegarden/app/game/ReconcilerTest.kt
git commit -m "feat: reconciler decisions - idempotent month close and streak hits"
```

---

### Task 6: DAO additions for the garden (instrumented)

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/Daos.kt`
- Create: `app/src/androidTest/java/com/expensegarden/app/data/GardenDaoTest.kt`

- [ ] **Step 1: Write the failing tests**

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

@RunWith(AndroidJUnit4::class)
class GardenDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
    }

    @After fun teardown() = db.close()

    private suspend fun logTxn(categoryId: Long, paise: Long, at: Long): String {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "p$at", vpa = null, defaultCategoryId = null))
        val uuid = UUID.randomUUID().toString()
        db.transactionDao().insert(TransactionEntity(
            uuid = uuid, amountPaise = paise, payeeId = payeeId, categoryId = categoryId,
            source = TxnSource.MANUAL, status = TxnStatus.LOGGED, breachedAtLogging = false,
            note = null, occurredAt = at, createdAt = at,
        ))
        return uuid
    }

    @Test fun logged_rows_between_bounds_flow_and_suspend() = runBlocking {
        logTxn(103, 1_000, at = 100L)
        logTxn(103, 2_000, at = 200L)
        logTxn(103, 4_000, at = 900L)
        assertEquals(2, db.transactionDao().loggedBetween(0L, 500L).size)
        assertEquals(2, db.transactionDao().observeLoggedBetween(0L, 500L).first().size)
    }

    @Test fun txn_row_by_uuid_joins_names() = runBlocking {
        val uuid = logTxn(103, 1_000, at = 100L)
        val row = db.transactionDao().rowByUuid(uuid)!!
        assertEquals("Chai & Snacks", row.categoryName)
        assertEquals(1_000L, row.amountPaise)
    }

    @Test fun events_between_and_by_type() = runBlocking {
        db.gameEventDao().insert(GameEventEntity(type = "gate.dodged", payloadJson = "{}", transactionUuid = null, createdAt = 100L))
        db.gameEventDao().insert(GameEventEntity(type = "month.closed", payloadJson = "{}", transactionUuid = null, createdAt = 200L))
        assertEquals(1, db.gameEventDao().eventsBetween(0L, 150L).size)
        assertEquals(1, db.gameEventDao().observeEventsBetween(0L, 150L).first().size)
        assertEquals(1, db.gameEventDao().ofType("month.closed").size)
    }

    @Test fun logged_count_in_categories_and_earliest() = runBlocking {
        logTxn(10, 1_000, at = 100L)
        logTxn(10, 1_000, at = 300L)
        logTxn(103, 1_000, at = 200L)
        assertEquals(2, db.transactionDao().observeLoggedCountIn(listOf(10L)).first())
        assertEquals(100L, db.transactionDao().earliestLoggedAt())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GardenDaoTest`
Expected: compile FAILED — `unresolved reference: loggedBetween` (and friends).

- [ ] **Step 3: Implement — append to `TransactionDao` in Daos.kt**

```kotlin
    @Query("SELECT * FROM txn WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis")
    suspend fun loggedBetween(fromMillis: Long, toMillis: Long): List<TransactionEntity>

    @Query("SELECT * FROM txn WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis")
    fun observeLoggedBetween(fromMillis: Long, toMillis: Long): Flow<List<TransactionEntity>>

    @Query(
        """SELECT t.uuid, t.amountPaise, p.name AS payeeName, c.name AS categoryName,
                  t.categoryId, t.regret, t.occurredAt
           FROM txn t JOIN payee p ON p.id = t.payeeId JOIN category c ON c.id = t.categoryId
           WHERE t.uuid = :uuid"""
    )
    suspend fun rowByUuid(uuid: String): TxnRow?

    @Query("SELECT COUNT(*) FROM txn WHERE status = 'LOGGED' AND categoryId IN (:categoryIds)")
    fun observeLoggedCountIn(categoryIds: List<Long>): Flow<Int>

    @Query("SELECT MIN(occurredAt) FROM txn WHERE status = 'LOGGED'")
    suspend fun earliestLoggedAt(): Long?
```

And to `GameEventDao`:

```kotlin
    @Query("SELECT * FROM game_event WHERE createdAt BETWEEN :fromMillis AND :toMillis ORDER BY id")
    suspend fun eventsBetween(fromMillis: Long, toMillis: Long): List<GameEventEntity>

    @Query("SELECT * FROM game_event WHERE createdAt BETWEEN :fromMillis AND :toMillis ORDER BY id")
    fun observeEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<GameEventEntity>>

    @Query("SELECT * FROM game_event WHERE type = :type ORDER BY id")
    suspend fun ofType(type: String): List<GameEventEntity>
```

- [ ] **Step 4: Run to verify pass** — the Step 2 command. Expected: 4 tests passed. Then `./gradlew connectedDebugAndroidTest` — all 24 instrumented green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/Daos.kt app/src/androidTest/java/com/expensegarden/app/data/GardenDaoTest.kt
git commit -m "feat: garden dao reads - logged rows, events windows, row by uuid"
```

---

### Task 7: GardenRepository + reconciler end-to-end (instrumented)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt`
- Modify: `app/src/main/java/com/expensegarden/app/GardenApp.kt`
- Create: `app/src/androidTest/java/com/expensegarden/app/data/GardenRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.game.Weather
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class GardenRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepository
    private lateinit var garden: GardenRepository
    private val zone = ZoneId.systemDefault()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        ledger = LedgerRepository(db)
        garden = GardenRepository(db, ledger)
    }

    @After fun teardown() = db.close()

    private suspend fun log(cat: Long, paise: Long, at: Long = System.currentTimeMillis()) =
        ledger.saveManualLogged(
            LedgerRepository.Draft(vpa = null, payeeName = "p", amountPaise = paise, categoryId = cat, note = null, occurredAt = at),
            breachedAtLogging = false,
        )

    @Test fun observed_garden_reflects_logs_live() = runBlocking {
        log(103, 5_000)
        val g = garden.observeCurrentGarden().first()
        assertEquals(1, g.plants.size)
        assertEquals(Weather.SUNNY, g.weather)
    }

    @Test fun dodge_becomes_butterfly() = runBlocking {
        ledger.recordGateDodge(10_000, categoryId = 103)
        assertEquals(1, garden.observeCurrentGarden().first().butterflies)
    }

    @Test fun reconciler_closes_past_month_once() = runBlocking {
        val lastMonth = YearMonth.now(zone).minusMonths(1)
        log(103, 5_000, at = lastMonth.atDay(10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
        garden.runReconciler()
        garden.runReconciler()   // idempotent
        val closed = db.gameEventDao().ofType("month.closed")
        assertEquals(1, closed.size)
        assertTrue(closed.single().payloadJson.contains(lastMonth.toString()))
    }

    @Test fun archived_month_folds_frozen() = runBlocking {
        val lastMonth = YearMonth.now(zone).minusMonths(1)
        log(103, 5_000, at = lastMonth.atDay(10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
        val g = garden.foldMonth(lastMonth.toString())
        assertTrue(g.archived)
        assertEquals(1, g.plants.size)
        assertEquals(listOf(lastMonth.toString(), YearMonth.now(zone).toString()), garden.monthsWithData())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GardenRepositoryTest`
Expected: compile FAILED — `unresolved reference: GardenRepository`

- [ ] **Step 3: Implement GardenRepository.kt**

```kotlin
package com.expensegarden.app.data

import com.expensegarden.app.game.GardenFolder
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.Reconciler
import com.expensegarden.app.game.StreakMath
import com.expensegarden.app.stats.CategoryTree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Assembles fold inputs from Room and appends reconciler events. All game logic lives in game/ (pure). */
class GardenRepository(private val db: AppDatabase, private val ledger: LedgerRepository) {
    private val zone: ZoneId = ZoneId.systemDefault()

    /** Live garden for the current month. Re-collection re-derives the month key (same idiom as the VMs). */
    fun observeCurrentGarden(): Flow<GardenState> {
        val monthKey = ledger.currentMonthKey()
        val (from, to) = ledger.boundsOfMonth(monthKey)
        return combine(
            db.transactionDao().observeLoggedBetween(from, to),
            db.categoryDao().observeAll(),
            db.budgetDao().observeAllForMonth(monthKey),
            db.gameEventDao().observeEventsBetween(from, to),
            db.transactionDao().observeLoggedCountIn(investmentIds()),
        ) { txns, cats, budgets, events, sips ->
            GardenFolder.fold(monthKey, txns, cats, budgets, events, sips, LocalDate.now(zone), zone)
        }
    }

    suspend fun foldMonth(monthKey: String): GardenState {
        val (from, to) = ledger.boundsOfMonth(monthKey)
        val cats = db.categoryDao().all()
        return GardenFolder.fold(
            monthKey,
            db.transactionDao().loggedBetween(from, to),
            cats,
            db.budgetDao().allForMonth(monthKey),
            db.gameEventDao().eventsBetween(from, to),
            allTimeInvestmentCount = db.transactionDao().loggedBetween(0L, Long.MAX_VALUE)
                .count { it.categoryId in investmentIds() },
            today = LocalDate.now(zone),
            zone = zone,
        )
    }

    /** Months that have any LOGGED data, oldest first, current month included. */
    suspend fun monthsWithData(): List<String> {
        val earliest = db.transactionDao().earliestLoggedAt() ?: return emptyList()
        val start = YearMonth.from(Instant.ofEpochMilli(earliest).atZone(zone))
        val now = YearMonth.now(zone)
        return generateSequence(start) { if (it < now) it.plusMonths(1) else null }
            .map { it.toString() }
            .filter { key ->
                val (f, t) = ledger.boundsOfMonth(key)
                db.transactionDao().loggedBetween(f, t).isNotEmpty()
            }
            .toList()
    }

    /** On-open reconciler: append month.closed for elapsed months and streak.hit thresholds, idempotently. */
    suspend fun runReconciler() {
        val nowMonth = YearMonth.now(zone)
        val closed = db.gameEventDao().ofType("month.closed")
            .mapNotNull { runCatching { JSONObject(it.payloadJson).getString("month") }.getOrNull() }
            .toSet()
        val monthKey = nowMonth.toString()
        val (from, to) = ledger.boundsOfMonth(monthKey)
        val monthTxns = db.transactionDao().loggedBetween(from, to)
        val dayTotals = monthTxns.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfMonth }
            .mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val overall = db.budgetDao().allForMonth(monthKey).firstOrNull { it.categoryId == null }?.amountPaise
        val today = LocalDate.now(zone)
        val streak = StreakMath.underPaceStreak(dayTotals, overall, today.dayOfMonth, nowMonth.lengthOfMonth())
        val hitAlready = db.gameEventDao().ofType("streak.hit")
            .mapNotNull { runCatching { JSONObject(it.payloadJson) }.getOrNull() }
            .filter { it.optString("month") == monthKey }
            .map { it.getInt("days") }
            .toSet()

        val decisions = Reconciler.decide(
            currentMonth = nowMonth,
            monthsWithData = monthsWithData().map { YearMonth.parse(it) },
            closedMonths = closed,
            currentStreakDays = streak,
            streakHitDaysThisMonth = hitAlready,
        )

        for (m in decisions.monthsToClose) {
            val (f, t) = ledger.boundsOfMonth(m)
            val spent = db.transactionDao().loggedSumBetween(f, t)
            val budget = db.budgetDao().allForMonth(m).firstOrNull { it.categoryId == null }?.amountPaise
            val payload = JSONObject().put("month", m).put("spentPaise", spent)
                .put("overallBudgetPaise", budget ?: JSONObject.NULL)
            db.gameEventDao().insert(GameEventEntity(
                type = "month.closed", payloadJson = payload.toString(), transactionUuid = null,
                createdAt = System.currentTimeMillis(),
            ))
        }
        for (days in decisions.streakHitsToEmit) {
            val payload = JSONObject().put("month", monthKey).put("days", days)
            db.gameEventDao().insert(GameEventEntity(
                type = "streak.hit", payloadJson = payload.toString(), transactionUuid = null,
                createdAt = System.currentTimeMillis(),
            ))
        }
    }

    private fun investmentIds(): List<Long> = listOf(10L)   // Investments subtree (no children in seed; revisit at 1E import mapping)
}
```

- [ ] **Step 4: Wire the container in GardenApp.kt**

```kotlin
class AppContainer(app: Application) {
    val db: AppDatabase = AppDatabase.build(app)
    val ledger: LedgerRepository = LedgerRepository(db)
    val quips: QuipRepository = QuipRepository(db)
    val garden: GardenRepository = GardenRepository(db, ledger)
}
```

(Import `com.expensegarden.app.data.GardenRepository`.)

- [ ] **Step 5: Run to verify pass** — the Step 2 command. Expected: 4 tests passed. Then full suites: `./gradlew testDebugUnitTest && ./gradlew connectedDebugAndroidTest` — green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/GardenRepository.kt app/src/main/java/com/expensegarden/app/GardenApp.kt app/src/androidTest/java/com/expensegarden/app/data/GardenRepositoryTest.kt
git commit -m "feat: garden repository - live fold flow, month folds, on-open reconciler"
```

---

### Task 8: IsoMath (TDD)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/render/IsoMath.kt`
- Create: `app/src/test/java/com/expensegarden/app/render/IsoMathTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.render

import com.expensegarden.app.game.Tile
import org.junit.Assert.assertEquals
import org.junit.Test

class IsoMathTest {
    // tileW 80, tileH 40, origin (300, 100): classic 2:1 diamonds
    private val iso = IsoMath(tileW = 80f, tileH = 40f, originX = 300f, originY = 100f)

    @Test fun `projects rows toward the horizon and cols to the right`() {
        assertEquals(300f to 100f, iso.tileCenterX(Tile(0, 0)) to iso.tileCenterY(Tile(0, 0)))
        assertEquals(340f to 120f, iso.tileCenterX(Tile(0, 1)) to iso.tileCenterY(Tile(0, 1)))
        // higher row = further back = up-left on screen
        assertEquals(260f to 120f, iso.tileCenterX(Tile(1, 0)) to iso.tileCenterY(Tile(1, 0)))
    }

    @Test fun `inverse recovers the tile from a screen point`() {
        val t = Tile(2, 3)
        assertEquals(t, iso.tileAt(iso.tileCenterX(t), iso.tileCenterY(t)))
        assertEquals(t, iso.tileAt(iso.tileCenterX(t) + 10f, iso.tileCenterY(t) - 5f))  // within the diamond
    }

    @Test fun `draw order sorts back rows first`() {
        val order = listOf(Tile(0, 0), Tile(2, 1), Tile(1, 4)).sortedByDescending { iso.depth(it) }
        assertEquals(Tile(2, 1), order[0])   // depth = row is the primary key; front row draws last
    }

    @Test fun `fit scales the grid into a viewport`() {
        val fitted = IsoMath.fit(gridRows = 4, gridCols = 5, viewportW = 1080f, viewportH = 1400f, topReserve = 300f, bottomReserve = 300f)
        // whole 4x5 field must land inside the viewport horizontally
        val leftMost = fitted.tileCenterX(Tile(3, 0)) - fitted.tileW / 2
        val rightMost = fitted.tileCenterX(Tile(0, 4)) + fitted.tileW / 2
        assert(leftMost >= 0f && rightMost <= 1080f)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.render.IsoMathTest"`
Expected: FAILED — `unresolved reference: IsoMath`

- [ ] **Step 3: Implement**

```kotlin
package com.expensegarden.app.render

import com.expensegarden.app.game.Tile
import kotlin.math.floor

/** 2:1 isometric projection. Row 0/col 0 tile center sits at (originX, originY);
 *  rows recede up-left (toward the horizon), cols run down-right. Pure floats — JVM-testable. */
class IsoMath(val tileW: Float, val tileH: Float, val originX: Float, val originY: Float) {
    fun tileCenterX(tile: Tile): Float = originX + (tile.col - tile.row) * tileW / 2f
    fun tileCenterY(tile: Tile): Float = originY + (tile.col + tile.row) * tileH / 2f

    /** Higher = further back = drawn earlier. Row recedes, so depth is row-major then col. */
    fun depth(tile: Tile): Int = tile.row * 1000 + tile.col

    fun tileAt(x: Float, y: Float): Tile {
        val dx = (x - originX) / (tileW / 2f)
        val dy = (y - originY) / (tileH / 2f)
        val col = floor((dx + dy) / 2f + .5f).toInt()
        val row = floor((dy - dx) / 2f + .5f).toInt()
        return Tile(row, col)
    }

    companion object {
        /** Scale + center a rows×cols field into the viewport between the reserved bands. */
        fun fit(gridRows: Int, gridCols: Int, viewportW: Float, viewportH: Float, topReserve: Float, bottomReserve: Float): IsoMath {
            val unitsW = (gridCols + gridRows) / 2f          // field width in tileW units
            val unitsH = (gridCols + gridRows) / 2f          // field height in tileH units
            val availH = viewportH - topReserve - bottomReserve
            val tileW = minOf(viewportW * .92f / unitsW, availH * 2f * .92f / unitsH / 2f * 2f).coerceAtLeast(24f)
            val tileH = tileW / 2f
            // Field's horizontal extent: leftmost = origin - gridRows*tileW/2 + tileW/2 … center it.
            val fieldLeftUnits = (gridRows - 1) * .5f
            val fieldRightUnits = (gridCols - 1) * .5f
            val originX = viewportW / 2f + (fieldLeftUnits - fieldRightUnits) * tileW / 2f
            val originY = topReserve + tileH
            return IsoMath(tileW, tileH, originX, originY)
        }
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: 4 tests passed.
If `fit`'s clamping makes the bounds assertion fail: adjust the `.92f` margins in `fit`, not the test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/IsoMath.kt app/src/test/java/com/expensegarden/app/render/IsoMathTest.kt
git commit -m "feat: iso math - 2:1 projection, inverse hit-test, depth order, viewport fit"
```

---

### Task 9: GardenPalette + PlantPainter seam + ProceduralPainter

No unit tests here (draw code); the check is compilation now and eyes at Task 11. Keep each archetype function short — silhouettes and gradients, not botany.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/render/GardenPalette.kt`
- Create: `app/src/main/java/com/expensegarden/app/render/PlantPainter.kt`

- [ ] **Step 1: GardenPalette.kt**

```kotlin
package com.expensegarden.app.render

import androidx.compose.ui.graphics.Color
import com.expensegarden.app.game.Weather

/** The whole scene's color vocabulary — matches the approved companion sample. */
object GardenPalette {
    fun sky(weather: Weather): List<Color> = when (weather) {
        Weather.SUNNY -> listOf(Color(0xFF8FD3FF), Color(0xFFCFEFFD), Color(0xFFEEF9E0))
        Weather.OVERCAST -> listOf(Color(0xFF9FB2C4), Color(0xFFC3CFD4), Color(0xFFDFE6DC))
        Weather.DROUGHT -> listOf(Color(0xFFD9B98A), Color(0xFFE8D3A8), Color(0xFFEFE3C2))
    }
    fun grassA(weather: Weather) = if (weather == Weather.DROUGHT) Color(0xFFC2BB6E) else Color(0xFFA7DD7F)
    fun grassB(weather: Weather) = if (weather == Weather.DROUGHT) Color(0xFFB0A85F) else Color(0xFF8CC968)
    val wallLeft = Color(0xFF7C5233)
    val wallRight = Color(0xFF63401F)
    val shadow = Color(0x28000000)
    val sun = Color(0xFFFFD54D)
    val sunHalo = Color(0x66FFE37E)
    val cloud = Color(0xF2FFFFFF)
    val trunk = Color(0xFF7A5230)
    val canopyLight = Color(0xFF93D47E)
    val canopyDark = Color(0xFF5DA24B)
    val stem = Color(0xFF5DA23C)
    val leaf = Color(0xFF6FB54A)
    val petalYellow = Color(0xFFFFCF3F)
    val petalCenterLight = Color(0xFFFFE066)
    val petalCenterDark = Color(0xFFF6A723)
    val tulipLight = Color(0xFFFF9BB0)
    val tulipDark = Color(0xFFE0577A)
    val bellViolet = Color(0xFF9A86D8)
    val hedgeLight = Color(0xFF79C268)
    val hedgeDark = Color(0xFF4F9140)
    val weedLight = Color(0xFF9A6FB4)
    val weedDark = Color(0xFF5F3C7A)
    val mushroomCap = Color(0xFFC96F8E)
    val mushroomStem = Color(0xFFEFE0C8)
    val sparkle = Color(0xCCFFFFFF)
    val butterflyA = Color(0xFF7DB8F2)
    val butterflyB = Color(0xFF9CCBF7)
}
```

- [ ] **Step 2: PlantPainter.kt — the seam + the procedural implementation**

```kotlin
package com.expensegarden.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.Plant
import com.expensegarden.app.game.SizeTier

/** The seam: mechanics/motion never know how flora is drawn.
 *  anchor = bottom-center of the plant on its tile; heightPx already includes tier scaling;
 *  swayDegrees is applied around the anchor so wind reads the same for sprites and vectors. */
interface PlantPainter {
    fun DrawScope.drawPlant(plant: Plant, anchor: Offset, heightPx: Float, swayDegrees: Float)
}

class ProceduralPainter : PlantPainter {
    override fun DrawScope.drawPlant(plant: Plant, anchor: Offset, heightPx: Float, swayDegrees: Float) {
        rotate(degrees = swayDegrees, pivot = anchor) {
            val h = heightPx
            val jitter = 0.92f + (plant.seed.mod(9)) * 0.02f       // ±8% scale, uuid-stable
            when (plant.archetype) {
                Archetype.PETAL_FLOWER -> petalFlower(anchor, h * jitter)
                Archetype.TULIP -> tulip(anchor, h * jitter)
                Archetype.BELL_FLOWER -> bellFlower(anchor, h * jitter)
                Archetype.HERB_TUFT -> herbTuft(anchor, h * jitter)
                Archetype.BUSH -> bush(anchor, h * jitter)
                Archetype.HEDGE -> hedge(anchor, h * jitter)
                Archetype.PERENNIAL_SHRUB -> shrub(anchor, h * jitter)
                Archetype.TREE -> tree(anchor, h * jitter)
                Archetype.THISTLE_WEED -> thistle(anchor, h * jitter)
                Archetype.ODD_MUSHROOM -> mushroom(anchor, h * jitter)
            }
        }
    }

    private fun DrawScope.stem(a: Offset, h: Float, w: Float = h * .07f) =
        drawLine(GardenPalette.stem, a, Offset(a.x, a.y - h * .55f), strokeWidth = w, cap = StrokeCap.Round)

    private fun DrawScope.leafPair(a: Offset, h: Float) {
        drawOval(GardenPalette.leaf, topLeft = Offset(a.x - h * .28f, a.y - h * .38f), size = androidx.compose.ui.geometry.Size(h * .24f, h * .12f))
        drawOval(GardenPalette.leaf, topLeft = Offset(a.x + h * .04f, a.y - h * .46f), size = androidx.compose.ui.geometry.Size(h * .24f, h * .12f))
    }

    private fun DrawScope.petalFlower(a: Offset, h: Float) {
        stem(a, h); leafPair(a, h)
        val c = Offset(a.x, a.y - h * .68f)
        repeat(6) { i ->
            rotate(degrees = i * 60f, pivot = c) {
                drawOval(GardenPalette.petalYellow, topLeft = Offset(c.x - h * .07f, c.y - h * .30f), size = androidx.compose.ui.geometry.Size(h * .14f, h * .26f))
            }
        }
        drawCircle(Brush.radialGradient(listOf(GardenPalette.petalCenterLight, GardenPalette.petalCenterDark), center = c, radius = h * .12f), radius = h * .12f, center = c)
    }

    private fun DrawScope.tulip(a: Offset, h: Float) {
        stem(a, h); leafPair(a, h)
        val c = Offset(a.x, a.y - h * .66f)
        val cup = Path().apply {
            moveTo(c.x - h * .16f, c.y + h * .10f)
            quadraticBezierTo(c.x - h * .18f, c.y - h * .22f, c.x, c.y - h * .20f)
            quadraticBezierTo(c.x + h * .18f, c.y - h * .22f, c.x + h * .16f, c.y + h * .10f)
            quadraticBezierTo(c.x, c.y + h * .20f, c.x - h * .16f, c.y + h * .10f)
        }
        drawPath(cup, Brush.verticalGradient(listOf(GardenPalette.tulipLight, GardenPalette.tulipDark), startY = c.y - h * .22f, endY = c.y + h * .2f))
    }

    private fun DrawScope.bellFlower(a: Offset, h: Float) {
        stem(a, h); leafPair(a, h)
        listOf(-.10f, .10f).forEachIndexed { i, dx ->
            val c = Offset(a.x + h * dx, a.y - h * (.62f - i * .08f))
            val bell = Path().apply {
                moveTo(c.x - h * .09f, c.y - h * .08f)
                quadraticBezierTo(c.x, c.y - h * .18f, c.x + h * .09f, c.y - h * .08f)
                lineTo(c.x + h * .11f, c.y + h * .08f); lineTo(c.x - h * .11f, c.y + h * .08f); close()
            }
            drawPath(bell, GardenPalette.bellViolet)
        }
    }

    private fun DrawScope.herbTuft(a: Offset, h: Float) {
        listOf(-24f, -10f, 0f, 12f, 26f).forEach { deg ->
            rotate(degrees = deg, pivot = a) {
                drawLine(GardenPalette.leaf, a, Offset(a.x, a.y - h * .6f), strokeWidth = h * .06f, cap = StrokeCap.Round)
            }
        }
    }

    private fun DrawScope.bush(a: Offset, h: Float) {
        val g = Brush.verticalGradient(listOf(GardenPalette.canopyLight, GardenPalette.canopyDark), startY = a.y - h * .7f, endY = a.y)
        drawCircle(g, radius = h * .30f, center = Offset(a.x, a.y - h * .30f))
        drawCircle(g, radius = h * .22f, center = Offset(a.x - h * .24f, a.y - h * .20f))
        drawCircle(g, radius = h * .22f, center = Offset(a.x + h * .24f, a.y - h * .20f))
        drawCircle(Color.White, radius = h * .035f, center = Offset(a.x - h * .1f, a.y - h * .42f))
        drawCircle(Color.White, radius = h * .03f, center = Offset(a.x + h * .14f, a.y - h * .34f))
    }

    private fun DrawScope.hedge(a: Offset, h: Float) {
        val g = Brush.verticalGradient(listOf(GardenPalette.hedgeLight, GardenPalette.hedgeDark), startY = a.y - h * .5f, endY = a.y)
        drawRoundRect(g, topLeft = Offset(a.x - h * .42f, a.y - h * .48f), size = androidx.compose.ui.geometry.Size(h * .84f, h * .48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * .16f))
        drawRoundRect(Color(0x22FFFFFF), topLeft = Offset(a.x - h * .36f, a.y - h * .46f), size = androidx.compose.ui.geometry.Size(h * .72f, h * .10f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * .08f))
    }

    private fun DrawScope.shrub(a: Offset, h: Float) {
        drawLine(GardenPalette.trunk, a, Offset(a.x, a.y - h * .28f), strokeWidth = h * .09f, cap = StrokeCap.Round)
        val g = Brush.verticalGradient(listOf(GardenPalette.canopyLight, GardenPalette.canopyDark), startY = a.y - h * .78f, endY = a.y - h * .2f)
        drawCircle(g, radius = h * .28f, center = Offset(a.x, a.y - h * .48f))
        drawCircle(GardenPalette.tulipLight, radius = h * .04f, center = Offset(a.x - h * .1f, a.y - h * .55f))
        drawCircle(GardenPalette.tulipLight, radius = h * .035f, center = Offset(a.x + h * .12f, a.y - h * .44f))
    }

    private fun DrawScope.tree(a: Offset, h: Float) {
        drawLine(GardenPalette.trunk, a, Offset(a.x, a.y - h * .42f), strokeWidth = h * .11f, cap = StrokeCap.Round)
        val g = Brush.verticalGradient(listOf(GardenPalette.canopyLight, GardenPalette.canopyDark), startY = a.y - h, endY = a.y - h * .3f)
        drawCircle(g, radius = h * .32f, center = Offset(a.x, a.y - h * .68f))
        drawCircle(g, radius = h * .2f, center = Offset(a.x - h * .26f, a.y - h * .56f))
        drawCircle(g, radius = h * .2f, center = Offset(a.x + h * .26f, a.y - h * .56f))
    }

    private fun DrawScope.thistle(a: Offset, h: Float) {
        listOf(-18f, 0f, 16f).forEach { deg ->
            rotate(degrees = deg, pivot = a) {
                drawLine(
                    Brush.verticalGradient(listOf(GardenPalette.weedLight, GardenPalette.weedDark), startY = a.y - h * .6f, endY = a.y),
                    a, Offset(a.x, a.y - h * .58f), strokeWidth = h * .06f, cap = StrokeCap.Round,
                )
            }
        }
        drawCircle(GardenPalette.weedLight, radius = h * .09f, center = Offset(a.x - h * .09f, a.y - h * .58f))
        drawCircle(GardenPalette.weedDark, radius = h * .07f, center = Offset(a.x + h * .10f, a.y - h * .52f))
    }

    private fun DrawScope.mushroom(a: Offset, h: Float) {
        drawRoundRect(GardenPalette.mushroomStem, topLeft = Offset(a.x - h * .07f, a.y - h * .34f), size = androidx.compose.ui.geometry.Size(h * .14f, h * .34f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * .06f))
        val cap = Path().apply {
            moveTo(a.x - h * .26f, a.y - h * .30f)
            quadraticBezierTo(a.x, a.y - h * .66f, a.x + h * .26f, a.y - h * .30f)
            quadraticBezierTo(a.x, a.y - h * .18f, a.x - h * .26f, a.y - h * .30f)
        }
        drawPath(cap, GardenPalette.mushroomCap)
        drawCircle(Color(0xFFF6DFE7), radius = h * .045f, center = Offset(a.x - h * .09f, a.y - h * .40f))
        drawCircle(Color(0xFFF6DFE7), radius = h * .035f, center = Offset(a.x + h * .08f, a.y - h * .36f))
    }
}

/** Height in px for a tier, relative to tile height. */
fun tierHeight(tileH: Float, tier: SizeTier): Float = when (tier) {
    SizeTier.S -> tileH * 1.4f
    SizeTier.M -> tileH * 2.0f
    SizeTier.L -> tileH * 2.7f
}
```

- [ ] **Step 3: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -2`
Expected: BUILD SUCCESSFUL. (If `quadraticBezierTo` is flagged deprecated in this BOM, it still compiles — do NOT chase the replacement.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/GardenPalette.kt app/src/main/java/com/expensegarden/app/render/PlantPainter.kt
git commit -m "feat: garden palette and plant painter seam with procedural archetypes"
```

---

### Task 10: GardenCanvas — the living scene

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.expensegarden.app.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.Plant
import com.expensegarden.app.game.SizeTier
import com.expensegarden.app.game.Tile
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun GardenCanvas(
    state: GardenState,
    painter: PlantPainter,
    modifier: Modifier = Modifier,
    onPlantTap: (String) -> Unit = {},
    topReservePx: Float = 300f,
    bottomReservePx: Float = 320f,
    animated: Boolean = true,
) {
    // One clock for everything ambient. phase ∈ [0,1) looping ~8s.
    val transition = rememberInfiniteTransition(label = "garden")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val livePhase = if (animated) phase else 0.25f

    // Pop-in: new uuids since last state spring from 0→1 with overshoot; first composition skips the show.
    val pop = remember { mutableStateMapOf<String, Animatable<Float, *>>() }
    LaunchedEffect(state.plants.map { it.txnUuid }) {
        val known = pop.keys.toSet()
        val current = state.plants.map { it.txnUuid }.toSet()
        val firstRun = known.isEmpty() && current.isNotEmpty()
        current.minus(known).forEach { uuid ->
            val anim = Animatable(if (firstRun || !animated) 1f else 0f)
            pop[uuid] = anim
            if (!firstRun && animated) anim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        }
        pop.keys.retainAll(current)
    }

    val isoState = remember(state.gridRows, state.gridCols) { mutableListOf<IsoMath>() }

    Canvas(
        modifier.pointerInput(state) {
            detectTapGestures { p ->
                val iso = isoState.lastOrNull() ?: return@detectTapGestures
                val tile = iso.tileAt(p.x, p.y)
                state.plants.firstOrNull { it.tile == tile }?.let { onPlantTap(it.txnUuid) }
            }
        },
    ) {
        val iso = IsoMath.fit(state.gridRows, state.gridCols, size.width, size.height, topReservePx, bottomReservePx)
        isoState.clear(); isoState.add(iso)

        // ---- sky ----
        drawRect(Brush.verticalGradient(GardenPalette.sky(state.weather)))
        // sun (dimmer under clouds/dust)
        val sunAlpha = when (state.weather) { com.expensegarden.app.game.Weather.SUNNY -> 1f; else -> .45f }
        val sunC = Offset(size.width * .85f, topReservePx * .38f)
        drawCircle(GardenPalette.sunHalo.copy(alpha = GardenPalette.sunHalo.alpha * sunAlpha * (0.8f + .2f * sin(livePhase * 2f * PI.toFloat()))), radius = 64f, center = sunC)
        drawCircle(GardenPalette.sun.copy(alpha = sunAlpha), radius = 34f, center = sunC)
        // clouds — 2 normally, 3 when overcast; wrap horizontally on the shared clock
        val cloudCount = if (state.weather == com.expensegarden.app.game.Weather.OVERCAST) 3 else 2
        repeat(cloudCount) { i ->
            val speed = .5f + i * .3f
            val cx = ((livePhase * speed + i * .37f) % 1.2f) * size.width * 1.2f - size.width * .1f
            val cy = topReservePx * (.25f + i * .16f)
            cloud(Offset(cx, cy), 1f - i * .18f)
        }
        // sparkles for no-spend days (max 4), twinkling on the clock
        repeat(minOf(state.noSpendDays, 4)) { i ->
            val sx = size.width * (.15f + i * .22f)
            val sy = topReservePx * (.55f + (i % 2) * .2f)
            val a = (sin((livePhase + i * .25f) * 2f * PI.toFloat()) * .5f + .5f)
            sparkle(Offset(sx, sy), 9f, a)
        }

        // ---- back-row trees on the horizon ----
        repeat(state.backRowTreeCount) { i ->
            val tx = size.width * (.30f + i * .20f)
            val base = Offset(tx, iso.tileCenterY(Tile(state.gridRows - 1, 0)) - iso.tileH * 1.2f)
            drawOval(GardenPalette.shadow, topLeft = Offset(base.x - 26f, base.y - 8f), size = Size(52f, 16f))
            val treeH = iso.tileH * (2.6f + minOf(state.trunkTier, 15) * .06f)
            val sway = sin((livePhase * 2f + i * .8f) * PI.toFloat()) * 1.2f
            val plant = Plant("backrow-$i", Archetype.TREE, SizeTier.L, false, Tile(state.gridRows, 0), i * 31)
            with(painter) { drawPlant(plant, base, treeH, sway) }
        }

        // ---- tile field + front walls ----
        for (r in state.gridRows - 1 downTo 0) {
            for (c in 0 until state.gridCols) {
                val cx = iso.tileCenterX(Tile(r, c)); val cy = iso.tileCenterY(Tile(r, c))
                val fill = if ((r + c) % 2 == 0) GardenPalette.grassA(state.weather) else GardenPalette.grassB(state.weather)
                diamond(cx, cy, iso.tileW, iso.tileH, fill)
            }
        }
        val wallH = iso.tileH * .5f
        for (c in 0 until state.gridCols) {                          // front row (row 0) left-facing walls
            val cx = iso.tileCenterX(Tile(0, c)); val cy = iso.tileCenterY(Tile(0, c))
            wall(Offset(cx - iso.tileW / 2, cy), Offset(cx, cy + iso.tileH / 2), wallH, GardenPalette.wallLeft)
        }
        for (r in 0 until state.gridRows) {                          // right column right-facing walls
            val cx = iso.tileCenterX(Tile(r, state.gridCols - 1)); val cy = iso.tileCenterY(Tile(r, state.gridCols - 1))
            wall(Offset(cx, cy + iso.tileH / 2), Offset(cx + iso.tileW / 2, cy), wallH, GardenPalette.wallRight)
        }

        // ---- plants, back to front ----
        state.plants.sortedByDescending { iso.depth(it.tile) }.forEach { plant ->
            val ax = iso.tileCenterX(plant.tile); val ay = iso.tileCenterY(plant.tile) + iso.tileH * .18f
            val anchor = Offset(ax, ay)
            val popScale = pop[plant.txnUuid]?.value ?: 1f
            if (popScale < 1f) {                                     // soil poof while springing in
                drawCircle(GardenPalette.wallLeft.copy(alpha = (1f - popScale) * .5f), radius = iso.tileW * .3f * (0.4f + popScale), center = anchor)
            }
            drawOval(GardenPalette.shadow, topLeft = Offset(ax - iso.tileW * .2f, ay - iso.tileH * .12f), size = Size(iso.tileW * .4f, iso.tileH * .24f))
            val swaySpeed = if (plant.isWeed) 3f else 2f             // weeds fidget
            val sway = sin((livePhase * swaySpeed + (plant.seed.mod(100)) / 100f) * 2f * PI.toFloat()) * 2.4f
            with(painter) { drawPlant(plant, anchor, tierHeight(iso.tileH, plant.sizeTier) * popScale, sway) }
        }

        // ---- butterflies (dodge rewards) on lissajous loops ----
        repeat(state.butterflies) { i ->
            val t = (livePhase + i * .19f) % 1f
            val bx = size.width * .5f + size.width * .32f * sin(2f * PI.toFloat() * t + i)
            val by = topReservePx + (size.height - topReservePx - bottomReservePx) * .3f +
                60f * sin(4f * PI.toFloat() * t + i * 2f)
            butterfly(Offset(bx, by), flap = sin(livePhase * 40f * PI.toFloat()))
        }
    }
}

private fun DrawScope.diamond(cx: Float, cy: Float, w: Float, h: Float, color: androidx.compose.ui.graphics.Color) {
    val p = Path().apply {
        moveTo(cx, cy - h / 2); lineTo(cx + w / 2, cy); lineTo(cx, cy + h / 2); lineTo(cx - w / 2, cy); close()
    }
    drawPath(p, color)
    drawPath(p, androidx.compose.ui.graphics.Color(0x14000000), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
}

private fun DrawScope.wall(top1: Offset, top2: Offset, depth: Float, color: androidx.compose.ui.graphics.Color) {
    val p = Path().apply {
        moveTo(top1.x, top1.y); lineTo(top2.x, top2.y)
        lineTo(top2.x, top2.y + depth); lineTo(top1.x, top1.y + depth); close()
    }
    drawPath(p, color)
}

private fun DrawScope.cloud(c: Offset, scale: Float) {
    drawOval(GardenPalette.cloud, topLeft = Offset(c.x - 44f * scale, c.y - 14f * scale), size = Size(88f * scale, 28f * scale))
    drawOval(GardenPalette.cloud, topLeft = Offset(c.x - 14f * scale, c.y - 24f * scale), size = Size(56f * scale, 30f * scale))
}

private fun DrawScope.sparkle(c: Offset, r: Float, alpha: Float) {
    val p = Path().apply {
        moveTo(c.x, c.y - r); lineTo(c.x + r * .3f, c.y - r * .3f); lineTo(c.x + r, c.y)
        lineTo(c.x + r * .3f, c.y + r * .3f); lineTo(c.x, c.y + r); lineTo(c.x - r * .3f, c.y + r * .3f)
        lineTo(c.x - r, c.y); lineTo(c.x - r * .3f, c.y - r * .3f); close()
    }
    drawPath(p, GardenPalette.sparkle.copy(alpha = GardenPalette.sparkle.alpha * alpha))
}

private fun DrawScope.butterfly(c: Offset, flap: Float) {
    val wing = 7f * (0.4f + 0.6f * kotlin.math.abs(flap))
    drawOval(GardenPalette.butterflyA, topLeft = Offset(c.x - wing - 1.5f, c.y - 4.5f), size = Size(wing, 9f))
    drawOval(GardenPalette.butterflyB, topLeft = Offset(c.x + 1.5f, c.y - 4.5f), size = Size(wing, 9f))
    drawRoundRect(androidx.compose.ui.graphics.Color(0xFF3F3B52), topLeft = Offset(c.x - 1.4f, c.y - 5f), size = Size(2.8f, 10f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.4f))
}
```

- [ ] **Step 2: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -2`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt
git commit -m "feat: garden canvas - sky, iso field, plants, clouds, butterflies, pop-in"
```

---

### Task 11: MOTION CHECKPOINT — Rajdweep judges the living placeholder

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/render/SyntheticGarden.kt`
- Modify (TEMPORARILY, uncommitted): `app/src/main/java/com/expensegarden/app/MainActivity.kt`

- [ ] **Step 1: SyntheticGarden.kt (committed — stays useful for art tuning)**

```kotlin
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
```

- [ ] **Step 2: Temporary preview wiring (do NOT commit this part)**

In `MainActivity.kt`, inside the NavHost add a route and flip the start destination:

```kotlin
        composable("gardenPreview") {
            com.expensegarden.app.render.GardenCanvas(
                state = com.expensegarden.app.render.SyntheticGarden.demo(),
                painter = remember { com.expensegarden.app.render.ProceduralPainter() },
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            )
        }
```

and `startDestination = "gardenPreview"`.

- [ ] **Step 3: Install, record, screenshot**

Run: `./gradlew installDebug`, launch, wait 5s, then `adb exec-out screencap -p > <scratchpad>/motion-sunny.png`. Also capture a 6-second screen record (`adb shell screenrecord --time-limit 6 /sdcard/motion.mp4 && adb pull /sdcard/motion.mp4 <scratchpad>/`). Then edit the demo call to `demo(Weather.DROUGHT)`, reinstall, screenshot `motion-drought.png`.
Expected: full field with all archetypes, sway visible in the recording, clouds drifting, two butterflies, sparkles, drought variant shows dusty sky + dry grass.

- [ ] **Step 4: STOP — user checkpoint**

Present the screenshots + recording to Rajdweep. This is the **motion** approval (lively/fluid), not the final art (that's Task 16 with his sprites). Iterate palette/motion constants on his feedback before proceeding. On approval: `git restore app/src/main/java/com/expensegarden/app/MainActivity.kt` (drops the temporary preview wiring).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/SyntheticGarden.kt
git commit -m "feat: synthetic garden state for art and motion tuning"
```

---

### Task 12: Dashboard gains the Recent section

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt`

- [ ] **Step 1: DashboardViewModel additions**

```kotlin
    val recent: Flow<List<TxnRow>> = ledger.observeRecent()

    fun setRegret(uuid: String, value: Regret) = viewModelScope.launch { ledger.setRegret(uuid, value) }
```

(Imports: `com.expensegarden.app.data.Regret`, `com.expensegarden.app.data.TxnRow`, `kotlinx.coroutines.flow.Flow`. `LedgerRepository.observeRecent`/`setRegret` already exist.)

- [ ] **Step 2: DashboardScreen — Recent section inside the existing LazyColumn**

Collect `val recent by vm.recent.collectAsState(initial = emptyList())` and `var regretTarget by remember { mutableStateOf<TxnRow?>(null) }` at the top. After the `items(s.rows, ...)` block inside the same LazyColumn, append:

```kotlin
                item { Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                items(recent, key = { "txn-" + it.uuid }) { row ->
                    Row(
                        Modifier.fillMaxWidth().animateItem().clickable { regretTarget = row },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(row.payeeName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${row.categoryName} · ${dateFmt.format(Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.systemDefault()))}" +
                                    when (row.regret) {
                                        Regret.REGRET -> " · regret"
                                        Regret.WORTH_IT -> " · worth it"
                                        Regret.UNRATED -> ""
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(Money.display(row.amountPaise), style = MaterialTheme.typography.bodyLarge)
                    }
                }
```

With `val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM") }` near the top, and the regret dialog appended after the budget-target dialog (same markup as the 1B home version):

```kotlin
    regretTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { regretTarget = null },
            title = { Text("${Money.display(row.amountPaise)} — ${row.payeeName}") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = row.regret == Regret.WORTH_IT,
                        onClick = { vm.setRegret(row.uuid, Regret.WORTH_IT); regretTarget = null },
                        label = { Text("Worth it") })
                    FilterChip(selected = row.regret == Regret.REGRET,
                        onClick = { vm.setRegret(row.uuid, Regret.REGRET); regretTarget = null },
                        label = { Text("Regret") })
                }
            },
            confirmButton = { TextButton(onClick = { regretTarget = null }) { Text("Close") } },
        )
    }
```

(New imports: `androidx.compose.material3.FilterChip`, `com.expensegarden.app.data.Regret`, `com.expensegarden.app.data.TxnRow`, `java.time.Instant`, `java.time.ZoneId`, `java.time.format.DateTimeFormatter`.)

- [ ] **Step 3: Build + smoke**

`./gradlew installDebug`; open dashboard → Recent rows appear below budgets; tap a row → regret dialog works.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt
git commit -m "feat: dashboard - recent transactions with regret tagging move in"
```

---

### Task 13: Home takeover — GardenViewModel + GardenHomeScreen

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/GardenViewModel.kt`
- Create: `app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt`
- Delete: `app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/expensegarden/app/MainActivity.kt`

- [ ] **Step 1: GardenViewModel.kt**

```kotlin
package com.expensegarden.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.expensegarden.app.AppContainer
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.game.GardenState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GardenViewModel(private val container: AppContainer) : ViewModel() {
    /** null = loading skeleton. flow{} wrapper re-derives the month on re-subscription (house idiom). */
    val garden: StateFlow<GardenState?> =
        flow { emitAll(container.garden.observeCurrentGarden()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch { container.garden.runReconciler() }   // month.closed / streak.hit on open
    }

    suspend fun plantRow(uuid: String): TxnRow? = container.db.transactionDao().rowByUuid(uuid)

    suspend fun archivedGardens(): List<GardenState> =
        container.garden.monthsWithData().dropLast(1).map { container.garden.foldMonth(it) }.reversed()

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GardenViewModel(container) as T
        }
    }
}
```

- [ ] **Step 2: GardenHomeScreen.kt**

```kotlin
package com.expensegarden.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.gate.Severity
import com.expensegarden.app.render.GardenCanvas
import com.expensegarden.app.render.PlantPainter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun GardenHomeScreen(
    gardenVm: GardenViewModel,
    vm: MainViewModel,
    painter: PlantPainter,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenGreenhouse: () -> Unit,
) {
    val garden by gardenVm.garden.collectAsState()
    val header by vm.homeHeader.collectAsState()
    val pending by vm.pendingConfirm.collectAsState()
    val scope = rememberCoroutineScope()
    var plantTarget by remember { mutableStateOf<TxnRow?>(null) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM") }

    Box(Modifier.fillMaxSize()) {
        garden?.let { g ->
            GardenCanvas(
                state = g,
                painter = painter,
                modifier = Modifier.fillMaxSize(),
                onPlantTap = { uuid -> scope.launch { plantTarget = gardenVm.plantRow(uuid) } },
            )
        }

        // Translucent stats strip — the same homeHeader the 1B home used.
        Surface(
            color = Color.White.copy(alpha = .82f),
            modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth().align(Alignment.TopCenter).clickable(onClick = onOpenDashboard),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                val h = header
                if (h == null) Text(" ", style = MaterialTheme.typography.titleMedium)
                else {
                    Text(Money.display(h.spentPaise), style = MaterialTheme.typography.titleMedium)
                    val streak = garden?.streakDays ?: 0
                    val streakSuffix = if (streak > 0) " · 🌱${streak}d" else ""   // the streaks-lite counter (spec §1)
                    Text(
                        (h.overallBudgetPaise?.let { "${Money.display(it)} · ${gardenHint(h.hint)}" } ?: "dashboard →") + streakSuffix,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        TextButton(
            onClick = onOpenGreenhouse,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 64.dp),
        ) { Text("🏡 greenhouse") }

        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val pendingTxn = pending.firstOrNull()
            var lastPending by remember { mutableStateOf<TransactionEntity?>(null) }
            LaunchedEffect(pendingTxn) { if (pendingTxn != null) lastPending = pendingTxn }
            AnimatedVisibility(
                visible = pendingTxn != null,
                enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) + fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
            ) {
                (pendingTxn ?: lastPending)?.let { txn ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Did ${Money.display(txn.amountPaise)} go through?", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { vm.confirmPending(txn.uuid) }) { Text("Log it") }
                                OutlinedButton(onClick = { vm.discardPending(txn.uuid) }) { Text("Discard") }
                            }
                        }
                    }
                }
            }
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(onClick = onScan) { Text("Scan & pay") }
                ExtendedFloatingActionButton(onClick = onManual) { Text("Log manually") }
            }
        }
    }

    plantTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { plantTarget = null },
            title = { Text("${Money.display(row.amountPaise)} — ${row.payeeName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${row.categoryName} · ${dateFmt.format(Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.systemDefault()))}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = row.regret == Regret.WORTH_IT,
                            onClick = { vm.setRegret(row.uuid, Regret.WORTH_IT); plantTarget = null },
                            label = { Text("Worth it") })
                        FilterChip(selected = row.regret == Regret.REGRET,
                            onClick = { vm.setRegret(row.uuid, Regret.REGRET); plantTarget = null },
                            label = { Text("Regret") })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { plantTarget = null }) { Text("Close") } },
        )
    }
}

private fun gardenHint(s: Severity) = when (s) {
    Severity.OK -> "on pace"
    Severity.PACE_WARNING -> "ahead of pace"
    Severity.BREACH -> "over budget"
}
```

- [ ] **Step 3: MainActivity wiring**

Add the ViewModel next to the others, delete `HomeScreen.kt`, and replace the home route:

```kotlin
    private val gardenVm: GardenViewModel by viewModels {
        GardenViewModel.factory((application as GardenApp).container)
    }
```

`GardenNav(vm, dashVm, gardenVm)`; inside:

```kotlin
        composable("home") {
            GardenHomeScreen(
                gardenVm = gardenVm,
                vm = vm,
                painter = remember { com.expensegarden.app.render.ProceduralPainter() },
                onScan = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan a UPI QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true)
                    )
                },
                onManual = { vm.startManualDraft(); nav.navigate("entry") },
                onOpenDashboard = { nav.navigate("dashboard") },
                onOpenGreenhouse = { nav.navigate("greenhouse") },
            )
        }
        composable("greenhouse") { GreenhouseScreen(gardenVm = gardenVm, painter = remember { com.expensegarden.app.render.ProceduralPainter() }) }
```

(GreenhouseScreen arrives in Task 14 — to keep this task compiling, create it there; in THIS task point the route at a placeholder `Text("greenhouse")` composable and note it, OR do Steps 3 of both tasks together. Preferred: keep this task's route commented out and the greenhouse button hidden until Task 14; simplest compilable choice: `composable("greenhouse") { Text("🏡 soon") }`.)

- [ ] **Step 4: Build + smoke**

`./gradlew installDebug`; cold-start: garden renders (fresh device data = empty field, sunny), stats strip shows totals, log a manual entry → return home → the new plant **pops in with a poof**; tap it → detail dialog with Worth it/Regret; screenshot for the record.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/GardenViewModel.kt app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt app/src/main/java/com/expensegarden/app/MainActivity.kt
git rm app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt
git commit -m "feat: garden takes home - live fold canvas, stats strip, plant dialog"
```

---

### Task 14: GreenhouseScreen — the album

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/GreenhouseScreen.kt`
- Modify: `app/src/main/java/com/expensegarden/app/MainActivity.kt` (replace the placeholder route)

- [ ] **Step 1: Implement**

```kotlin
package com.expensegarden.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.render.GardenCanvas
import com.expensegarden.app.render.PlantPainter
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GreenhouseScreen(gardenVm: GardenViewModel, painter: PlantPainter) {
    var months by remember { mutableStateOf<List<GardenState>?>(null) }
    var selected by remember { mutableStateOf<GardenState?>(null) }
    LaunchedEffect(Unit) { months = gardenVm.archivedGardens() }
    val monthFmt = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH) }

    Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Greenhouse", style = MaterialTheme.typography.headlineSmall)
        when {
            months == null -> Card(Modifier.fillMaxWidth().height(120.dp)) {}
            months!!.isEmpty() -> Text("No archived months yet — your first bed archives at month end.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(months!!, key = { it.monthKey }) { g ->
                    Card(Modifier.fillMaxWidth().clickable { selected = g }) {
                        Column {
                            GardenCanvas(
                                state = g, painter = painter, animated = false,
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                topReservePx = 60f, bottomReservePx = 30f,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(monthFmt.format(YearMonth.parse(g.monthKey).atDay(1)), style = MaterialTheme.typography.titleMedium)
                                Text(Money.display(g.spentPaise), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { g ->
        Box(Modifier.fillMaxSize()) {
            GardenCanvas(state = g, painter = painter, animated = false, modifier = Modifier.fillMaxSize())
            TextButton(
                onClick = { selected = null },
                modifier = Modifier.statusBarsPadding().padding(12.dp),
            ) { Text("← back") }
        }
    }
}
```

Replace the MainActivity placeholder route with `composable("greenhouse") { GreenhouseScreen(gardenVm = gardenVm, painter = remember { com.expensegarden.app.render.ProceduralPainter() }) }` and un-hide the greenhouse button if it was hidden in Task 13.

- [ ] **Step 2: Build + smoke**

`./gradlew installDebug`; backdate a manual entry into LAST month via the date picker (arrow to the previous month in the calendar), then: greenhouse shows one archived card (frozen mini garden + month + spent), tap → full frozen view, back works. Also verify the reconciler closed it: `run-as … sqlite3 … "SELECT payloadJson FROM game_event WHERE type='month.closed'"` after reopening the app.
Expected: card renders; `month.closed` row exists for last month.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/GreenhouseScreen.kt app/src/main/java/com/expensegarden/app/MainActivity.kt
git commit -m "feat: greenhouse album - archived month beds from the same fold"
```

---

### Task 15: SpritePainter + asset loader (the pack wires in)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/render/SpritePainter.kt`
- Create: `app/src/test/java/com/expensegarden/app/render/SpriteNamesTest.kt`
- Modify: `app/src/main/java/com/expensegarden/app/GardenApp.kt`
- Modify: `app/src/main/java/com/expensegarden/app/MainActivity.kt` (painter selection)

**FIRST: check `app/src/main/assets/garden/` for Rajdweep's PNGs.** If absent → REMIND HIM (second reminder, he asked): *"Sprites not in yet — the brief is `docs/assets/garden-sprite-brief.md`, drop PNGs into `app/src/main/assets/garden/`. Building the loader anyway; procedural stays on screen until they land."* Then continue — this task is fully buildable without the files.

- [ ] **Step 1: Failing JVM test for the name mapping**

```kotlin
package com.expensegarden.app.render

import com.expensegarden.app.game.Archetype
import org.junit.Assert.assertEquals
import org.junit.Test

class SpriteNamesTest {
    @Test fun `file names are the lowercase archetype names`() {
        assertEquals("petal_flower.png", SpriteNames.fileFor(Archetype.PETAL_FLOWER))
        assertEquals("odd_mushroom.png", SpriteNames.fileFor(Archetype.ODD_MUSHROOM))
        assertEquals(Archetype.entries.size, Archetype.entries.map { SpriteNames.fileFor(it) }.toSet().size)
    }
}
```

Run: `./gradlew testDebugUnitTest --tests "com.expensegarden.app.render.SpriteNamesTest"` → Expected: FAILED — `unresolved reference: SpriteNames`.

- [ ] **Step 2: Implement SpritePainter.kt**

```kotlin
package com.expensegarden.app.render

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.Plant

object SpriteNames {
    fun fileFor(archetype: Archetype): String = archetype.name.lowercase() + ".png"
}

object SpriteLoader {
    /** Decode whatever is present in assets/garden/. Missing dir or files → empty/partial map. */
    fun load(context: Context): Map<Archetype, ImageBitmap> {
        val present = runCatching { context.assets.list("garden")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        return Archetype.entries.mapNotNull { arch ->
            val name = SpriteNames.fileFor(arch)
            if (name !in present) null
            else runCatching {
                context.assets.open("garden/$name").use { s ->
                    arch to BitmapFactory.decodeStream(s).asImageBitmap()
                }
            }.getOrNull()
        }.toMap()
    }
}

/** Sprites where available, procedural everywhere else — a partial pack still renders a full garden. */
class SpritePainter(
    private val sprites: Map<Archetype, ImageBitmap>,
    private val fallback: PlantPainter = ProceduralPainter(),
) : PlantPainter {
    override fun DrawScope.drawPlant(plant: Plant, anchor: Offset, heightPx: Float, swayDegrees: Float) {
        val bmp = sprites[plant.archetype]
        if (bmp == null) {
            with(fallback) { drawPlant(plant, anchor, heightPx, swayDegrees) }
            return
        }
        val h = heightPx.toInt()
        val w = (heightPx * bmp.width / bmp.height).toInt()
        rotate(degrees = swayDegrees, pivot = anchor) {
            drawImage(
                image = bmp,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset((anchor.x - w / 2f).toInt(), (anchor.y - h).toInt()),
                dstSize = IntSize(w, h),
            )
        }
    }
}
```

- [ ] **Step 3: Wire selection**

`AppContainer` gains `val sprites: Map<Archetype, ImageBitmap> by lazy { SpriteLoader.load(app) }` (imports `com.expensegarden.app.game.Archetype`, `com.expensegarden.app.render.SpriteLoader`, `androidx.compose.ui.graphics.ImageBitmap`; `app` must become a stored `private val app: Application` constructor property). In MainActivity, both painter sites become:

```kotlin
            painter = remember {
                val container = (context.applicationContext as GardenApp).container
                if (container.sprites.isEmpty()) com.expensegarden.app.render.ProceduralPainter()
                else com.expensegarden.app.render.SpritePainter(container.sprites)
            },
```

(`val context = LocalContext.current` is already in `GardenNav` scope.)

- [ ] **Step 4: Tests + build**

`./gradlew testDebugUnitTest --tests "com.expensegarden.app.render.SpriteNamesTest"` → PASS (1 test). `./gradlew installDebug` → app renders (procedural if assets absent, sprites if present).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/SpritePainter.kt app/src/test/java/com/expensegarden/app/render/SpriteNamesTest.kt app/src/main/java/com/expensegarden/app/GardenApp.kt app/src/main/java/com/expensegarden/app/MainActivity.kt
git commit -m "feat: sprite painter - asset pack loader with per-archetype procedural fallback"
```

(If Rajdweep's PNGs are already in the repo by now, `git add app/src/main/assets/garden/` too and note it in the message.)

---

### Task 16: LOOK CHECKPOINT — the sprite garden vs the Fortune City bar

**Requires:** PNGs in `app/src/main/assets/garden/`. If they're still missing: STOP, remind Rajdweep a final time, and proceed to Task 17 — this checkpoint stays open until the pack lands (the app ships procedural meanwhile; that's the designed fallback, not a failure).

- [ ] **Step 1: Reinstall with the pack, screenshot the live garden + a synthetic full field (temporary preview wiring from Task 11, again uncommitted), plus one greenhouse card.**
- [ ] **Step 2: STOP — present to Rajdweep: "Is this at or above the Fortune City bar?"** On yes: commit the assets if not already committed. On no: capture what specifically falls short (silhouette? shading? palette? coherence?), update `docs/assets/garden-sprite-brief.md` with the correction notes, and hand back for regeneration — iterate this checkpoint. Log each round in Execution amendments.

---

### Task 17: Full regression + 1C verification sweep

**Files:**
- Modify: `docs/superpowers/plans/2026-07-07-phase1c-garden.md` (tick checkboxes, amendments)
- Modify: memory `expense-garden-phase1a-status.md`

- [ ] **Step 1: Full suites**

Run: `./gradlew testDebugUnitTest && ./gradlew connectedDebugAndroidTest`
Expected: JVM **74** (46 existing + 28 new: mapper 6, tiler 3, streak 4, folder 7, reconciler 3, iso 4, sprite names 1). Instrumented **28** (20 existing + 8 new: garden-dao 4, garden-repo 4). Zero failures. (Count drift → fix the arithmetic here as an amendment, not the tests.)

- [ ] **Step 2: Emulator E2E sweep** (remember: the connected run just wiped the app — `./gradlew installDebug` first)

1. Cold start → garden home renders (empty sunny field), stats strip live, no ₹0.00 flash.
2. Log ₹50 chai (chips) → back home → plant pops in with poof; tap it → dialog; tag Regret → plant re-folds to a weed on the spot.
3. Set a tiny overall budget (dashboard) → sky turns DROUGHT; clear it → sunny again (weather = live health).
4. Backdate an entry to last month → greenhouse shows the archived bed; reopen app → `month.closed` event exists (sqlite check).
5. Dashboard: Recent section works, budgets/pace intact (1B regression).
6. Butterfly + gate-title live checks stay parked with the 1A Task-12 scan leg (dodge requires the QR gate) — verified synthetically at Task 11; note in amendments.
7. Screen-record 8s of the live garden for the record.

- [ ] **Step 3: Tick all checkboxes, log amendments, update memory** (1C status: shipped/checkpoints state, sprite-pack status, what rides Task-12 resume).

- [ ] **Step 4: Final commit**

```bash
git add docs/superpowers/plans/2026-07-07-phase1c-garden.md
git commit -m "docs: 1c plan executed - checkboxes ticked, amendments logged"
```

---

## Execution amendments

(Log deviations here as they happen.)

## Deferred (recorded)

- Roaster character sprite + voice → 1D. Compost/fertilizer, rare species, collections → Phase 4. Camera pan/zoom, rain particles → polish backlog.
- Live `gate.dodged` butterfly verification + gate-title-on-scan → ride the 1A Task-12 emulator scan leg.
- `investmentIds()` hardcodes the seed's Investments root (10) — revisit when 1E import mapping can add subtree children.
- If fold latency ever matters at real scale (it shouldn't): hybrid snapshot-per-archived-month is the recorded escape hatch (spec §2).

