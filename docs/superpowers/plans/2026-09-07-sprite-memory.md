# Sprite Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop decoding all 48 plant sprites on the main thread at first garden frame; decode only what the island actually shows, off the main thread.

**Architecture:** Split the loader's two jobs — *which sprites exist in the pack* (a cheap `assets.list`) and *decode this one sprite* (expensive). A `SpriteCache` holds a `SnapshotStateMap` that `SpritePainter` reads during draw; a `LaunchedEffect` in `GardenCanvas` derives the needed keys from `GardenState` and asks the cache to warm them on `Dispatchers.IO`. Until a sprite lands, the painter's existing procedural fallback covers the plant.

**Tech Stack:** Kotlin, Jetpack Compose (`SnapshotStateMap`), `kotlinx.coroutines`, `BitmapFactory`. No new dependencies.

---

## Why

Measured 2026-09-07 from the shipped PNG headers:

| | |
|---|---|
| 54 sprites, all 512x512 | 13.4 MB on disk |
| decoded ARGB_8888 | **54.0 MB** (48 plants + 6 structures) |
| previously measured on device | PSS 132 MB, cold start 1.38 s |

`GardenApp.kt:60` declares `sprites` as `by lazy`, which reads like deferral. It is not:
`MainActivity.kt:97` calls `container.sprites.isEmpty()` inside `remember { }` during
composition, forcing the entire 48 MB decode on the main thread before the first garden frame —
and decoding all 48 whether the user has planted three things or thirty.

**Resolution is deliberately not the lever.** In world mode `tileW` freezes at `FRAME_SIDE = 8`
(`IsoMath.kt:66`), so on a 1080 px-wide screen `tileW = 124`, `tileH = 62`. At `MAX_ZOOM = 2.2`
an L plant draws at 273 px and the max-trunk tree at 478 px. Halving to 256 px would soften the
tree at exactly the zoom a user reaches for to look at it. Downsampling is rejected.

**Structures stay eager.** `drawHomestead` is gated on `houseBmp != null` (`GardenCanvas.kt:524`),
so a missing house sprite means *no house*, not a fallback. The 6 structures cost 6 MB and load
as they do today. Only the 48 plant sprites become lazy — and plants already degrade gracefully:
`SpritePainter.kt:71` falls back to `ProceduralPainter` per plant on a miss. This plan adds no
new fallback machinery; it rides the partial-pack policy the loader was built around.

**Honest limit:** memory becomes proportional to what the user has earned, so a mature island
eventually approaches today's figure. This permanently fixes cold start and fixes memory for the
early and middle game. It is not a cap.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/expensegarden/app/render/SpriteDemand.kt` | **Create.** Pure: `GardenState` -> the set of `(Archetype, variant)` keys the island needs. No Android imports, so it is JVM-testable. |
| `app/src/main/java/com/expensegarden/app/render/SpritePainter.kt` | **Modify.** Add `availableKeys()` (no decode) and `decodePlant()` (one sprite). Delete `load()`. |
| `app/src/main/java/com/expensegarden/app/render/SpriteCache.kt` | **Create.** Owns the `SnapshotStateMap`, the in-flight guard, and the IO decode. |
| `app/src/main/java/com/expensegarden/app/GardenApp.kt` | **Modify.** Replace the eager `sprites` map with a `SpriteCache`. |
| `app/src/main/java/com/expensegarden/app/MainActivity.kt` | **Modify.** Stop forcing the decode; always build a `SpritePainter` over the cache. |
| `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt` | **Modify.** `LaunchedEffect(state)` warms the demand set. |
| `app/src/test/java/com/expensegarden/app/render/SpriteDemandTest.kt` | **Create.** JVM tests for demand derivation. |
| `app/src/androidTest/java/com/expensegarden/app/render/SpriteCacheTest.kt` | **Create.** Instrumented tests for decode + dedup. |
| `app/src/androidTest/java/com/expensegarden/app/render/RareSpriteLoadTest.kt` | **Modify.** Ask `availableKeys()` instead of decoding 48 MB twice. |

---

### Task 1: Pure demand derivation

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/render/SpriteDemand.kt`
- Test: `app/src/test/java/com/expensegarden/app/render/SpriteDemandTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/render/SpriteDemandTest.kt`:

```kotlin
package com.expensegarden.app.render

import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.Plant
import com.expensegarden.app.game.SizeTier
import com.expensegarden.app.game.Tile
import com.expensegarden.app.game.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpriteDemandTest {

    private fun plant(archetype: Archetype, variant: Int) =
        Plant("t-${archetype.name}-$variant", archetype, SizeTier.M, false, Tile(0, 0), 1, variant)

    private fun state(plants: List<Plant>, backRowTreeCount: Int = 0) = GardenState(
        monthKey = "2026-09", weather = Weather.SUNNY, plants = plants, spentPaise = 0,
        backRowTreeCount = backRowTreeCount, trunkTier = 0, butterflies = 0, streakDays = 0,
        noSpendDays = 0, archived = false, gridRows = 4, gridCols = 4,
    )

    @Test fun `every planted archetype and variant is demanded`() {
        val keys = SpriteDemand.of(state(listOf(plant(Archetype.TULIP, 2), plant(Archetype.HEDGE, 0))))
        assertTrue(keys.contains(Archetype.TULIP to 2))
        assertTrue(keys.contains(Archetype.HEDGE to 0))
    }

    @Test fun `variant zero is demanded alongside a rare variant`() {
        // SpritePainter falls back to `archetype to 0` before giving up on sprites entirely,
        // so warming a rare without its base leaves the fallback path decoding during a frame.
        val keys = SpriteDemand.of(state(listOf(plant(Archetype.TULIP, 3))))
        assertTrue("base look must be warmed too", keys.contains(Archetype.TULIP to 0))
    }

    @Test fun `the tree is always demanded`() {
        // GardenCanvas synthesises Plant(..., Archetype.TREE, ...) for the back row and the
        // grove at lines 378 and 555 — neither is in state.plants, and both use variant 0.
        assertTrue(SpriteDemand.of(state(emptyList())).contains(Archetype.TREE to 0))
    }

    @Test fun `duplicate plants collapse to one key`() {
        val many = (1..20).map { plant(Archetype.TULIP, 1) }
        val tulips = SpriteDemand.of(state(many)).filter { it.first == Archetype.TULIP }
        assertEquals(setOf(Archetype.TULIP to 1, Archetype.TULIP to 0), tulips.toSet())
    }

    @Test fun `an empty garden still demands only the tree`() {
        assertEquals(setOf(Archetype.TREE to 0), SpriteDemand.of(state(emptyList())))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests '*SpriteDemandTest*'
```

Expected: FAIL to compile — `Unresolved reference: SpriteDemand`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/expensegarden/app/render/SpriteDemand.kt`:

```kotlin
package com.expensegarden.app.render

import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.GardenState

/** Which sprites an island actually needs, derived from state rather than observed during draw.
 *
 *  Deriving demand from [GardenState] keeps every side effect out of the draw phase: the canvas
 *  warms this set in a LaunchedEffect, and the draw pass only ever reads. A cache filled from
 *  inside `drawPlant` would mutate snapshot state during drawing, which Compose treats as a bug.
 *
 *  Pure and Android-free on purpose — this is the part worth unit-testing, and BitmapFactory
 *  throws "not mocked" under JVM tests. */
object SpriteDemand {

    fun of(state: GardenState): Set<Pair<Archetype, Int>> {
        val keys = mutableSetOf<Pair<Archetype, Int>>()
        // The back-row grove and the house-yard grove both synthesise TREE plants that never
        // appear in state.plants (GardenCanvas.kt:378 and :555). Always warming it costs one
        // sprite and avoids a procedural tree flashing on every island that has one.
        keys += Archetype.TREE to 0
        for (plant in state.plants) {
            keys += plant.archetype to plant.variant
            // SpritePainter resolves `archetype to variant` ?: `archetype to 0`, so the base
            // look is on the render path for every plant whose own variant has not landed yet.
            keys += plant.archetype to 0
        }
        return keys
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests '*SpriteDemandTest*'
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/SpriteDemand.kt app/src/test/java/com/expensegarden/app/render/SpriteDemandTest.kt
git commit -m "feat: derive the sprite set an island needs from its state"
```

---

### Task 2: Split the loader into key listing and single-sprite decode

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/render/SpritePainter.kt:22-62`
- Modify: `app/src/androidTest/java/com/expensegarden/app/render/RareSpriteLoadTest.kt:32,90`

- [ ] **Step 1: Add `availableKeys()` + `decodePlant()` alongside `load()`**

In `app/src/main/java/com/expensegarden/app/render/SpritePainter.kt`, add these two functions
**above** the existing `load` function, leaving `load` and `loadStructures` in place. `load` is
deleted in Task 4, when its last caller goes away — deleting it here would leave this task's
commit unable to compile, and every commit must build.

```kotlin
    /** Which plant sprites the installed pack contains — file names only, nothing decoded.
     *
     *  Split out of the old `load()` because almost every caller wanted this and paid 48 MB of
     *  PNG decode to get it. `RareSpriteLoadTest` asked purely about keys; `MainActivity` called
     *  `.isEmpty()`. Listing an asset directory answers both for free. */
    fun availableKeys(context: Context): Set<Pair<Archetype, Int>> {
        val present = runCatching { context.assets.list("garden")?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())
        return Archetype.entries.flatMap { arch ->
            (0 until MAX_VARIANTS).mapNotNull { v ->
                if (SpriteNames.fileFor(arch, v) in present) arch to v else null
            }
        }.toSet()
    }

    /** Decode exactly one plant sprite. Null when absent or undecodable — the painter's
     *  procedural fallback covers both, so neither is an error. */
    fun decodePlant(context: Context, archetype: Archetype, variant: Int): ImageBitmap? =
        runCatching {
            context.assets.open("garden/${SpriteNames.fileFor(archetype, variant)}").use { s ->
                BitmapFactory.decodeStream(s).asImageBitmap()
            }
        }.getOrNull()
```

- [ ] **Step 2: Point the existing instrumented test at the cheap call**

In `app/src/androidTest/java/com/expensegarden/app/render/RareSpriteLoadTest.kt`, change line 32
from `val loaded = SpriteLoader.load(context)` to:

```kotlin
        val loaded = SpriteLoader.availableKeys(context)
```

and line 42 from `loaded.containsKey(archetype to species.variant),` to:

```kotlin
                loaded.contains(archetype to species.variant),
```

Then change line 90 from `val loaded = SpriteLoader.load(context)` to:

```kotlin
        val loaded = SpriteLoader.availableKeys(context)
```

and line 93 from `if (!loaded.containsKey(archetype to species.variant)) continue` to:

```kotlin
            if (!loaded.contains(archetype to species.variant)) continue
```

- [ ] **Step 3: Verify the app compiles and the migrated test still passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew compileDebugKotlin
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.render.RareSpriteLoadTest
```

Expected: BUILD SUCCESSFUL, 5 instrumented tests pass — and noticeably faster than before, since
the two `SpriteLoader.load(context)` calls that each decoded 48 MB are now directory listings.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/SpritePainter.kt app/src/androidTest/java/com/expensegarden/app/render/RareSpriteLoadTest.kt
git commit -m "refactor: answer sprite-pack questions without decoding the pack"
```

---

### Task 3: The cache

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/render/SpriteCache.kt`
- Test: `app/src/androidTest/java/com/expensegarden/app/render/SpriteCacheTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/expensegarden/app/render/SpriteCacheTest.kt`. Instrumented,
not JVM: `BitmapFactory` throws "not mocked" off-device. Method names are snake_case — `minSdk 26`
means D8 rejects spaces in method names in `androidTest`.

```kotlin
package com.expensegarden.app.render

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.game.Archetype
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpriteCacheTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun cache() = SpriteCache(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test fun a_new_cache_has_decoded_nothing() {
        assertTrue("construction must not decode", cache().sprites.isEmpty())
    }

    @Test fun warming_a_key_decodes_exactly_that_key() = runBlocking {
        val c = cache()
        val available = SpriteLoader.availableKeys(context)
        val key = available.first { it.first == Archetype.TREE }
        c.warmNow(setOf(key))
        assertNotNull("TREE sprite should be resident", c.sprites[key])
        assertEquals("nothing else should have been decoded", 1, c.sprites.size)
    }

    @Test fun warming_the_same_key_twice_decodes_once() = runBlocking {
        val c = cache()
        val key = SpriteLoader.availableKeys(context).first { it.first == Archetype.TREE }
        c.warmNow(setOf(key))
        val first = c.sprites[key]
        c.warmNow(setOf(key))
        assertTrue("a resident sprite must not be re-decoded", first === c.sprites[key])
    }

    @Test fun a_key_with_no_asset_is_skipped_without_throwing() = runBlocking {
        val c = cache()
        c.warmNow(setOf(Archetype.TREE to SpriteLoader.MAX_VARIANTS + 5))
        assertTrue("an absent sprite is a fallback, not an error", c.sprites.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.render.SpriteCacheTest
```

Expected: FAIL to compile — `Unresolved reference: SpriteCache`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/expensegarden/app/render/SpriteCache.kt`:

```kotlin
package com.expensegarden.app.render

import android.content.Context
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import com.expensegarden.app.game.Archetype
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/** Plant sprites, decoded when the island first needs them and kept for the process lifetime.
 *
 *  The map is a [SnapshotStateMap], which is what makes this invisible to the renderer:
 *  `SpritePainter` reads it during the draw phase, Compose records that read, and writing a
 *  newly decoded sprite invalidates only the drawing — no recomposition, no explicit callback.
 *  Until a sprite lands the painter falls back to procedural art, which is the same path a
 *  partial sprite pack has always taken.
 *
 *  Not an LRU. Sprites are evicted by process death alone, which is the honest trade: an island
 *  shows a bounded set, and re-decoding on scroll would trade a fixed cost for a recurring one. */
class SpriteCache(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val sprites: SnapshotStateMap<Pair<Archetype, Int>, ImageBitmap> = mutableStateMapOf()

    /** Keys already decoded or currently decoding. Guards against a second warm for the same
     *  key while the first is still on IO — without it, a state change during a decode would
     *  start a duplicate and pay for the same PNG twice. */
    private val claimed: MutableSet<Pair<Archetype, Int>> = Collections.synchronizedSet(mutableSetOf())

    /** Fire-and-forget warm for the canvas. */
    fun warm(keys: Set<Pair<Archetype, Int>>) {
        scope.launch { warmNow(keys) }
    }

    /** Suspending warm, so tests can await the decode instead of polling. */
    suspend fun warmNow(keys: Set<Pair<Archetype, Int>>) {
        val wanted = keys.filter { claimed.add(it) }
        if (wanted.isEmpty()) return
        val decoded = withContext(Dispatchers.IO) {
            wanted.mapNotNull { key ->
                SpriteLoader.decodePlant(context, key.first, key.second)?.let { key to it }
            }
        }
        // Publish on Main: the write is what invalidates the draw, and doing it on one thread
        // keeps the map's growth ordered with respect to the frames that read it.
        withContext(Dispatchers.Main) { sprites.putAll(decoded) }
        // A key with no asset stays claimed — re-asking every frame would re-open the asset
        // manager for a file that is not coming.
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Install nothing beforehand — `connectedDebugAndroidTest` uninstalls both APKs when it finishes.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.render.SpriteCacheTest
```

Expected: PASS, 4 tests.

If any test hangs instead of failing, the cause is `warmNow`'s `withContext(Dispatchers.Main)`
under `runBlocking`. AndroidJUnit4 runs test methods on the instrumentation thread, not the main
thread, so this should not deadlock — but a `@UiThreadTest` annotation anywhere in this class
would make it do so. Do not add one.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/SpriteCache.kt app/src/androidTest/java/com/expensegarden/app/render/SpriteCacheTest.kt
git commit -m "feat: decode plant sprites on demand into a snapshot-backed cache"
```

---

### Task 4: Wire the cache through the app

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/GardenApp.kt:59-61`
- Modify: `app/src/main/java/com/expensegarden/app/MainActivity.kt:95-99,129-133`
- Modify: `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt` (after the `expand` block, ~line 110)

- [ ] **Step 1: Replace the eager map in the container**

In `app/src/main/java/com/expensegarden/app/GardenApp.kt`, replace the `sprites` declaration
(line 59-60, the KDoc and the `by lazy` line) with:

```kotlin
    /** Scope for work that outlives any one screen. Supervisor so one failed sprite decode
     *  cannot cancel the rest. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Plant sprites, decoded per-island rather than all 48 at the first garden frame. */
    val spriteCache: SpriteCache = SpriteCache(app, appScope)
```

Add these imports to the file, and delete the now-unused `Archetype` and `ImageBitmap` imports
if the compiler reports them as unused (`structures` still needs `ImageBitmap`):

```kotlin
import com.expensegarden.app.render.SpriteCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
```

- [ ] **Step 2: Stop forcing the decode at painter construction**

In `app/src/main/java/com/expensegarden/app/MainActivity.kt`, replace **both** identical blocks
(lines 95-99 and 129-133) with this. The `isEmpty()` branch is what forced the decode, and it is
now redundant: `SpritePainter` already falls back to procedural art per plant on a miss, so an
uninstalled pack renders exactly as it did before — decided per plant instead of once.

```kotlin
                painter = remember {
                    val container = (context.applicationContext as GardenApp).container
                    com.expensegarden.app.render.SpritePainter(container.spriteCache.sprites)
                },
```

- [ ] **Step 3: Warm the demand set from the canvas**

In `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt`, add a `spriteCache`
parameter to the composable signature, immediately after `structures` (line 78):

```kotlin
    spriteCache: SpriteCache? = null,   // null in previews and greenhouse cards with no cache
```

Then, immediately after the `LaunchedEffect(expandFrom) { ... }` block, add:

```kotlin
    // Warm from state, never from the draw pass: mutating snapshot state while drawing is a
    // Compose error, and the demand set is fully derivable from the island anyway.
    LaunchedEffect(state, spriteCache) {
        spriteCache?.warm(SpriteDemand.of(state))
    }
```

No import is needed: `GardenCanvas.kt` is already `package com.expensegarden.app.render`,
the same package as both `SpriteDemand` and `SpriteCache`.

- [ ] **Step 4: Pass the cache from the three call sites**

In `app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt`, add a parameter after
`structures` (line 65):

```kotlin
    spriteCache: SpriteCache? = null,
```

and pass it at the `GardenCanvas` call on line 84, next to the existing `painter = painter,`:

```kotlin
                spriteCache = spriteCache,
```

In `app/src/main/java/com/expensegarden/app/ui/GreenhouseScreen.kt`, add the same parameter
after `painter` (line 45), and pass `spriteCache = spriteCache,` at both `GardenCanvas` calls
(lines 70 and 99). Both files need `import com.expensegarden.app.render.SpriteCache`.

In `MainActivity.kt`, add `spriteCache = (context.applicationContext as GardenApp).container.spriteCache,`
to the `GardenHomeScreen(...)` and `GreenhouseScreen(...)` calls.

The `null` default keeps every preview and any future test call site compiling unchanged, and a
null cache degrades to procedural art rather than failing.

- [ ] **Step 5: Delete `load()`**

Its last caller is gone. Remove the `load` function from
`app/src/main/java/com/expensegarden/app/render/SpritePainter.kt` and confirm nothing references
it:

```bash
grep -rn "SpriteLoader.load(" app/src --include='*.kt'
```

Expected: no output.

- [ ] **Step 6: Build and run the full suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest lintDebug
```

Expected: BUILD SUCCESSFUL, 303 JVM tests, 0 failures, 0 lint errors.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/GardenApp.kt app/src/main/java/com/expensegarden/app/render/SpritePainter.kt app/src/main/java/com/expensegarden/app/MainActivity.kt app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt app/src/main/java/com/expensegarden/app/ui/GreenhouseScreen.kt
git commit -m "fix: stop decoding the whole sprite pack on the first garden frame"
```

---

### Task 5: Measure the result on device

**Files:** none — this task produces evidence, not code.

- [ ] **Step 1: Install alone**

`connectedDebugAndroidTest` uninstalls both APKs, and a following `installDebug` may report
success while installing nothing. Install last, alone:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
```

- [ ] **Step 2: Cold-start timing**

```bash
~/Library/Android/sdk/platform-tools/adb shell am force-stop com.expensegarden.app && ~/Library/Android/sdk/platform-tools/adb shell am start-activity -W -n com.expensegarden.app/.MainActivity | grep -E 'TotalTime|WaitTime'
```

Expected: `TotalTime` below the 1.38 s previously measured. Record the actual number.

- [ ] **Step 3: Memory**

Open the garden, let it settle, then:

```bash
~/Library/Android/sdk/platform-tools/adb shell dumpsys meminfo com.expensegarden.app | head -25
```

Expected: total PSS below the 132 MB previously measured, with the gap roughly tracking the
number of archetypes *not* planted. Record the actual number and how many distinct archetypes
the test island holds — the two numbers only make sense together.

- [ ] **Step 4: Confirm no procedural flash**

Open the garden from cold and watch the first second. Plants may appear procedurally for a frame
or two before their sprites land; anything longer, or a plant that never upgrades, means the warm
is not reaching the cache. **If this step's output does not match: stop and report.**

- [ ] **Step 5: Commit the measurements**

Append the measured numbers to this plan under a `## Results` heading, then:

```bash
git add docs/superpowers/plans/2026-09-07-sprite-memory.md
git commit -m "docs: record the sprite memory measurements"
```

*(Per the repo rule, docs and plans are never auto-committed — run this step only if Rajdweep
asks for it.)*

---

## Results

Measured 2026-09-07 on `Pixel_8_API_35`, A/B on the same emulator and the same data state:
baseline is `f473eb9` (the commit immediately before this work), after is `5dfaad2`.

### Memory — the claim holds

| island state | Java heap | Native heap | TOTAL PSS |
|---|---|---|---|
| baseline `f473eb9`, eager decode | 14,536 KB | 66,600 KB | **128,938 KB** |
| after `5dfaad2`, 0 plants | 14,408 KB | 18,424 KB | **79,972 KB** |
| after `5dfaad2`, 3 archetypes planted | 15,536 KB | 28,752 KB | **97,481 KB** |

The native-heap drop on an empty island is 48,176 KB, against 48 MB of plant sprites — the
numbers match closely enough to identify the mechanism rather than merely correlate with it.
Java heap is unchanged throughout, confirming the sprites were never on it.

The saving shrinks as the island fills, as designed and as the "Why" section predicted: 48 MB
empty, 31 MB at three archetypes. A mature island holding every archetype approaches the
baseline. This is a fix for cold start and for the early and middle game, not a cap.

### Cold start — NOT measurable on this emulator

Step 2's expected line (a `TotalTime` below the 1.38 s previously recorded) could not be
evaluated, and the earlier figure could not be reproduced by either build. Five runs of one
identical build spanned **2,447 ms to 7,339 ms**, and the same build measured a median of
2,753 ms in one batch and 4,719 ms in another taken at *lower* system load. The variance
between runs of one build is larger than any plausible difference between builds, so no
cold-start claim is made in either direction. The emulator threw two "isn't responding" system
dialogs during the session, which is the likely cause.

Re-measure on the physical phone when it is next out for the Task 12 payment E2E. A device with
no host contention should resolve this; nothing in the change is emulator-specific.

### Functional checks

- Three transactions logged through the real UI (Food & Drinks, Transport, Shopping). All three
  plants rendered as **sprites**, not the procedural fallback — the on-demand decode lands and
  the `SnapshotStateMap` write invalidates the draw, with no visible flash at this island size.
- Step 4's "no procedural flash" check passed at three plants. It has NOT been checked on a
  large island where many archetypes warm at once; that is where a flash would first appear.

### Follow-up found while measuring — RETRACTED 2026-09-13

The original note here proposed making `SpriteDemand.of` stop warming `(archetype, 0)` alongside
each plant's own variant, on the grounds that it roughly doubles per-archetype cost. Quantified
against `PlantMapper.variantCounts`, that recommendation does not hold up and should not be
acted on.

Ordinary archetypes have 2 variants (a few have 3), and a plant's variant is
`abs(seed / 31) % variantCount`. So `P(variant = 0)` is 1/2 or 1/3 per plant, and the chance
that *no* plant of an archetype is variant 0 is `((c-1)/c)^N` — at c=2, N=3 that is 12.5%. The
base look is therefore already needed, not wasted, as soon as an archetype has more than a
couple of plants. The saving is real only for archetypes with one or two plants, which is
precisely the island where memory is not a problem. It optimises the case that does not matter.

### What actually bounds the endgame

Worth stating plainly, because the measurements above could be read as more than they are.
Ordinary variants total 32 across the 14 archetypes in `variantCounts`, plus 14 rares that sit
at variant indices above them — roughly 46 of the 48 shipped plant sprites. A fully-collected
mature island therefore converges on the old resident set. **On-demand decoding fixes cold start
and the early and middle game; it does not cap the endgame.** The ceiling is the art pack.

Two levers that do move the ceiling, and why neither is taken here:

- **Downsampling** was rejected in the "Why" section above: the max-trunk tree draws at 478 px
  at `MAX_ZOOM`, so a 256 px decode is visibly soft exactly where a user has zoomed in to look.
- **Per-archetype downsampling** — shrinking only archetypes that never render large — does not
  work either, and the reason is worth recording so nobody re-derives it. `SizeTier` comes from
  the transaction *amount*, not the archetype (`PlantMapper.map`), so any archetype can be L on
  a large enough purchase. There is no archetype that is safe to shrink.

The honest remaining lever is shipping fewer or smaller sprites, which is an art decision rather
than a code one.
