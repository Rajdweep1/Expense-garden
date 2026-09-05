# 1C.7 Growing Homestead & Expanded Cast — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the house claim more land as it levels up (2×2 → 3×3 → 4×4), animate the re-layout, and split five overloaded category→plant mappings into their own archetypes.

**Architecture:** `SpiralTiler` gains one parameter `f` (house side in tiles) defaulting to `2`, so all 1C.6 behaviour is exactly the f=2 case and the existing test file stays valid unchanged. `GardenFolder.foldAllTime` gains `houseLevelOverride`, letting the screen fold the *same* transactions at the previous house level; `GardenCanvas` lerps between the two resulting states in screen space. A new `GardenPrefs` (SharedPreferences, zero new dependencies) records which level the device has already seen.

**Tech Stack:** Kotlin, Jetpack Compose Canvas, Room, JUnit4 (JVM unit tests), `mflux` + FLUX.1-schnell q4 for sprites.

**Spec:** [`docs/superpowers/specs/2026-08-16-1c7-homestead-growth-design.md`](../specs/2026-08-16-1c7-homestead-growth-design.md)

---

## Guardrails (read before starting)

- **Do NOT** upgrade versions, add dependencies, or fix deprecation warnings. `gradle/libs.versions.toml` is pinned deliberately. `GardenPrefs` uses `android.content.SharedPreferences` — framework, not a library. DataStore would violate this.
- **If a step's output doesn't match its Expected line: STOP and report.** Do not improvise.
- **Commits:** plain messages, no `Co-Authored-By` / Claude / AI attribution lines. **Never `git push`.**
- **Never commit** files under `docs/` — the spec and this plan stay uncommitted working files. Code and assets commit normally.
- Every gradle command needs the JDK prefix shown in each step. Use `:app:testDebugUnitTest`, never the aggregate `test` task.

## File Structure

**Create:**

| File | Responsibility |
|---|---|
| `app/src/main/java/com/expensegarden/app/data/GardenPrefs.kt` | Device-local view state. One Int: `lastSeenHouseLevel`. |
| `app/src/test/java/com/expensegarden/app/game/SpiralTilerFootprintTest.kt` | f=3 / f=4 tiling. Separate file so `SpiralTilerTest.kt` remains an untouched f=2 regression guard. |
| `tools/art/gen.py` | FLUX sprite generator + chroma keying. Rebuilt in the repo (the scratchpad copy is gone). |
| `tools/art/briefs.py` | Executable prompt table for all sprites, transcribed from the committed casting sheet. |
| `tools/art/README.md` | How to run the pipeline. |
| `app/src/main/assets/garden/*.png` | 10 new sprites (5 archetypes × 2 variants). |

**Modify:**

| File | Change |
|---|---|
| `app/src/main/java/com/expensegarden/app/game/SpiralTiler.kt` | Add `footprint(level)`; thread `f` through every function with default 2. |
| `app/src/main/java/com/expensegarden/app/game/GardenModel.kt` | 5 new `Archetype` values. |
| `app/src/main/java/com/expensegarden/app/game/PlantMapper.kt` | `archetypeBySubcat`; remap 3 necessity roots; 5 `variantCounts` entries. |
| `app/src/main/java/com/expensegarden/app/render/PlantPainter.kt` | 5 aliased branches (the `when` is exhaustive). |
| `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt` | `houseLevelOverride`; footprint-aware `tiles`/`gridSide`. |
| `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt` | Footprint-aware house geometry; expansion tween; `house()` gains `alpha`. |
| `app/src/main/java/com/expensegarden/app/ui/GardenViewModel.kt` | Expose `expandFrom` + `markExpansionShown()`. |
| `app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt` | Pass `expandFrom` / `onExpansionShown` through. |
| `app/src/main/java/com/expensegarden/app/GardenApp.kt` | `GardenPrefs` in `AppContainer`. |
| `app/src/test/java/com/expensegarden/app/render/IsoMathTest.kt` | Footprint-independence assertion. |
| `app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt` | `houseLevelOverride` coherence. |
| `app/src/test/java/com/expensegarden/app/game/PlantMapperTest.kt` | Subcat precedence + necessity splits. |
| `docs/assets/sprite-briefs.md` | 10 new briefs. |

**Sequencing note:** Tasks 1–8 are pure code and land the whole mechanic with the *procedural* fallback art. Sprite generation (Task 10) is slow (~95s/sprite) and makes the machine crawl — it is deliberately placed after everything is green so it can run unattended.

---

### Task 1: SpiralTiler footprint parameter

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/SpiralTiler.kt`
- Test: `app/src/test/java/com/expensegarden/app/game/SpiralTilerFootprintTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/game/SpiralTilerFootprintTest.kt`:

```kotlin
package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.7: the house block is f×f. SpiralTilerTest covers f = 2 (the 1C.6 case) and must keep
 *  passing untouched — this file covers the new f = 3 and f = 4 rungs. */
class SpiralTilerFootprintTest {

    @Test fun `footprint ladder is 2 2 3 4`() {
        assertEquals(2, SpiralTiler.footprint(1))
        assertEquals(2, SpiralTiler.footprint(2))
        assertEquals(3, SpiralTiler.footprint(3))
        assertEquals(4, SpiralTiler.footprint(4))
        assertEquals(2, SpiralTiler.footprint(0))    // clamped
        assertEquals(4, SpiralTiler.footprint(9))    // clamped
    }

    @Test fun `capacity counts the full square minus house and grove`() {
        // side = f + 2k, so side² − f² − 4 must equal capacity(k, f).
        for (f in listOf(2, 3, 4)) {
            for (k in 1..4) {
                val side = f + 2 * k
                assertEquals("f=$f k=$k", side * side - f * f - 4, SpiralTiler.capacity(k, f))
            }
        }
    }

    @Test fun `side and footprint always share parity`() {
        for (f in listOf(2, 3, 4)) {
            for (n in listOf(0, 1, 12, 40, 200)) {
                val side = SpiralTiler.gridSide(n, f)
                assertEquals("f=$f n=$n", f % 2, side % 2)
            }
        }
    }

    @Test fun `house block is centered and f by f`() {
        assertEquals(9, SpiralTiler.houseTiles(5, 3).size)
        assertEquals(16, SpiralTiler.houseTiles(6, 4).size)
        assertTrue(SpiralTiler.houseTiles(5, 3).all { it.row in 1..3 && it.col in 1..3 })
        assertTrue(SpiralTiler.houseTiles(6, 4).all { it.row in 1..4 && it.col in 1..4 })
    }

    @Test fun `grove is four tiles on the row behind the house`() {
        // Placement table from spec §1.
        assertEquals((0..3).map { Tile(3, it) }.toSet(), SpiralTiler.backyardTiles(4, 2))
        assertEquals((0..3).map { Tile(4, it) }.toSet(), SpiralTiler.backyardTiles(5, 3))
        assertEquals((1..4).map { Tile(5, it) }.toSet(), SpiralTiler.backyardTiles(6, 4))
    }

    @Test fun `reserved tiles are never planted at any footprint`() {
        for (f in listOf(3, 4)) {
            val n = 40
            val side = SpiralTiler.gridSide(n, f)
            val reserved = SpiralTiler.houseTiles(side, f) + SpiralTiler.backyardTiles(side, f)
            val tiles = SpiralTiler.tiles(n, f)
            assertEquals("f=$f", n, tiles.size)
            assertEquals("f=$f unique", n, tiles.toSet().size)
            assertTrue("f=$f reserved", tiles.none { it in reserved })
            assertTrue("f=$f bounds", tiles.all { it.row in 0 until side && it.col in 0 until side })
        }
    }

    @Test fun `chronological order is preserved across a footprint change`() {
        // The re-layout shuffles plants outward but must not reorder them: plant i stays plant i.
        val small = SpiralTiler.tiles(20, 2)
        val big = SpiralTiler.tiles(20, 3)
        assertEquals(20, small.size)
        assertEquals(20, big.size)
        assertEquals(small.size, big.size)
        // Ring index (Chebyshev distance from the house block) must be non-decreasing in both.
        fun ring(t: Tile, side: Int, f: Int): Int {
            val lo = (side - f) / 2
            val dr = maxOf(lo - t.row, t.row - (lo + f - 1), 0)
            val dc = maxOf(lo - t.col, t.col - (lo + f - 1), 0)
            return maxOf(dr, dc)
        }
        val rSmall = small.map { ring(it, SpiralTiler.gridSide(20, 2), 2) }
        val rBig = big.map { ring(it, SpiralTiler.gridSide(20, 3), 3) }
        assertEquals(rSmall.sorted(), rSmall)
        assertEquals(rBig.sorted(), rBig)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.expensegarden.app.game.SpiralTilerFootprintTest"
```

Expected: **compilation failure** — `Unresolved reference: footprint`, and "Too many arguments" on `capacity`, `gridSide`, `houseTiles`, `backyardTiles`, `tiles`.

- [ ] **Step 3: Rewrite SpiralTiler with the f parameter**

Replace the entire contents of `app/src/main/java/com/expensegarden/app/game/SpiralTiler.kt`:

```kotlin
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

    fun rings(plantCount: Int, f: Int = 2): Int {
        var k = 1
        while (capacity(k, f) < plantCount) k++
        return k
    }

    fun gridSide(plantCount: Int, f: Int = 2): Int = f + 2 * rings(plantCount, f)

    /** Plantable tiles through ring k around an f×f core. Ring i holds (f+2i)² − (f+2i−2)²
     *  = 4f + 8i − 4 tiles; summing i=1..k gives 4k² + 4fk. Minus the 4 reserved grove tiles.
     *  f = 2 reduces to the 1C.6 formula 4k² + 8k − 4. */
    fun capacity(k: Int, f: Int = 2): Int = 4 * k * k + 4 * f * k - 4

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
        val skip = backyardTiles(side, f).map { Tile(it.row - origin, it.col - origin) }.toSet()
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
```

- [ ] **Step 4: Run both SpiralTiler suites**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.expensegarden.app.game.SpiralTiler*"
```

Expected: `BUILD SUCCESSFUL`. **`SpiralTilerTest` must pass with zero edits** — if it fails, the f=2 reduction is wrong; STOP and report which assertion broke.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/SpiralTiler.kt app/src/test/java/com/expensegarden/app/game/SpiralTilerFootprintTest.kt && git commit -m "feat: parameterize SpiralTiler by house footprint"
```

---

### Task 2: Prove IsoMath needs no footprint parameter

The whole plan rests on the claim that the house's screen position is independent of `f`. Assert it so a future change can't silently break it.

**Files:**
- Test: `app/src/test/java/com/expensegarden/app/render/IsoMathTest.kt`

- [ ] **Step 1: Write the failing test**

Append inside the existing `IsoMathTest` class in `app/src/test/java/com/expensegarden/app/render/IsoMathTest.kt` (before the closing brace):

```kotlin
    @Test fun `house centroid screen position is independent of footprint`() {
        // 1C.7 §1: the block is centered, so its centroid index is (side−1)/2 for every
        // footprint — the f cancels. This is exactly why fitHome takes no footprint argument.
        // side ≡ f (mod 2) always, so an even side is only ever paired with an even f.
        val side = 12
        val iso = IsoMath.fitHome(side, 1080f, 2400f, 300f, 320f)
        fun centroid(f: Int): Pair<Float, Float> {
            val lo = (side - f) / 2
            val tiles = buildList {
                for (r in lo until lo + f) for (c in lo until lo + f) add(Tile(r, c))
            }
            return tiles.map { iso.tileCenterX(it) }.average().toFloat() to
                tiles.map { iso.tileCenterY(it) }.average().toFloat()
        }
        assertEquals(centroid(2).first, centroid(4).first, 0.01f)
        assertEquals(centroid(2).second, centroid(4).second, 0.01f)
    }
```

If `Tile` is not already imported in this file, add `import com.expensegarden.app.game.Tile` to the imports.

- [ ] **Step 2: Run it**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.expensegarden.app.render.IsoMathTest"
```

Expected: `BUILD SUCCESSFUL`. This test passes immediately — it documents an existing property rather than driving new code, which is the one legitimate case for a passing-first test. **If it FAILS, stop immediately**: the plan's central assumption is wrong and Tasks 6–8 need redesigning.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/expensegarden/app/render/IsoMathTest.kt && git commit -m "test: assert house screen position is footprint-independent"
```

---

### Task 3: GardenFolder houseLevelOverride

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt:69-121`
- Test: `app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt`

- [ ] **Step 1: Write the failing test**

Append inside the existing `GardenFolderTest` class (before the closing brace). The helper `monthsSpan` and `foldAll` already exist in this file and are reused as-is:

```kotlin
    @Test fun `houseLevelOverride yields the old level and the old footprint together`() {
        // 1C.7 §2: the "before" state must be internally coherent — old level AND old
        // footprint — not a hybrid, or the expansion tween lerps against a bogus layout.
        val txns = monthsSpan(listOf(1, 2, 3, 4, 5, 6, 7))         // 7 months → level 3
        val now = foldAll(txns)
        assertEquals(3, now.houseLevel)
        assertEquals(SpiralTiler.gridSide(now.plants.size, 3), now.gridRows)

        val before = foldAll(txns, houseLevelOverride = 2)
        assertEquals(2, before.houseLevel)
        assertEquals(SpiralTiler.gridSide(before.plants.size, 2), before.gridRows)
        assertEquals(now.plants.size, before.plants.size)
        // Same transactions, same order — only the tiles differ.
        assertEquals(now.plants.map { it.txnUuid }, before.plants.map { it.txnUuid })
        assertNotEquals(now.plants.map { it.tile }, before.plants.map { it.tile })
    }

    @Test fun `default fold is unchanged by the override parameter`() {
        val txns = monthsSpan(listOf(4, 5, 7))
        assertEquals(foldAll(txns).plants.map { it.tile }, foldAll(txns, houseLevelOverride = null).plants.map { it.tile })
    }
```

Add `import org.junit.Assert.assertNotEquals` to this file's imports if absent.

You must also extend the file's existing `foldAll` helper to forward the new parameter. Find its declaration and change it to:

```kotlin
    private fun foldAll(txns: List<TransactionEntity>, houseLevelOverride: Int? = null) =
        GardenFolder.foldAllTime(
            txns, cats, emptyList(), emptyList(), 0, LocalDate.of(2026, 8, 16), ZoneId.of("UTC"),
            houseLevelOverride = houseLevelOverride,
        )
```

**Match the existing helper's argument list exactly** — read it first and only add the trailing `houseLevelOverride` argument; do not change the others.

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.expensegarden.app.game.GardenFolderTest"
```

Expected: **compilation failure** — `Cannot find a parameter with this name: houseLevelOverride`.

- [ ] **Step 3: Add the parameter to foldAllTime**

In `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt`, add a trailing parameter to `foldAllTime` (currently ending at `zone: ZoneId,` on line 76):

```kotlin
        zone: ZoneId,
        houseLevelOverride: Int? = null,                     // 1C.7: fold the SAME txns at a
                                                             // previous house level, for the
                                                             // expansion tween's "before" state
    ): GardenState {
```

- [ ] **Step 4: Move monthsTracked above the tiling and derive the footprint**

`monthsTracked` is currently computed at line 96, *below* the `tiles` call at line 89. The footprint depends on it, so it must move up. Delete the existing `monthsTracked` line and re-insert it, plus the two new lines, immediately **above** `val tiles = ...`:

```kotlin
        // Months tracked = distinct months with any LOGGED txn (investments count — showing
        // up is showing up). The house is the monument to sticking with it.
        val monthsTracked = ordered.map { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) }.distinct().size
        // 1C.7: the house's footprint drives the tiling, so the level must be resolved first.
        val level = houseLevelOverride ?: houseLevel(monthsTracked)
        val foot = SpiralTiler.footprint(level)
        val tiles = SpiralTiler.tiles(mapped.size, foot)
```

- [ ] **Step 5: Use the footprint in the returned state**

In the `return GardenState(...)` block, replace the three affected lines:

```kotlin
            gridRows = SpiralTiler.gridSide(mapped.size, foot),
            gridCols = SpiralTiler.gridSide(mapped.size, foot),
            monthMarkers = markers,
            houseLevel = level,
```

- [ ] **Step 6: Run the tests**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.expensegarden.app.game.GardenFolderTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/GardenFolder.kt app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt && git commit -m "feat: fold the all-time garden at an overridable house level"
```

---

### Task 4: Five new archetypes

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/GardenModel.kt:5-11`
- Modify: `app/src/main/java/com/expensegarden/app/game/PlantMapper.kt`
- Modify: `app/src/main/java/com/expensegarden/app/render/PlantPainter.kt:28-40`
- Test: `app/src/test/java/com/expensegarden/app/game/PlantMapperTest.kt`

- [ ] **Step 1: Write the failing test**

Append inside the existing `PlantMapperTest` class. It already has helpers for building a `TransactionEntity` and a `CategoryTree` — **read the file first and reuse its existing helper names**; the calls below assume a `txn(categoryId, amountPaise)` helper and a `tree` property, so adapt the two call sites if the names differ.

```kotlin
    @Test fun `necessity roots each grow their own family`() {
        // 1C.7 §4: before this, Groceries/Housing/Family all grew HEDGE and Transport/Health
        // both grew PERENNIAL_SHRUB — the island couldn't tell rent from a grocery run.
        assertEquals(Archetype.VEGETABLE_ROW, PlantMapper.map(txn(2L, 50_000L), tree)!!.archetype)
        assertEquals(Archetype.PERENNIAL_SHRUB, PlantMapper.map(txn(3L, 50_000L), tree)!!.archetype)
        assertEquals(Archetype.HEDGE, PlantMapper.map(txn(4L, 50_000L), tree)!!.archetype)
        assertEquals(Archetype.SUCCULENT, PlantMapper.map(txn(5L, 50_000L), tree)!!.archetype)
        assertEquals(Archetype.BERRY_BUSH, PlantMapper.map(txn(9L, 50_000L), tree)!!.archetype)
    }

    @Test fun `a subcategory archetype overrides its root family`() {
        // Food & Drinks (root 1) is PETAL_FLOWER, but two of its subcats now differ.
        assertEquals(Archetype.PETAL_FLOWER, PlantMapper.map(txn(101L, 50_000L), tree)!!.archetype)
        assertEquals(Archetype.CURL_VINE, PlantMapper.map(txn(102L, 50_000L), tree)!!.archetype)
        assertEquals(Archetype.CHAI_CLUSTER, PlantMapper.map(txn(103L, 50_000L), tree)!!.archetype)
        // An unmapped subcat still falls through to its root.
        assertEquals(Archetype.BELL_FLOWER, PlantMapper.map(txn(601L, 50_000L), tree)!!.archetype)
    }

    @Test fun `every new archetype declares two sprite variants`() {
        listOf(
            Archetype.VEGETABLE_ROW, Archetype.SUCCULENT, Archetype.BERRY_BUSH,
            Archetype.CURL_VINE, Archetype.CHAI_CLUSTER,
        ).forEach { assertEquals(it.name, 2, PlantMapper.variantCount(it)) }
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.expensegarden.app.game.PlantMapperTest"
```

Expected: **compilation failure** — `Unresolved reference: VEGETABLE_ROW`.

- [ ] **Step 3: Add the enum values**

In `app/src/main/java/com/expensegarden/app/game/GardenModel.kt`, replace the `Archetype` enum:

```kotlin
enum class Archetype {
    PETAL_FLOWER, TULIP, BELL_FLOWER, HERB_TUFT, BUSH,      // discretionary families
    CURL_VINE, CHAI_CLUSTER,                                 // 1C.7: Food & Drinks subcategories
    HEDGE, PERENNIAL_SHRUB,                                  // necessities — dignified
    VEGETABLE_ROW, SUCCULENT, BERRY_BUSH,                    // 1C.7: necessity split
    TREE,                                                    // investments, back row
    THISTLE_WEED, ODD_MUSHROOM,                              // weeds — grew during a breach
    ZOMBIE,                                                  // a regretted purchase, risen; revives when marked worth-it
}
```

- [ ] **Step 4: Map the categories**

In `app/src/main/java/com/expensegarden/app/game/PlantMapper.kt`, add the subcategory map immediately below `discretionaryByRoot`:

```kotlin
    /** 1C.7: a subcategory may override its root's family — consulted BEFORE the root maps.
     *  Food & Drinks is the highest-volume root, so its subcats earn distinct looks. */
    private val archetypeBySubcat = mapOf(
        102L to Archetype.CURL_VINE,      // Delivery — something that arrived
        103L to Archetype.CHAI_CLUSTER,   // Chai & Snacks — small and frequent
    )
```

Replace `variantCounts` wholesale:

```kotlin
    /** Sprite variants per archetype (matching the asset pack); everything else has one look. */
    private val variantCounts = mapOf(
        Archetype.PETAL_FLOWER to 3, Archetype.TULIP to 3, Archetype.BELL_FLOWER to 2,
        Archetype.HERB_TUFT to 2, Archetype.BUSH to 2, Archetype.HEDGE to 3,
        Archetype.PERENNIAL_SHRUB to 2, Archetype.TREE to 2, Archetype.ZOMBIE to 3,
        Archetype.VEGETABLE_ROW to 2, Archetype.SUCCULENT to 2, Archetype.BERRY_BUSH to 2,
        Archetype.CURL_VINE to 2, Archetype.CHAI_CLUSTER to 2,
    )
```

Replace `necessityByRoot` wholesale:

```kotlin
    private val necessityByRoot = mapOf(
        2L to Archetype.VEGETABLE_ROW,  // Groceries   (1C.7: was HEDGE)
        3L to Archetype.PERENNIAL_SHRUB,// Transport
        4L to Archetype.HEDGE,          // Housing — the topiary IS the rent landmark
        5L to Archetype.SUCCULENT,      // Health      (1C.7: was PERENNIAL_SHRUB)
        9L to Archetype.BERRY_BUSH,     // Family      (1C.7: was HEDGE)
    )
```

Add one branch to the archetype `when` in `map()`, between the `isWeed` and `ownNecessity` branches:

```kotlin
        val archetype = when {
            isZombie -> Archetype.ZOMBIE
            isWeed -> if (abs(seed) % 2 == 0) Archetype.THISTLE_WEED else Archetype.ODD_MUSHROOM
            archetypeBySubcat.containsKey(txn.categoryId) -> archetypeBySubcat.getValue(txn.categoryId)
            ownNecessity -> necessityByRoot[root] ?: Archetype.HEDGE
            else -> discretionaryByRoot[root] ?: Archetype.BUSH
        }
```

- [ ] **Step 5: Alias the procedural fallbacks**

`ProceduralPainter`'s `when` is exhaustive over `Archetype`, so the build breaks until all five are handled. In `app/src/main/java/com/expensegarden/app/render/PlantPainter.kt`, add five branches after the `Archetype.ZOMBIE` line (inside the `when`):

```kotlin
                // 1C.7 newcomers ship with sprites, so the procedural path is a
                // nearest-neighbour alias rather than five new hand-drawn looks. This only
                // ever renders if the asset pack is missing or a decode fails.
                Archetype.VEGETABLE_ROW -> bush(anchor, h * jitter)
                Archetype.BERRY_BUSH -> bush(anchor, h * jitter)
                Archetype.SUCCULENT -> herbTuft(anchor, h * jitter)
                Archetype.CURL_VINE -> herbTuft(anchor, h * jitter)
                Archetype.CHAI_CLUSTER -> herbTuft(anchor, h * jitter)
```

- [ ] **Step 6: Run the full JVM suite**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. `SpriteNamesTest` also exercises `Archetype.entries` — if it fails on a new name, read it and extend rather than weakening the assertion.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/GardenModel.kt app/src/main/java/com/expensegarden/app/game/PlantMapper.kt app/src/main/java/com/expensegarden/app/render/PlantPainter.kt app/src/test/java/com/expensegarden/app/game/PlantMapperTest.kt && git commit -m "feat: split necessity and food-subcategory plant archetypes"
```

---

### Task 5: GardenPrefs

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/data/GardenPrefs.kt`
- Modify: `app/src/main/java/com/expensegarden/app/GardenApp.kt:22-33`

No unit test: this is a thin framework wrapper with no logic worth pinning, and the behaviour that matters (does the animation replay?) is verified on device in Task 11. Adding a Robolectric harness for one Int would mean a new dependency, which the guardrails forbid.

- [ ] **Step 1: Create the file**

```kotlin
package com.expensegarden.app.data

import android.content.Context

/** Device-local VIEW state — deliberately not in Room (spec §3).
 *
 *  The local-first invariant says Room is the source of truth *for the ledger*. "Has this
 *  device already played the homestead-expansion animation" is not ledger data: in Room it
 *  would sync to the Phase 2 backend, where it is meaningless and wrong on a second device.
 *  SharedPreferences is also framework, not a library, so this adds zero dependencies. */
class GardenPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("garden_view_state", Context.MODE_PRIVATE)

    /** 0 = never recorded. A fresh install adopts the current level silently rather than
     *  animating an expansion the user was never present for. */
    var lastSeenHouseLevel: Int
        get() = prefs.getInt(KEY_LAST_SEEN_HOUSE_LEVEL, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SEEN_HOUSE_LEVEL, value).apply()

    private companion object {
        const val KEY_LAST_SEEN_HOUSE_LEVEL = "lastSeenHouseLevel"
    }
}
```

- [ ] **Step 2: Wire it into the container**

In `app/src/main/java/com/expensegarden/app/GardenApp.kt`, add an import and one property to `AppContainer`:

```kotlin
import com.expensegarden.app.data.GardenPrefs
```

and inside `class AppContainer`, after the `garden` property:

```kotlin
    val prefs: GardenPrefs = GardenPrefs(app)
```

- [ ] **Step 3: Verify it compiles**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/GardenPrefs.kt app/src/main/java/com/expensegarden/app/GardenApp.kt && git commit -m "feat: add GardenPrefs for device-local view state"
```

---

### Task 6: Footprint-aware house geometry in the canvas

Land the bigger house *without* the tween first, so the two changes can be verified separately.

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt:196-200, 465-492`

- [ ] **Step 1: Derive the footprint alongside the side**

Replace lines 196–200 (the `val side` / `houseTiles` / `backyardTiles` block):

```kotlin
    // 1C.6 homestead geometry: the house block + the 4 backyard (grove) tiles exist only in
    // world mode on the square island; both are reserved (never planted or propped).
    // 1C.7: the block is footprint×footprint, growing 2→3→4 with the house level.
    val side = state.gridRows
    val foot = SpiralTiler.footprint(state.houseLevel)
    val houseLo = (side - foot) / 2
    val houseTiles = if (worldMode) SpiralTiler.houseTiles(side, foot) else emptySet()
    val backyardTiles = if (worldMode) SpiralTiler.backyardTiles(side, foot) else emptySet()
```

- [ ] **Step 2: Generalize the house anchor and depth insertion**

Replace lines 468–471 (`houseRowsVisible` through `hAnchorY`):

```kotlin
            val houseRowsVisible = (houseLo until houseLo + foot).any { rowVisible(it) }
            val houseCorners = listOf(
                Tile(houseLo, houseLo), Tile(houseLo, houseLo + foot - 1),
                Tile(houseLo + foot - 1, houseLo), Tile(houseLo + foot - 1, houseLo + foot - 1),
            )
            val hAnchorX = if (houseBmp != null) houseCorners.map { iso.tileCenterX(vis(it)) }.average().toFloat() else 0f
            val hAnchorY = if (houseBmp != null) houseCorners.maxOf { iso.tileCenterY(vis(it)) } + iso.tileH * .5f else 0f
```

- [ ] **Step 3: Scale the drawn house to the spec's ladder**

Replace lines 487–489 (the `houseSpan` comment and assignment):

```kotlin
                    // Draw-size follows the footprint ladder (spec §4): the villa spans half
                    // the frame. Level 2 slightly overhangs its 2×2 plot, as in 1C.6.
                    val houseSpan = iso.tileW * when (state.houseLevel.coerceIn(1, 4)) {
                        1 -> 2.0f
                        2 -> 2.4f
                        3 -> 3.2f
                        else -> 4.0f
                    }
```

- [ ] **Step 4: Fix the depth-insertion test**

On line 501, the house is drawn when the loop reaches the first plant in front of it. Replace `plant.tile.row < side / 2 - 1` with the footprint-aware front row:

```kotlin
                if (houseBmp != null && houseRowsVisible && !houseDrawn && plant.tile.row < houseLo) {
```

- [ ] **Step 5: Build and run the whole suite**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt && git commit -m "feat: house block and draw scale follow the footprint ladder"
```

---

### Task 7: The expansion tween

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt` (signature ~76-86, clock block ~91-110, draw loop ~465-506, `house()` helper ~759)

- [ ] **Step 1: Add the parameters**

Extend the `GardenCanvas` signature (line 76) with two trailing parameters:

```kotlin
    worldMode: Boolean = false,   // 1C.6: square all-time island with a center house, camera roams
    expandFrom: GardenState? = null,          // 1C.7: the SAME txns folded at the previous house level
    onExpansionShown: (() -> Unit)? = null,   // fired once the tween completes, so it never replays
) {
```

- [ ] **Step 2: Drive the progress**

Immediately after the `timeState` block (after line 99), add:

```kotlin
    // 1C.7: one-shot homestead expansion. 0→1 over 1.5s, then the caller records the level so
    // it never replays. Static callers (greenhouse cards) never pass expandFrom, so ep stays 1.
    val expand = remember(expandFrom) { Animatable(if (expandFrom == null) 1f else 0f) }
    LaunchedEffect(expandFrom) {
        if (expandFrom == null) return@LaunchedEffect
        expand.snapTo(0f)
        expand.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
        onExpansionShown?.invoke()
    }
    val ep = expand.value
```

Add `import androidx.compose.animation.core.FastOutSlowInEasing` if it is not already imported.

- [ ] **Step 3: Give `house()` an alpha so the sprites can crossfade**

Replace the helper at line 759:

```kotlin
private fun DrawScope.house(bmp: ImageBitmap, cx: Float, baseY: Float, spanW: Float, shadow: Color, alpha: Float = 1f) {
    val hW = spanW
    val hH = hW * bmp.height / bmp.width
    drawOval(shadow.copy(alpha = shadow.alpha * alpha), topLeft = Offset(cx - hW * .42f, baseY - hH * .05f), size = Size(hW * .84f, hH * .13f))
    drawImage(
        image = bmp,
        srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
        dstOffset = IntOffset((cx - hW / 2f).toInt(), (baseY - hH).toInt()),
        dstSize = IntSize(hW.toInt(), hH.toInt()),
        alpha = alpha,
    )
}
```

- [ ] **Step 4: Build the "before" projection**

Inside `Canvas`, immediately **above** the `val houseBmp = ...` line (currently 467), insert:

```kotlin
            // 1C.7 expansion: plants glide from their old tiles to their new ones. Positions
            // are lerped in SCREEN space between each state's OWN iso, so a changed island
            // side needs no special-casing. The ground/slab renders at the new size for the
            // whole tween — the land is already there; the house pushes the garden onto it.
            val isoFrom = expandFrom?.let {
                IsoMath.fitHome(it.gridRows, size.width, size.height, topReservePx, bottomReservePx)
            }
            val fromTiles = expandFrom?.plants?.associate { it.txnUuid to it.tile } ?: emptyMap()
            val fromRows = expandFrom?.gridRows ?: 0
            fun visFrom(t: Tile) = Tile(fromRows - 1 - t.row, t.col)
            val expanding = expandFrom != null && isoFrom != null && ep < 1f

            /** Bottom-center of a plant, lerped from its previous tile while expanding. */
            fun anchorOf(plant: Plant): Offset {
                val v = vis(plant.tile)
                val nx = iso.tileCenterX(v)
                val ny = iso.tileCenterY(v) + iso.tileH * .18f
                val ft = fromTiles[plant.txnUuid]
                if (!expanding || ft == null) return Offset(nx, ny)
                val fv = visFrom(ft)
                val im = isoFrom!!                          // local capture: no smart-cast reliance
                val ox = im.tileCenterX(fv)
                val oy = im.tileCenterY(fv) + im.tileH * .18f
                return Offset(ox + (nx - ox) * ep, oy + (ny - oy) * ep)
            }

            /** House anchor for a given state under its own projection. */
            fun houseAnchor(s: GardenState, im: IsoMath, flip: (Tile) -> Tile): Offset {
                val f = SpiralTiler.footprint(s.houseLevel)
                val lo = (s.gridRows - f) / 2
                val corners = listOf(
                    Tile(lo, lo), Tile(lo, lo + f - 1),
                    Tile(lo + f - 1, lo), Tile(lo + f - 1, lo + f - 1),
                )
                return Offset(
                    corners.map { im.tileCenterX(flip(it)) }.average().toFloat(),
                    corners.maxOf { im.tileCenterY(flip(it)) } + im.tileH * .5f,
                )
            }
```

- [ ] **Step 5: Lerp the house anchor**

Replace the `hAnchorX` / `hAnchorY` lines written in Task 6 Step 2 with:

```kotlin
            val hNew = houseAnchor(state, iso, ::vis)
            val hAnchor = if (expanding) {
                val hOld = houseAnchor(expandFrom!!, isoFrom!!, ::visFrom)
                Offset(hOld.x + (hNew.x - hOld.x) * ep, hOld.y + (hNew.y - hOld.y) * ep)
            } else hNew
```

Then replace the two uses inside `drawHomestead` — the grove base and the `houseBmp?.let` call — as shown in the next step. Delete the now-unused `houseCorners` list; `houseAnchor` supersedes it. Keep `houseRowsVisible`.

- [ ] **Step 6: Lerp the span and crossfade the sprites**

Replace the `houseSpan` block and the `houseBmp?.let { house(...) }` line inside `drawHomestead`:

```kotlin
                    // Draw-size follows the footprint ladder (spec §4): the villa spans half
                    // the frame. Level 2 slightly overhangs its 2×2 plot, as in 1C.6.
                    fun spanOf(level: Int) = iso.tileW * when (level.coerceIn(1, 4)) {
                        1 -> 2.0f
                        2 -> 2.4f
                        3 -> 3.2f
                        else -> 4.0f
                    }
                    val newSpan = spanOf(state.houseLevel)
                    val houseSpan = if (expanding) {
                        val old = spanOf(expandFrom!!.houseLevel)
                        old + (newSpan - old) * ep
                    } else newSpan
                    // Crossfade old house → new over the same 1.5s, both at the lerped anchor.
                    val oldBmp = if (expanding) structures["house_${(expandFrom!!.houseLevel - 1).coerceIn(0, 3)}"] else null
                    oldBmp?.let { house(it, hAnchor.x, hAnchor.y, houseSpan, GardenPalette.shadow.copy(alpha = .22f), alpha = 1f - ep) }
                    houseBmp?.let { house(it, hAnchor.x, hAnchor.y, houseSpan, GardenPalette.shadow.copy(alpha = .22f), alpha = if (expanding) ep else 1f) }
```

- [ ] **Step 7: Use the lerped anchor in the plant loop**

In the plant draw loop, replace the two anchor lines (currently 504–506):

```kotlin
                val anchor = anchorOf(plant)
                val ax = anchor.x
                val ay = anchor.y
```

Leave the `val v = vis(plant.tile)` line if anything below still uses `v`; if the compiler warns it is unused, delete it.

**Known limitation, deliberately accepted:** the loop still sorts by the *final* tile depth (`sortedByDescending { iso.depth(it.tile) }`) throughout the tween. The re-layout is monotonically outward so mis-ordering is unlikely, and any artifact lasts under 1.5s. If it reads wrong on device in Task 11, change the sort to `sortedBy { anchorOf(it).y }` — a one-line fix that is exactly equivalent to the depth sort at rest.

- [ ] **Step 8: Build and run the suite**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt && git commit -m "feat: animate the homestead expansion between two folds"
```

---

### Task 8: Wire the ViewModel and screen

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt:35-47`
- Modify: `app/src/main/java/com/expensegarden/app/ui/GardenViewModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt:79-86`

- [ ] **Step 1: Let the repository fold at an overridden level**

In `GardenRepository`, add a parameter to `observeAllTimeGarden` and forward it:

```kotlin
    /** 1C.5: the persistent island — every LOGGED txn ever, with the current month's sky.
     *  1C.7: houseLevelOverride folds the same txns at a previous level, for the expansion. */
    fun observeAllTimeGarden(houseLevelOverride: Int? = null): Flow<GardenState> {
```

and change the terminal lambda's fold call:

```kotlin
            GardenFolder.foldAllTime(txns, cats, budgets, events, sips, LocalDate.now(zone), zone, houseLevelOverride)
```

- [ ] **Step 2: Expose the pair from the ViewModel**

Replace the body of `GardenViewModel` above the `companion object` in `app/src/main/java/com/expensegarden/app/ui/GardenViewModel.kt`:

```kotlin
class GardenViewModel(private val container: AppContainer) : ViewModel() {
    /** null = loading skeleton. flow{} wrapper re-derives the month on re-subscription (house idiom).
     *  1C.5: home shows the persistent all-time island; the greenhouse keeps monthly folds. */
    val garden: StateFlow<GardenState?> =
        flow { emitAll(container.garden.observeAllTimeGarden()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 1C.7: the same transactions folded at the level this device last saw. Non-null only
     *  when that level's FOOTPRINT differs — level 1→2 shares the 2×2 plot, so it records
     *  silently and never animates. A fresh install (lastSeen == 0) also adopts silently:
     *  we don't replay an expansion the user was never present for. */
    val expandFrom: StateFlow<GardenState?> =
        garden.map { g ->
            if (g == null) return@map null
            val lastSeen = container.prefs.lastSeenHouseLevel
            if (lastSeen == 0 || lastSeen >= g.houseLevel) {
                container.prefs.lastSeenHouseLevel = g.houseLevel
                return@map null
            }
            if (SpiralTiler.footprint(lastSeen) == SpiralTiler.footprint(g.houseLevel)) {
                container.prefs.lastSeenHouseLevel = g.houseLevel
                return@map null
            }
            container.garden.observeAllTimeGarden(houseLevelOverride = lastSeen).first()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun markExpansionShown() {
        garden.value?.let { container.prefs.lastSeenHouseLevel = it.houseLevel }
    }

    init {
        viewModelScope.launch { container.garden.runReconciler() }   // month.closed / streak.hit on open
    }

    suspend fun plantRow(uuid: String): TxnRow? = container.db.transactionDao().rowByUuid(uuid)

    suspend fun archivedGardens(): List<GardenState> =
        container.garden.monthsWithData().dropLast(1).map { container.garden.foldMonth(it) }.reversed()
```

Add these imports:

```kotlin
import com.expensegarden.app.game.SpiralTiler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
```

- [ ] **Step 3: Pass them to the canvas**

In `app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt`, add a collect next to the existing `garden` collect (line 70):

```kotlin
    val expandFrom by gardenVm.expandFrom.collectAsState()
```

and extend the `GardenCanvas` call (lines 79–86):

```kotlin
            GardenCanvas(
                state = g,
                painter = painter,
                structures = structures,
                modifier = Modifier.fillMaxSize(),
                onPlantTap = { uuid -> scope.launch { plantTarget = gardenVm.plantRow(uuid) } },
                worldMode = true,   // 1C.5: home is the endless all-time island
                expandFrom = expandFrom,
                onExpansionShown = { gardenVm.markExpansionShown() },
            )
```

- [ ] **Step 4: Build and run the suite**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/GardenRepository.kt app/src/main/java/com/expensegarden/app/ui/GardenViewModel.kt app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt && git commit -m "feat: wire the expansion tween through the garden view model"
```

---

### Task 9: Rebuild the art pipeline **into the repo**

Closes the hole that has now cost two rebuilds: the session scratchpad **is** cleaned between sessions. It was verified empty again while this plan was being written — `gen.py` and `briefs.py` are both gone, along with every raw but one. What survived is exactly what lived somewhere durable: the committed sprites, the committed casting sheet, and the 9GB model cache in `~/.cache`.

So this task **reconstructs** rather than copies. `briefs.py` comes from `docs/assets/sprite-briefs.md`, which is committed and carries the full brief text for all 28 sprites. `gen.py` has no surviving source or bytecode and is written fresh below.

**Files:**
- Create: `tools/art/gen.py`, `tools/art/briefs.py`, `tools/art/README.md`
- Modify: `docs/assets/sprite-briefs.md`

- [ ] **Step 1: Create `tools/art/gen.py`**

```bash
mkdir -p tools/art
```

Then create `tools/art/gen.py`:

```python
#!/usr/bin/env python3
"""Generate the garden sprite cast locally with FLUX.1-schnell via mflux.

Lives in the repo, NOT the session scratchpad — the scratchpad is cleaned between
sessions and has already taken this script twice.

Usage:
    python3 tools/art/gen.py                  # every sprite in briefs.py
    python3 tools/art/gen.py berry_bush_0     # just these
    SEED_OFFSET=7 python3 tools/art/gen.py    # re-roll with different seeds
"""
import os
import subprocess
import sys
from collections import deque

from PIL import Image

from briefs import PROMPTS, STYLE, BUILDING_STYLE

MFLUX = "mflux-generate"
MODEL = os.path.expanduser("~/.cache/mflux-models/flux1-schnell-q4")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join(REPO, "app/src/main/assets/garden")
RAW_DIR = os.path.join(HERE, "raw")
SEED_OFFSET = int(os.environ.get("SEED_OFFSET", "0"))

# Magenta despill destroys legitimate pink and red, so warm-palette sprites shoot on
# cyan instead. 1C.6 learned this when magenta bleached the tulips white; berries carry
# exactly the same risk.
CYAN_PREFIXES = ("tulip", "berry_bush")
SCREENS = {"magenta": (255, 0, 255), "cyan": (0, 255, 255)}


def screen_of(name):
    return "cyan" if name.startswith(CYAN_PREFIXES) else "magenta"


def size_of(name):
    return "1024" if name.startswith("house") else "768"


def style_of(name):
    # Houses are props, not creatures — the shared block's "huge glossy eyes" clause
    # put googly eyes on the hut during the 1C.6 pilot.
    return BUILDING_STYLE if name.startswith("house") else STYLE


def key_out(path, screen):
    """Drop the chroma screen by BORDER FLOOD-FILL, not a paint-anywhere colour match.

    This distinction is the whole trick. Baked shadow lobes touch the border, so a
    flood-fill eats them; a warm sprite's pink-drifted interior does NOT touch the
    border, so the fill can never punch holes in it. 1C.6 shipped a bug both ways
    before landing here.
    """
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    px = img.load()
    br, bg_, bb = SCREENS[screen]

    def is_bg(r, g, b):
        if screen == "cyan":
            fam = g > r + 55 and b > r + 40
        else:
            fam = r > g + 55 and b > g + 40
        return fam or (r - br) ** 2 + (g - bg_) ** 2 + (b - bb) ** 2 < 105 ** 2 * 2

    seen = bytearray(w * h)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            q.append((x, y))
    while q:
        x, y = q.popleft()
        if x < 0 or y < 0 or x >= w or y >= h or seen[y * w + x]:
            continue
        r, g, b, _ = px[x, y]
        if not is_bg(r, g, b):
            continue
        seen[y * w + x] = 1
        px[x, y] = (0, 0, 0, 0)
        q.extend(((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))

    # Gentle despill: desaturate whatever screen tint survived on the edges. An
    # aggressive transparent-drop here ate legitimate pixels in 1C.6 — do not "improve" it.
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            if screen == "magenta" and r > g + 40 and b > g + 40:
                m = (r + b) // 2
                px[x, y] = (min(r, m), g, min(b, m), a)
            elif screen == "cyan" and g > r + 40 and b > r + 40:
                m = (g + b) // 2
                px[x, y] = (r, min(g, m), min(b, m), a)

    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
    side = max(img.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(img, ((side - img.width) // 2, side - img.height))
    return square.resize((512, 512), Image.LANCZOS)


def gen(name, prompt_core):
    screen = screen_of(name)
    prompt = style_of(name).format(screen=screen) + " " + prompt_core
    raw = os.path.join(RAW_DIR, name + ".png")
    # mflux UNIQUIFIES its output (name_1.png) rather than overwriting, so a re-roll
    # silently leaves the old file in place unless we clear it first.
    if os.path.exists(raw):
        os.remove(raw)
    seed = (abs(hash(name)) % 100000) + SEED_OFFSET
    subprocess.run([
        MFLUX, "--model", MODEL, "--base-model", "schnell", "--prompt", prompt,
        "--steps", "4", "--seed", str(seed),
        "--height", size_of(name), "--width", size_of(name),
        "--output", raw, "--mlx-cache-limit-gb", "8",
    ], check=True)
    out = os.path.join(OUT_DIR, name + ".png")
    key_out(raw, screen).save(out)
    print("saved", out)


if __name__ == "__main__":
    os.makedirs(RAW_DIR, exist_ok=True)
    os.makedirs(OUT_DIR, exist_ok=True)
    wanted = sys.argv[1:] or sorted(PROMPTS)
    for n in wanted:
        if n not in PROMPTS:
            sys.exit("unknown sprite: " + n)
        gen(n, PROMPTS[n])
```

- [ ] **Step 2: Create `tools/art/briefs.py` from the committed casting sheet**

Read `docs/assets/sprite-briefs.md`. It carries the shared style block and a casting-sheet table with the full brief text for all 28 existing sprites. Transcribe it into `tools/art/briefs.py` as:

```python
"""Prompt table for the garden sprite cast. Mirrors docs/assets/sprite-briefs.md,
which is the human-readable source of truth and survived two scratchpad wipes."""

# {screen} is substituted by gen.py — warm-palette sprites shoot on cyan, not magenta.
STYLE = (
    "2D game sprite for a cozy casual tower-defense-style mobile game. Cute cartoon "
    "plant creature with an oversized head and huge glossy eyes, soft airbrushed "
    "shading, rounded chunky volumes, thick clean dark-brown outlines, vibrant "
    "saturated colors, warm rim light from the upper left, standing on a small soil "
    "mound. Single character, full body, centered, isolated on a solid bright {screen} "
    "background. No text, no watermark, no logo. No drop shadow, no cast shadow, no "
    "shadow ellipse on the ground. "
    "This must be an ORIGINAL character design. It must NOT depict or resemble "
    "Peashooter, Sunflower, Wall-nut, Crazy Dave, or any Plants vs. Zombies character."
)

# Houses are props, not creatures: the shared block's face clauses put googly eyes on
# the hut during the 1C.6 pilot.
BUILDING_STYLE = (
    "2D game sprite of a small storybook cottage building for a cozy casual mobile "
    "game, soft airbrushed shading, rounded chunky volumes, thick clean dark-brown "
    "outlines, vibrant saturated colors, warm rim light from the upper left. NO face, "
    "no eyes, no mouth — this is a building, not a character. Single building, full "
    "body, centered, isolated on a solid bright {screen} background. No text, no "
    "watermark, no logo. No drop shadow, no cast shadow, no shadow ellipse."
)

PROMPTS = {
    # ... transcribe every row of the casting sheet's table here, one entry per file ...
}
```

Each `PROMPTS` entry maps a filename stem (e.g. `"petal_flower_0"`) to that character's brief core plus its variant line, exactly as the casting sheet's "Brief core" column gives them. The 28 stems are: `petal_flower_0..2`, `tulip_0..2`, `bell_flower_0..1`, `herb_tuft_0..1`, `bush_0..1`, `hedge_0..2`, `perennial_shrub_0..1`, `tree_0..1`, `thistle_weed_0`, `odd_mushroom_0`, `zombie_0..2`, `house_0..3`.

- [ ] **Step 3: Verify the reconstruction reproduces an existing sprite**

Before trusting it on new work, regenerate one sprite that already exists and compare:

```bash
cd tools/art && python3 gen.py tulip_0 && cd "$(git rev-parse --show-toplevel)" && git status --short app/src/main/assets/garden/tulip_0.png
```

Expected: the command completes and `tulip_0.png` shows as modified (the seed path differs, so a byte-identical result is not expected). Open it with the Read tool. Expected: **a recognisable pink tulip creature with saturated colour and a clean transparent background** — no white bleaching, no leftover cyan fringe, no baked shadow ellipse. If it is bleached, `screen_of` is not routing tulips to cyan; if it has a shadow disc, `key_out`'s flood-fill is wrong.

Then discard the test regeneration:

```bash
git checkout -- app/src/main/assets/garden/tulip_0.png
```

- [ ] **Step 4: Add the 10 new briefs**

Append to `tools/art/briefs.py`'s `PROMPTS` dict. Each entry follows the existing style block convention — original characters, explicit negative prompts, no baked shadows, no faces on non-creature props:

```python
    # ---- 1C.7 necessity split ----
    "vegetable_row_0": "a low neat row of three plump round cabbages with crinkled blue-green outer leaves, chunky and sturdy, storybook game asset",
    "vegetable_row_1": "a low row of leafy dark-green chard with bright pale stems, broad ruffled leaves fanning outward, chunky and sturdy, storybook game asset",
    "succulent_0": "a single aloe rosette, thick tapered blue-green paddles radiating from the center, matte waxy surface, calm and sculptural, storybook game asset",
    "succulent_1": "a compact jade succulent, rounded fleshy blue-green leaves in tight clusters on short stems, matte waxy surface, storybook game asset",
    "berry_bush_0": "a rounded compact bush of small dark-green leaves studded with clusters of bright red berries, cheerful and generous, storybook game asset",
    "berry_bush_1": "a rounded compact bush of small dark-green leaves studded with clusters of deep purple-blue berries, cheerful and generous, storybook game asset",
    # ---- 1C.7 food-volume split ----
    "curl_vine_0": "a single coiled green vine spiralling upward in loose curls, small heart-shaped leaves along its length, springy and quick-looking, storybook game asset",
    "curl_vine_1": "a single coiled green vine spiralling upward, tighter corkscrew curls with a few tiny pale tendrils, springy and quick-looking, storybook game asset",
    "chai_cluster_0": "a low tight cluster of many tiny round cream-and-amber buds on short green stems, small and numerous, storybook game asset",
    "chai_cluster_1": "a low tight cluster of many tiny round pale-green buds on short stems, small and numerous, slightly taller sprigs at the center, storybook game asset",
```

- [ ] **Step 5: Verify the chroma routing**

`gen.py` already routes berries to cyan via `CYAN_PREFIXES`. Confirm it resolves correctly rather than assuming:

```bash
cd tools/art && python3 -c "import gen; print({n: gen.screen_of(n) for n in ['tulip_0','berry_bush_0','berry_bush_1','vegetable_row_0','succulent_0','curl_vine_0','chai_cluster_0','house_3']})"
```

Expected: `berry_bush_0`, `berry_bush_1` and `tulip_0` → `cyan`; everything else → `magenta`.

- [ ] **Step 6: Write the README**

Create `tools/art/README.md`:

```markdown
# Sprite pipeline

Generates the garden creature cast locally at ₹0 using FLUX.1-schnell via `mflux` on Apple Silicon.

## One-time setup

The quantized model lives outside this repo (9GB) and is created once:

    mflux-save --model schnell -q 4 --path ~/.cache/mflux-models/flux1-schnell-q4

`HF_TOKEN` must be set (see `~/.zshrc`) and the FLUX.1-schnell licence accepted on Hugging Face.

## Generating

    python3 tools/art/gen.py                 # all sprites in briefs.py
    SEED_OFFSET=7 python3 tools/art/gen.py   # re-roll with different seeds

Creatures render at 768px, houses at 1024px. Expect ~95s per sprite; the machine
lags noticeably during a run, so batch large runs when the Mac is free.

## Chroma keying

Sprites are shot against a solid screen and keyed by a **border flood-fill**, not a
paint-anywhere colour match — that is what stops the key punching holes in warm-tinted
interiors. Warm-palette sprites (tulips, berries) shoot on **cyan**; everything else on
**magenta**, because magenta despill destroys legitimate pink and red.

## Why these live in the repo

The session scratchpad is cleaned between sessions. During 1C.6 it took the generator and
seeder scripts with it and they had to be rebuilt from the committed casting sheet.
Anything needed to regenerate the art belongs here or in `~/.cache`.
```

- [ ] **Step 7: Update the casting sheet — including its stale pipeline section**

Two edits to `docs/assets/sprite-briefs.md`:

**(a) Fix the stale "How these briefs are used" section.** It still describes the abandoned Gemini path and a scratchpad script that no longer exists. Replace that section's body with:

```markdown
Each image = SHARED STYLE BLOCK + the character brief + VARIANT line (if any).
Generator: FLUX.1-schnell via `mflux` on Apple Silicon, q4-quantized copy at
`~/.cache/mflux-models/flux1-schnell-q4`, 4 steps, creatures at 768px and houses
at 1024px. Then chroma-key matting by border flood-fill, gentle despill, autocrop,
pad square, resize 512px, save to `app/src/main/assets/garden/<archetype>_<variant>.png`
(loader contract unchanged). Script: `tools/art/gen.py` — in the repo, because the
session scratchpad is cleaned between sessions and has eaten it twice.

Warm-palette sprites (tulips, berries) shoot on a CYAN screen; everything else on
MAGENTA. Magenta despill destroys legitimate pink and red — it bleached the tulips
white before this rule existed.
```

Also amend the shared style block quoted in that file: it currently hardcodes "solid bright magenta background (#FF00FF)" and omits the no-shadow clause. Change it to say the screen colour is per-sprite (magenta by default, cyan for warm palettes) and add "No drop shadow, no cast shadow, no shadow ellipse on the ground."

**(b) Append the 10 new briefs** under a new `## 1C.7 additions` heading, matching the existing casting-sheet table format (`| File(s) | Character | Brief core |`), and note the cyan screen on the `berry_bush` row.

- [ ] **Step 8: Commit**

`docs/assets/sprite-briefs.md` is a committed asset record (unlike `docs/superpowers/**`, which stays uncommitted), so it goes in:

```bash
git add tools/art docs/assets/sprite-briefs.md && git commit -m "chore: move the sprite pipeline into the repo"
```

---

### Task 10: Generate the 10 sprites

Slow and machine-hogging (~95s each, ~20 min total). Run it when the Mac is otherwise free.

**Files:**
- Create: `app/src/main/assets/garden/vegetable_row_{0,1}.png`, `succulent_{0,1}.png`, `berry_bush_{0,1}.png`, `curl_vine_{0,1}.png`, `chai_cluster_{0,1}.png`

- [ ] **Step 1: Confirm the model cache survived**

```bash
du -sh ~/.cache/mflux-models/flux1-schnell-q4
```

Expected: `9.0G`. Verified present as of 2026-08-16 — it lives in `~/.cache`, which is why it survived two scratchpad wipes. If it is ever missing, re-run the `mflux-save` command from `tools/art/README.md` first, but **tell the user before starting** — that path re-downloads ~32GB.

- [ ] **Step 2: Generate**

```bash
cd tools/art && python3 gen.py 2>&1 | tail -40
```

Expected: ten `... saved` lines, one per new sprite. **Verify the output files themselves — do not trust the exit code or a completion notification.** 1C.6 saw both a "failed" notification that was a `pkill` and a "completed exit 0" that masked a failed `cd`.

- [ ] **Step 3: Verify all ten landed with transparency**

```bash
cd "$(git rev-parse --show-toplevel)" && ls -la app/src/main/assets/garden/ | grep -E "vegetable_row|succulent|berry_bush|curl_vine|chai_cluster" | wc -l
```

Expected: `10`.

- [ ] **Step 4: Eyeball them, especially the berries**

Open `berry_bush_0.png` and `berry_bush_1.png` with the Read tool. Expected: red/purple berries are **saturated, not washed out**. Washed-out berries mean the cyan screen didn't take — re-check `screen_of()` and regenerate just those two. Also confirm no sprite carries a baked elliptical shadow at its base.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/garden/ && git commit -m "feat: sprites for the five new plant archetypes"
```

---

### Task 11: Device verification

**Files:** none — this is a live check on the emulator.

- [ ] **Step 1: Confirm a device is up**

```bash
PATH="$PATH:/Users/rajdweep/Library/Android/sdk/platform-tools" adb devices
```

Expected: `Pixel_8_API_35` (or equivalent) listed as `device`. If the list is empty, relaunch the AVD with `-no-snapshot-save` and poll `getprop sys.boot_completed` until it returns `1`.

- [ ] **Step 2: Back up the demo DB before anything touches app data**

```bash
PATH="$PATH:/Users/rajdweep/Library/Android/sdk/platform-tools" adb exec-out run-as com.expensegarden.app sh -c 'cat databases/garden.db' > /tmp/garden-demo-backup.db 2>/dev/null; ls -la /tmp/garden-demo-backup.db
```

Expected: a non-zero-byte file. **Redirect stderr inside the `sh -c`** — 1C.6 corrupted a pull by letting shell output mix into the stream.

- [ ] **Step 3: Install**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify each footprint rung renders**

The demo DB spans 12 months, so each rung is made by trimming it to N distinct months. The
transaction table is `txn` and its timestamp column is `occurredAt` (epoch millis). This
deletes everything from the Nth month onward, leaving exactly N months tracked — set `N` to
1, 3, 7, then 12 (12 = no trim, restore the backup):

```bash
N=7; PATH="$PATH:/Users/rajdweep/Library/Android/sdk/platform-tools" adb shell run-as com.expensegarden.app sh -c "sqlite3 databases/garden.db \"DELETE FROM txn WHERE strftime('%Y-%m', occurredAt/1000, 'unixepoch') >= (SELECT strftime('%Y-%m', MIN(occurredAt)/1000, 'unixepoch', '+$N months') FROM txn);\"" 2>&1
```

Confirm the trim took before launching:

```bash
PATH="$PATH:/Users/rajdweep/Library/Android/sdk/platform-tools" adb shell run-as com.expensegarden.app sh -c "sqlite3 databases/garden.db \"SELECT COUNT(DISTINCT strftime('%Y-%m', occurredAt/1000,'unixepoch')) FROM txn;\"" 2>&1
```

Expected: the value of `N`. Restore from `/tmp/garden-demo-backup.db` (Step 7's push command) between rungs. Force-stop the app before each relaunch so the fold re-runs:
`adb shell am force-stop com.expensegarden.app`.

Launch and screenshot each rung. Expected, per spec §1:

| Level | Months | Footprint | What to check |
|---|---|---|---|
| 1 | 1 | 2×2 | hut, 8 plantable tiles on side 4 |
| 2 | 3 | 2×2 | cottage on the **same** plot as the hut |
| 3 | 7 | 3×3 | two-story, island side is **odd** |
| 4 | 12 | 4×4 | villa spans about half the frame |

Confirm the villa now reads large relative to the plants — that was the complaint that started 1C.7.

- [ ] **Step 5: Watch both expansions actually play**

Deleting the prefs file resets `lastSeen` to 0, which by design adopts silently — that verifies
the fresh-install path but not the tween. To fire a real expansion, let the app *record* a lower
level first, then grow the data underneath it.

**Expansion A (level 2 → 3, footprint 2×2 → 3×3):**

1. Trim to `N=3` (Step 4), force-stop, launch. The app records `lastSeenHouseLevel = 2`.
2. Restore the full backup (Step 7's push command), force-stop, launch.

Expected: on that second launch the plants glide outward and the house swells and crossfades
cottage → two-story, once, over about 1.5s.

**Expansion B (level 3 → 4, footprint 3×3 → 4×4):**

1. Trim to `N=7`, force-stop, launch. Records `lastSeenHouseLevel = 3`.
2. Restore the full backup, force-stop, launch.

Expected: the same glide, two-story → villa.

**Then confirm it does not replay:** force-stop and launch once more. Expected: the villa island
renders immediately with no motion.

**Also confirm the no-op rung:** trim to `N=1`, launch (records level 1), trim to `N=3`, launch.
Expected: the house changes hut → cottage with **no glide at all** — levels 1 and 2 share the
2×2 plot, so `expandFrom` stays null by design.

**Check the draw order during the glide.** If plants visibly pop in front of ones they should sit behind, apply the Task 7 Step 7 fallback (`sortedBy { anchorOf(it).y }`), rebuild, and re-verify.

- [ ] **Step 6: Confirm the new plants render as sprites**

Seed transactions in Groceries (2), Health (5), Family (9), Delivery (102) and Chai & Snacks (103); confirm five visibly distinct plants — not the procedural fallback shapes, which would mean a sprite filename mismatch.

- [ ] **Step 7: Restore the demo DB**

Instrumentation and reinstalls wipe app data, so `databases/` may not exist. **Launch the app once to recreate it**, then push:

```bash
PATH="$PATH:/Users/rajdweep/Library/Android/sdk/platform-tools" adb shell monkey -p com.expensegarden.app 1 >/dev/null 2>&1; sleep 3; cat /tmp/garden-demo-backup.db | PATH="$PATH:/Users/rajdweep/Library/Android/sdk/platform-tools" adb exec-out run-as com.expensegarden.app sh -c 'cat > databases/garden.db'
```

Expected: no `No such file or directory`. Relaunch and confirm the villa island renders.

---

### Task 12: Full verification and wrap

- [ ] **Step 1: JVM suite**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Room instrumentation suite**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, 28 tests. **This wipes app data** — redo Task 11 Step 7 afterward.

- [ ] **Step 3: Confirm the working tree is clean except the docs**

```bash
git status --short
```

Expected: only files under `docs/superpowers/` modified. Everything else committed. If code is uncommitted, commit it; if `docs/superpowers/**` is staged, unstage it.

- [ ] **Step 4: Append the execution log**

Add a dated completion entry to the bottom of this plan file recording what shipped, the commit range, and any deviation from the plan.

---

## Out of scope (do not build these)

- Camera or zoom changes — `IsoMath` is untouched by design (Task 2 asserts why).
- Dust particles, camera push-in, or per-plant stagger on the expansion.
- Greenhouse monthly cards — serpentine, not world-mode.
- More zombie variants — noted as a later want, not part of 1C.7.
- Any change to the gate, ledger, or payment path.

---

## Execution log

### 2026-08-16 — Tasks 1–9, 11, 12 COMPLETE. Task 10 (sprites) deferred by Rajdweep.

| Task | Commit |
|---|---|
| 1 SpiralTiler footprint parameter | `cc7e18d` |
| 2 IsoMath footprint-independence assertion | `480ce60` |
| 3 Fold at an overridable house level | `75674d8` |
| 4 Five new archetypes | `bab533e` |
| 5 GardenPrefs | `7369825` |
| 6 Footprint-aware house geometry | `4aa5d8b` |
| 7 Expansion tween | `01af5cd` |
| 8 ViewModel + screen wiring | `4d20163` |
| 9 Art pipeline rebuilt in-repo | `eb1b99c` |
| 11 (fix found on device) opaque dissolve | `5427816` |

**Verification:** 119 JVM tests green (0 skipped, 0 failures); 28/28 Room instrumentation
green; all four footprint rungs crop-verified on the emulator; both expansions and the
no-op rung recorded and inspected frame by frame; demo DB restored (100 txns, 12 months).

### Deviations from the plan

1. **Task 9 changed from "copy" to "rebuild".** The scratchpad was wiped again *during
   planning*: `gen.py` and `briefs.py` gone, and the venv gutted (`PIL` reduced to two
   `.so` files, `pyvenv.cfg` deleted). `briefs.py` was reconstructed from the committed
   casting sheet; `gen.py` was rewritten from scratch. The 397 MB venv remnant was moved
   to `~/.cache/expense-garden-art-venv`, but its packages are unusable and need
   reinstalling. The 9 GB FLUX model in `~/.cache` was untouched.

2. **Two bugs fixed that the plan did not anticipate.**
   - `gen.py`'s seed used `abs(hash(name))`. Python randomises string hashing per process,
     so every run would have produced a different image and `SEED_OFFSET` re-rolls would
     have been unrepeatable. Now `zlib.crc32`.
   - The house dissolve cross-faded at `1−ep`/`ep`, which composites to `ep + (1−ep)²` —
     0.75 at the midpoint, so ground tiles showed *through the house walls*. Confirmed on
     device by slowing `animator_duration_scale` to 3×. Fixed by drawing the old sprite
     opaque underneath and fading only the new one in (`5427816`).

3. **In-session replay guard added to Task 8.** `expandFrom` is derived with `map`, so it
   held its non-null value until the next DB emission; navigating away and back would
   rebuild the canvas and replay the expansion despite the pref being written. Added
   `expansionRefresh` so `markExpansionShown()` forces re-evaluation.

4. **Six existing tests encoded the old category→archetype mapping** and were updated.
   Where a test used Chai & Snacks (103) as a generic discretionary fixture, the *fixture*
   was swapped to Restaurants (101) rather than re-baselining the expectation — 103 now
   has two variants, so re-baselining would have silently weakened the three-variant
   spread assertion to a two-variant one.

5. **`monkey` does not launch this app** (`VM exiting with result code -5`). Use
   `am start -n com.expensegarden.app/.MainActivity`. Three rungs were captured as
   byte-identical launcher screenshots before this was caught by checksumming the files —
   the plan's "verify the artifact, not the notification" rule earning its place again.

6. **`connectedDebugAndroidTest` uninstalls the app**, it does not merely wipe data. The
   restore step needs a full `installDebug` first, not just a relaunch.

### 2026-08-16 (later) — Task 10 COMPLETE. **1C.7 DONE.**

Sprites generated and committed (`e6d4db9`). All 12 tasks complete; 119 JVM tests and
28/28 Room tests green; the five new archetypes verified rendering as sprites on device,
not fallbacks.

**Environment rebuild:** the salvaged venv was unusable (gutted `dist-info` dirs would
have made pip skip Pillow), so it was deleted and recreated fresh —
`~/.cache/expense-garden-art-venv`, 1.2 GB, mflux 0.18.1 / mlx 0.31.2 / Pillow 12.3.0.
All nine `mflux-generate` flags the script uses still exist in 0.18.1. `gen.py` now
resolves `mflux-generate` next to `sys.executable`, so running it with the venv's
interpreter needs no PATH juggling.

**Generation was ~5 min/sprite, not the ~95 s estimated.** Per-step times climbed 51→81 s
under memory pressure (13.7 GB peak on a 16 GB machine, with the emulator holding 2 GB).
Shutting the emulator down first is worth doing.

**Three of ten needed a re-roll** (`SEED_OFFSET=7`), and the reasons are worth keeping:

| Sprite | Defect | Why the keyer couldn't fix it |
|---|---|---|
| `chai_cluster_1` | magenta not removed at all | FLUX composed a magenta *card inset on white*, so the screen never touched the image border and the flood-fill had no seed |
| `curl_vine_1` | magenta blob inside the sprite | interior islands are unreachable from the border **by design** — that is exactly the property that stops the fill punching holes in warm-tinted interiors |
| `berry_bush_1` | baked navy shadow ellipse | not screen-coloured, so the chroma test never matched it |

Two of these are the deliberate cost of the border flood-fill. Do **not** "fix" the keyer to
catch them — that reintroduces the 1C.6 hole-punching bug. Re-roll instead.

**Verification note:** a screen-colour scan across all ten flagged `succulent_0` (23.8%
"cyan") and `curl_vine_0` (1.8% magenta). Both are false positives — the scan reuses the
keyer's chroma-family test, so a legitimately blue-green aloe and a purple berry trip it.
Confirmed clean by eye. Treat that scan as a screening tool, never a verdict.

**Doc correction:** the casting sheet initially claimed the new archetypes would be
faceless. They are not, and should not be — the entire 1C.6 cast is faced and a faceless
plant reads as an outsider. The sheet now says so.


### 2026-09-03 — house-silhouette follow-ups (found by Rajdweep on device)

He caught two defects the plan's verification missed, both in the house's appearance. Worth
recording because my first diagnosis of each was wrong.

**1. The chimera (`642968d`).** Cross-fading the old and new house at `(1−ep)`/`ep` dipped
coverage to `ep+(1−ep)²` = 0.75 mid-tween, showing ground tiles through the walls. Drawing
the old one opaque underneath fixed that and made it worse: the two levels have different
silhouettes, so the union of both outlines showed — a house wearing two roofs. **Rule: exactly
one house sprite visible at any instant.** Any alpha blend of two building silhouettes looks
broken. Now a hard swap at the midpoint, hidden behind a construction puff (the tap-dust idiom).

**2. Creatures on the roof (`ed2ac08`, `10d34a9`).** Reported as "the house model is still
broken" after the chimera fix. It was a *separate* problem, and my first two hypotheses were
both wrong:
- Not the tween — it reproduced in the settled state with no tween running.
- Not z-order — forcing the house to draw after *every* plant left the creatures in place, and
  a 3× zoom showed the roof edge cleanly occluding their faces.

Actual cause: the island's **back corner** projects to the topmost point of the isometric
diamond, dead centre — directly above the house. Plants standing there are correctly drawn
behind the house but were tall enough to clear its roofline, so their faces appeared to sit
on the roof.

**Reserving those tiles cannot work** (this was the first plan, abandoned on the geometry):
they are island-EDGE relative, so they move outward every time a ring is added, which would
shift already-planted tiles and break the growth-invariance 1C.6 and 1C.7 both rest on.

Fix shipped: raise the villa's draw scale 4.0 → **5.2** tile-widths so its silhouette covers
them. Verified on device. `ed2ac08` also landed independently — plant/house depth now sorts by
screen anchor Y rather than tile row, because isometric depth runs along `row+col` diagonals
and a row-only test misfiles plants whose column pulls them behind the house. That was a real
bug (harmless while plants were short) but it did **not** cause the roof clutter.

**Known limit:** on a much larger island the back corner rises far enough that plants there sit
clearly above the roof. At that separation they read as distant garden rather than fused to the
building, so it is left alone.

**Verification lesson:** I checked a downsampled contact sheet and called it clean; roof clutter
is invisible at that scale. Silhouette defects must be checked at full resolution, cropped tight.

### 2026-09-04 — homestead art + rendering polish (all found by Rajdweep on device)

| commit | fix |
|---|---|
| `642968d` | one house sprite visible at any instant; swap hidden behind a construction puff |
| `ed2ac08` | plant/house depth sorts by screen anchor Y, not tile row (iso depth runs on row+col diagonals) |
| `e1bbed6` | island and expansion origin emitted as one `Homestead` state — the villa no longer flashes before the tween |
| `01a07ca` | house drawn after every plant, so nothing slices it |
| `f1b810a` | castle manor replaces the villa at level 4; `facade_gaps` + `key_failed` gates added |
| `adea5b7` | `match_palette` normalizes house saturation to the cast band (70) |
| `a01ecd2` | solid cottage — creatures were visible through its wall/base seam and roof notch |
| `2a557b1` | month signposts split into two passes around the house so those behind it are occluded |

**The one lesson that generalizes:** every building brief must forbid overhangs *by name*.
Three sprites shipped with see-through bands and all three had the same cause — FLUX draws
architecturally plausible buildings, plausible buildings have eaves and balconies and porch
roofs, and an overhang is a hole once the sprite sits in a busy scene. Invisible against white.

**Gates are proxies; the island is the arbiter.** `facade_gaps` flagged roof finials, square-pad
edge rows, and the castle's legitimate tower notches — all benign. It now judges only the
building's body, and the castle's expected 38 is documented in `briefs.py` so nobody "fixes" it.
Conversely it passed a fully opaque sprite at 0 gaps, which is why `key_failed` runs first.

**Known trade-off:** ~5 of 12 month markers now sit behind the level-4 castle and are invisible.
Accepted — the month history remains in the greenhouse.
