# Phase 4B — Landmarks on the Island: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Place the two earned Landmarks — koi pond and stone lantern — on reserved tiles flanking the house, so the tier that rewards a year of persistence is visible on the island instead of only in the album.

**Architecture:** Two tiles are reserved by `SpiralTiler` as a function of the house footprint it is already passed, so no new state and no new parameter. Landmark species resolve **ordinally** from the sorted set of landmark earns, replacing a per-earn hash that collides. The fold emits `PlacedLandmark`s into `GardenState`; the renderer draws them inside the existing `drawHomestead` lambda at the house block's depth, exactly as the investment grove already is.

**Tech Stack:** Kotlin, Jetpack Compose Canvas, JUnit4 (JVM) + AndroidJUnit4 (instrumented), FLUX.1-schnell via mflux for the two sprites.

**Spec:** `docs/superpowers/specs/2026-09-06-phase4b-landmarks-design.md`

---

## Before you start

Every Gradle command needs JDK 17. Each shell starts fresh, so export it **every time**:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Filtered runs:

```bash
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.SpiralTilerTest'
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.render.RareSpriteLoadTest
```

Three hazards specific to this repo:

- **JVM test names use backticks with spaces. Instrumented test names must be snake_case.** `minSdk 26` targets a DEX version that rejects spaces in method names — fatal in `app/src/androidTest`, fine in `app/src/test`.
- **`connectedDebugAndroidTest` uninstalls both APKs when it finishes.** Never chain `installDebug` before it. Install last, alone. Afterwards `installDebug` may report BUILD SUCCESSFUL while installing nothing, because Gradle still believes the app is present — verify with `adb shell pm list packages | grep expensegarden`.
- **No new dependencies, no version bumps.** `gradle/libs.versions.toml` is deliberately pinned.

`adb` is at `~/Library/Android/sdk/platform-tools/adb` (not on PATH). Emulator: `~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 -no-boot-anim`.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/expensegarden/app/game/SpiralTiler.kt` | Modify | Adds `landmarkCount(f)` and `landmarkTiles(side, f)`; `capacity` subtracts the reserved landmark tiles |
| `app/src/main/java/com/expensegarden/app/game/RareModel.kt` | Modify | `RareCatalog.landmarkAssignment(earns)` replaces `pickLandmark`; `Earn.landmarkSpecies` is removed |
| `app/src/main/java/com/expensegarden/app/game/GardenModel.kt` | Modify | New `PlacedLandmark`; `GardenState.landmarks` |
| `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt` | Modify | Places landmarks onto the reserved tiles during `foldAllTime` |
| `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt` | Modify | Album call site follows the new ordinal API |
| `app/src/main/java/com/expensegarden/app/render/SpritePainter.kt` | Modify | `loadStructures` also picks up landmark sprites |
| `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt` | Modify | `landmark()` DrawScope extension + a call inside `drawHomestead` |
| `tools/art/briefs.py` | Modify | `LANDMARK_STYLE` and the two briefs |
| `tools/art/gen.py` | Modify | Routes landmark names to `LANDMARK_STYLE` |
| `app/src/main/assets/garden/koi_pond.png` | Create | Art |
| `app/src/main/assets/garden/stone_lantern.png` | Create | Art |
| `app/src/test/java/com/expensegarden/app/game/SpiralTilerTest.kt` | Modify | Reservation and capacity tests |
| `app/src/test/java/com/expensegarden/app/game/RareEngineTest.kt` | Modify | Ordinal assignment tests |
| `app/src/test/java/com/expensegarden/app/game/RareFoldTest.kt` | Modify | Fold placement tests |
| `app/src/androidTest/java/com/expensegarden/app/render/RareSpriteLoadTest.kt` | Modify | Landmark sprites are reachable |

`GardenCanvas.kt` is 1001 lines around a single ~790-line composable. **Do not refactor it** — that is unrelated work the spec explicitly excludes. The landmark drawing goes in as its own top-level `DrawScope` extension beside `house()` and `shoreShambler()`, so it does not add to the inline pile.

---

## Task 1: Reserve the landmark tiles in SpiralTiler

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/SpiralTiler.kt`
- Test: `app/src/test/java/com/expensegarden/app/game/SpiralTilerTest.kt`

- [ ] **Step 1: Write the failing tests**

Append these inside `class SpiralTilerTest` in `app/src/test/java/com/expensegarden/app/game/SpiralTilerTest.kt`:

```kotlin
    // ---------- 4B: landmark plots ----------

    @Test fun `landmark count follows the house footprint ladder`() {
        // Landmarks are earned at house levels 3 and 4, which are exactly the levels where
        // footprint() grows. That makes the count derivable from f — no new parameter.
        assertEquals(0, SpiralTiler.landmarkCount(SpiralTiler.footprint(1)))
        assertEquals(0, SpiralTiler.landmarkCount(SpiralTiler.footprint(2)))
        assertEquals(1, SpiralTiler.landmarkCount(SpiralTiler.footprint(3)))
        assertEquals(2, SpiralTiler.landmarkCount(SpiralTiler.footprint(4)))
    }

    @Test fun `landmark plots are the tiles flanking the house on its own diagonal`() {
        // Order is load-bearing: index i is the plot for landmark ordinal i, which is why this
        // returns a List and not a Set. Asserting the tiles explicitly also catches a swapped
        // pair — the left plot must stay the left plot, or a landmark the user already owns
        // would jump sides the day the second one is earned.
        val f3 = 3
        val side3 = SpiralTiler.gridSide(20, f3)
        val lo3 = (side3 - f3) / 2
        assertEquals(listOf(Tile(lo3 - 1, lo3 - 1)), SpiralTiler.landmarkTiles(side3, f3))

        val f4 = 4
        val side4 = SpiralTiler.gridSide(40, f4)
        val lo4 = (side4 - f4) / 2
        assertEquals(
            listOf(Tile(lo4 - 1, lo4 - 1), Tile(lo4 + f4, lo4 + f4)),
            SpiralTiler.landmarkTiles(side4, f4),
        )
        // Screen-x tracks (row + col), so the first plot is genuinely the LEFT one: its
        // 2*lo - 2 sits below the house centre's 2*lo + f.
        assertTrue((lo4 - 1) + (lo4 - 1) < lo4 + lo4 + f4)
    }

    @Test fun `landmark plots never collide with the house the grove or the grid edge`() {
        // The exhaustive check from spec §2, kept as a regression. A collision here would
        // either hide the landmark under the house or delete a grove tree.
        for (level in 1..4) {
            val f = SpiralTiler.footprint(level)
            for (n in 0..120) {
                val side = SpiralTiler.gridSide(n, f)
                val house = SpiralTiler.houseTiles(side, f)
                val yard = SpiralTiler.backyardTiles(side, f)
                for (t in SpiralTiler.landmarkTiles(side, f)) {
                    assertTrue("$t collides with the house at level $level, n=$n", t !in house)
                    assertTrue("$t collides with the grove at level $level, n=$n", t !in yard)
                    assertTrue(
                        "$t is outside the ${side}x$side grid at level $level, n=$n",
                        t.row in 0 until side && t.col in 0 until side,
                    )
                }
            }
        }
    }

    @Test fun `landmark plots are never planted`() {
        val f = SpiralTiler.footprint(4)
        val side = SpiralTiler.gridSide(40, f)
        val tiles = SpiralTiler.tiles(40, f)
        assertEquals(40, tiles.size)
        assertTrue(tiles.none { it in SpiralTiler.landmarkTiles(side, f) })
    }

    @Test fun `capacity accounts for the reserved landmark plots`() {
        // 4k^2 + 4fk minus 4 grove tiles minus the landmark plots.
        assertEquals(4 + 12 - 5, SpiralTiler.capacity(1, 3))     // f=3: one landmark plot
        assertEquals(4 + 16 - 6, SpiralTiler.capacity(1, 4))     // f=4: two
    }

    @Test fun `the f equals two case is untouched by landmark reservation`() {
        // Every pre-4B assertion in this file uses the default f = 2. landmarkCount(2) is 0,
        // so the old capacity formula must hold exactly. If this fails, the reservation has
        // leaked into the 1C.6 case — fix the code, not the test.
        assertEquals(0, SpiralTiler.landmarkTiles(SpiralTiler.gridSide(20, 2), 2).size)
        for (k in 1..5) assertEquals(4 * k * k + 8 * k - 4, SpiralTiler.capacity(k, 2))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.SpiralTilerTest'
```

Expected: FAIL. The Kotlin test compile errors with `Unresolved reference: landmarkCount` and `Unresolved reference: landmarkTiles`.

**If instead you see a list of test failures with no compile error, stop and check.** A failing test compile means Gradle never re-ran the suite and you may be reading the *previous* run's XML — this cost real time during 4A. Confirm `compileDebugUnitTestKotlin` succeeded before believing any failure list.

- [ ] **Step 3: Add the two functions and change capacity**

In `app/src/main/java/com/expensegarden/app/game/SpiralTiler.kt`, insert after `footprint`:

```kotlin
    /** How many landmark plots exist at a given footprint (4B spec §1).
     *
     *  Landmarks are earned at house levels 3 and 4 — which are exactly the levels where
     *  [footprint] grows. The count therefore falls out of the f the tiler is already being
     *  handed, so nothing new has to be threaded through rings/gridSide/capacity, and no
     *  landmark state is stored anywhere. */
    fun landmarkCount(f: Int): Int = maxOf(0, f - 2)

    /** The reserved landmark plots, in earn order: index i is the plot for landmark ordinal i.
     *
     *  A List, not a Set like [houseTiles] and [backyardTiles]. Those two are pure membership
     *  tests — is this tile plantable — and a set says that precisely. These need membership
     *  AND order, and a set would drop exactly the information placement depends on.
     *
     *  The specific tiles were chosen by rendering, not by reasoning. Screen-x tracks
     *  (row + col) and screen-y tracks (col − row), so a tile flanks the house at its own
     *  height only when col − row matches the house centre's. The obvious-looking pair
     *  (lo, lo−1) / (lo, lo+f) fails that and puts one landmark visually behind the other.
     *  Both of these sit on the house's own diagonal, two x-units outside its screen corners.
     *
     *  Only the first plot is reserved until the second landmark is earned, so there is never
     *  an empty plot advertising a reward that has not arrived. */
    fun landmarkTiles(side: Int, f: Int = 2): List<Tile> {
        val lo = (side - f) / 2
        return listOf(Tile(lo - 1, lo - 1), Tile(lo + f, lo + f)).take(landmarkCount(f))
    }
```

Then change `capacity`. Replace:

```kotlin
    fun capacity(k: Int, f: Int = 2): Int = 4 * k * k + 4 * f * k - 4
```

with:

```kotlin
    fun capacity(k: Int, f: Int = 2): Int = 4 * k * k + 4 * f * k - 4 - landmarkCount(f)
```

and extend its KDoc by appending this sentence to the existing block comment above it:

```
     *  4B: also minus the reserved landmark plots, which is zero at f = 2 — so every 1C.6
     *  expectation in SpiralTilerTest holds unchanged.
```

Finally — and this is the half the first draft of this plan missed — teach `tiles()` to skip the
plots. In `fun tiles(...)`, replace:

```kotlin
        val skip = backyardTiles(side, f).map { Tile(it.row - origin, it.col - origin) }.toSet()
```

with:

```kotlin
        // Both reserved plots, in house-relative coords to match the ring walk below.
        // capacity() only makes the island BIG enough to hold the reservation; refusing to
        // plant on it is this set's job. Updating one without the other grows the island and
        // then plants on the plot anyway — which is exactly what happened first time.
        val skip = (backyardTiles(side, f) + landmarkTiles(side, f))
            .map { Tile(it.row - origin, it.col - origin) }.toSet()
```

**Reserving space and refusing to use it are two separate mechanisms.** `capacity()` sizes the
island; `skip` decides occupancy. Changing only the first passes every capacity assertion and
still plants a tulip in the koi pond.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.SpiralTilerTest'
```

Expected: PASS, 11 tests (5 pre-existing + 6 new). The 5 pre-existing must still pass — they all use the default `f = 2`.

- [ ] **Step 4b: Update the one existing assertion that legitimately changes**

There are **two** tiler test files. Running the full suite will fail one test in the other:

```
SpiralTilerFootprintTest > capacity counts the full square minus house and grove
java.lang.AssertionError: f=3 k=1 expected:<12> but was:<11>
```

That is correct behaviour, not a regression — that test parameterises `f` over `{2, 3, 4}` and
asserts the pre-4B identity `side² − f² − 4`. Note **which** rows fail: f=2 passes for every k,
and the first failure is f=3. That is the tripwire saying the reservation did *not* leak into the
1C.6 case. Update the identity, and rename the test since it now covers all three reserved kinds:

```kotlin
    @Test fun `capacity counts the full square minus every reserved plot`() {
        // side = f + 2k, so side² − f² − 4 grove tiles − the landmark plots must equal
        // capacity(k, f). 4B added the last term: this test parameterises f over {2, 3, 4},
        // so unlike everything in SpiralTilerTest it does see the new reservation. At f = 2
        // landmarkCount is 0 and the original identity is unchanged, which is what says the
        // reservation has not leaked into the 1C.6 case.
        for (f in listOf(2, 3, 4)) {
            for (k in 1..4) {
                val side = f + 2 * k
                val reserved = 4 + SpiralTiler.landmarkCount(f)
                assertEquals("f=$f k=$k", side * side - f * f - reserved, SpiralTiler.capacity(k, f))
            }
        }
    }
```

The other six tests in that file pass untouched, including `reserved tiles are never planted at
any footprint` — it checks house and grove, and the landmark plots are skipped too, so it stays
true as a subset.

- [ ] **Step 5: Run the full JVM suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: `failures=0 errors=0`, tests = 252 + 6 = 258.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/SpiralTiler.kt \
        app/src/test/java/com/expensegarden/app/game/SpiralTilerTest.kt \
        app/src/test/java/com/expensegarden/app/game/SpiralTilerFootprintTest.kt
git commit -m "feat: reserve landmark plots flanking the house block"
```

---

## Task 2: Assign landmark species ordinally

This replaces a **latent** defect in shipped 4A code — corrected from an earlier draft of this plan, which called it a live one.

`pickLandmark` hashes each earn independently against the pool. Checked against the real hashes, that is correct today: `seedFrom` is `scopeKey.hashCode()`, `"house:3"` and `"house:4"` differ by one character so their hashes differ by exactly 1, and `% 2` always yields opposite indices. Both landmarks ship reachable. The earlier claim that they collided half the time was wrong.

The correctness rests on the pool being exactly two *and* the levels being adjacent. Non-adjacent levels collide (`house:3` and `house:6` share a parity), a third landmark changes the pool size, and renaming the scope key changes the hashes — none of which fails loudly. Ordinal assignment is also required regardless of the bug: the fold must index species against `landmarkTiles` in a stable order (Task 3).

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/RareModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt:125`
- Test: `app/src/test/java/com/expensegarden/app/game/RareEngineTest.kt`

- [ ] **Step 1: Write the failing tests**

Append inside `class RareEngineTest` in `app/src/test/java/com/expensegarden/app/game/RareEngineTest.kt`:

```kotlin
    // ---------- 4B: landmark species assignment ----------

    @Test fun `the two landmark levels never resolve to the same species`() {
        // The property pickLandmark only satisfied by luck. It hashed each earn independently
        // against the pool: "house:3" and "house:4" differ by one character, so their hashes
        // differ by 1, so % 2 always gave opposite indices. Correct — but only because the
        // pool is two AND the levels are adjacent. "house:3" with "house:6" share a parity and
        // would have collided, silently costing a landmark. Assert the property directly so it
        // survives a third landmark or a renamed scope key.
        val e = earns(emptyList(), houseLevel = 4)
        val assigned = RareCatalog.landmarkAssignment(e)
        assertEquals(2, assigned.size)
        assertEquals(2, assigned.map { it.second.id }.toSet().size)
    }

    @Test fun `the first landmark earned is always the same species`() {
        // Ordinal, not hashed: level 3 alone must give the same species it gives as part of
        // a level-4 pair, or the album would rename a landmark the user already owns.
        val atThree = RareCatalog.landmarkAssignment(earns(emptyList(), houseLevel = 3))
        val atFour = RareCatalog.landmarkAssignment(earns(emptyList(), houseLevel = 4))
        assertEquals(1, atThree.size)
        assertEquals(atThree[0].second.id, atFour[0].second.id)
    }

    @Test fun `landmark assignment is ordered by scope key`() {
        val assigned = RareCatalog.landmarkAssignment(earns(emptyList(), houseLevel = 4))
        assertEquals(listOf("house:3", "house:4"), assigned.map { it.first.scopeKey })
    }

    @Test fun `plantable earns are never assigned a landmark species`() {
        val e = earns(listOf(streak(7)), houseLevel = 4)
        val assigned = RareCatalog.landmarkAssignment(e)
        assertTrue(assigned.all { it.first.tier == RareTier.LANDMARK })
    }

    @Test fun `no landmarks earned yields no assignment`() {
        assertTrue(RareCatalog.landmarkAssignment(earns(emptyList(), houseLevel = 2)).isEmpty())
    }

    @Test fun `landmark assignment never exceeds the catalogue`() {
        // Defensive: the ladder tops out at two, but a truncating zip means a third earn could
        // never index past the end of the pool.
        val e = earns(emptyList(), houseLevel = 4)
        assertTrue(RareCatalog.landmarkAssignment(e).size <= RareCatalog.pool(RareTier.LANDMARK).size)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.RareEngineTest'
```

Expected: FAIL, compile error `Unresolved reference: landmarkAssignment`.

- [ ] **Step 3: Add the ordinal assignment and delete the hash**

In `app/src/main/java/com/expensegarden/app/game/RareModel.kt`, **delete** this property from `data class Earn`:

```kotlin
    /** Landmarks are the exception: they belong to no archetype because they are island
     *  features rather than plants, so they resolve from the earn alone. */
    val landmarkSpecies: RareSpecies?
        get() = if (tier == RareTier.LANDMARK) RareCatalog.pickLandmark(sourceEventId) else null
```

**Delete** `pickLandmark` from `object RareCatalog`:

```kotlin
    /** Landmarks resolve without an archetype — see [Earn.landmarkSpecies]. */
    fun pickLandmark(seed: Long): RareSpecies =
        LANDMARKS[(seed.hashCode().toLong().absoluteValue % LANDMARKS.size).toInt()]
```

**Add** to `object RareCatalog` in its place:

```kotlin
    /** Which landmark each landmark earn resolves to, in a stable order (4B spec §3).
     *
     *  Ordinal, not hashed. `pickLandmark` hashed each earn independently against the pool,
     *  which happened to work and was one edit away from not working. `"house:3"` and
     *  `"house:4"` differ in one character, so their hashes differ by exactly 1, so `% 2`
     *  always gives opposite indices — both landmarks were reachable purely because the pool
     *  is two and the levels are adjacent.
     *
     *  This takes the whole earn LIST rather than one Earn, because "which landmark is this"
     *  is a question about the set, not about one element — an earn cannot know it is the
     *  second one. Sorting by scope key ("house:3" before "house:4") keeps it deterministic,
     *  which is what the fold requires: a runtime roll here would make replays diverge and
     *  the greenhouse's archived months drift.
     *
     *  Zipping truncates, so a landmark earn with no species left simply gets none. */
    fun landmarkAssignment(earns: List<Earn>): List<Pair<Earn, RareSpecies>> =
        earns.filter { it.tier == RareTier.LANDMARK }
            .sortedBy { it.scopeKey }
            .zip(LANDMARKS)
```

If `kotlin.math.absoluteValue` is now unused in the file, leave the import alone — `pick` still uses it. Verify by searching: `grep -c absoluteValue app/src/main/java/com/expensegarden/app/game/RareModel.kt` should return 2 or more.

- [ ] **Step 4: Update the album call site**

In `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt`, replace line 125:

```kotlin
        val landmarks = earns.mapNotNull { e -> e.landmarkSpecies?.let { it.id to e.trigger } }.toMap()
```

with:

```kotlin
        val landmarks = RareCatalog.landmarkAssignment(earns).associate { (e, s) -> s.id to e.trigger }
```

Confirm `RareCatalog` is imported in that file; if not, add `import com.expensegarden.app.game.RareCatalog`.

- [ ] **Step 5: Run the full JVM suite to verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Count the results rather than trusting the exit code:

```bash
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=0
for p in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    r=ET.parse(p).getroot()
    t+=int(r.get("tests",0)); f+=int(r.get("failures",0)); e+=int(r.get("errors",0))
print(f"tests={t} failures={f} errors={e}")
PY
```

Expected: `failures=0 errors=0`, tests = 252 + 6 (Task 1) + 6 (Task 2) = 264.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/RareModel.kt \
        app/src/main/java/com/expensegarden/app/data/GardenRepository.kt \
        app/src/test/java/com/expensegarden/app/game/RareEngineTest.kt
git commit -m "fix: assign landmarks ordinally so both species are reachable"
```

---

## Task 3: Place landmarks in the fold

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/GardenModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt:116-130`
- Test: `app/src/test/java/com/expensegarden/app/game/RareFoldTest.kt`

- [ ] **Step 1: Write the failing tests**

Append inside `class RareFoldTest` in `app/src/test/java/com/expensegarden/app/game/RareFoldTest.kt`:

```kotlin
    // ---------- 4B: landmark placement ----------

    private fun foldAtLevel(level: Int) = GardenFolder.foldAllTime(
        allTxns = listOf(txn("a", 7, day = 3)),
        categories = categories,
        currentBudgets = emptyList(),
        currentMonthEvents = emptyList(),
        allTimeInvestmentCount = 0,
        today = today,
        zone = zone,
        houseLevelOverride = level,
        rareSignals = emptyList(),
    )

    @Test fun `no landmark is placed below house level three`() {
        assertTrue(foldAtLevel(2).landmarks.isEmpty())
    }

    @Test fun `house level three places exactly one landmark`() {
        assertEquals(1, foldAtLevel(3).landmarks.size)
    }

    @Test fun `house level four places both landmarks and they differ`() {
        val placed = foldAtLevel(4).landmarks
        assertEquals(2, placed.size)
        assertEquals(2, placed.map { it.species.id }.toSet().size)
    }

    @Test fun `a placed landmark never shares a tile with a plant or the house`() {
        val g = foldAtLevel(4)
        val house = SpiralTiler.houseTiles(g.gridRows, SpiralTiler.footprint(4))
        for (l in g.landmarks) {
            assertTrue(g.plants.none { it.tile == l.tile })
            assertTrue(l.tile !in house)
        }
    }

    @Test fun `landmark placement is deterministic across repeated folds`() {
        // Spec §4.1 — same property the rest of the fold rests on.
        val first = foldAtLevel(4).landmarks.map { it.species.id to it.tile }
        val second = foldAtLevel(4).landmarks.map { it.species.id to it.tile }
        assertEquals(first, second)
    }

    @Test fun `the reserved plot count always matches the number of landmarks earned`() {
        // RareEngine emits for every level <= houseLevel while landmarkCount derives from the
        // footprint. The two agree by construction; this pins that so a change to either ladder
        // cannot silently strand a landmark with no plot, or reserve a plot with nothing on it.
        for (level in 1..4) {
            val f = SpiralTiler.footprint(level)
            assertEquals(SpiralTiler.landmarkCount(f), foldAtLevel(level).landmarks.size)
        }
    }
```

No new imports. This test file, `SpiralTiler`, `GardenFolder`, `PlacedLandmark` and `RareCatalog` all live in `com.expensegarden.app.game`. The same is true of the production edits in `GardenFolder.kt`. Only `GardenRepository.kt` (package `…app.data`) and `SpritePainter.kt` (package `…app.render`) need imports added, and those are called out in Tasks 2 and 4.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.RareFoldTest'
```

Expected: FAIL, compile error `Unresolved reference: landmarks`.

- [ ] **Step 3: Add the model type and the state field**

In `app/src/main/java/com/expensegarden/app/game/GardenModel.kt`, add after `data class MonthMarker`:

```kotlin
/** An earned Landmark standing on its reserved plot (4B).
 *
 *  Not a [Plant]: a landmark has no transaction behind it, so it carries no uuid, no size tier
 *  and no regret state. It is closer to the house — a fixture of the island that the garden
 *  grew around — which is also why it renders at the house block's depth. */
data class PlacedLandmark(val species: RareSpecies, val tile: Tile)
```

Add to `data class GardenState`, immediately after `houseLevel`:

```kotlin
    /** 4B: earned landmarks on their reserved plots. Empty for monthly folds — landmarks are
     *  an all-time property of the island, like the house, not a property of one month. */
    val landmarks: List<PlacedLandmark> = emptyList(),
```

- [ ] **Step 4: Place them in the fold**

In `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt`, find this block (around line 116):

```kotlin
        val foot = SpiralTiler.footprint(level)
        val tiles = SpiralTiler.tiles(mapped.size, foot)
```

and replace it with:

```kotlin
        val foot = SpiralTiler.footprint(level)
        // Hoisted to a local because three things now depend on it agreeing with itself: the
        // plant tiling, the landmark plots, and gridRows/gridCols below — which each called
        // gridSide() inline. Same arguments give the same answer, so this is not a fix; it is
        // removing the chance for a future edit to make one of them disagree, which would put
        // a landmark plot on a tile the plants think is plantable.
        val side = SpiralTiler.gridSide(mapped.size, foot)
        val tiles = SpiralTiler.tiles(mapped.size, foot)

        // 4B: landmarks take reserved plots, so they are placed independently of the plant
        // tiling rather than competing with it. Zipping truncates — a landmark with no plot, or
        // a plot with no landmark, simply does not appear. The two agree by construction
        // anyway: RareEngine emits one earn per house level 3..level, and landmarkCount derives
        // from that same level's footprint. `the reserved plot count always matches the number
        // of landmarks earned` in RareFoldTest pins that.
        val landmarks = RareCatalog.landmarkAssignment(earns)
            .map { it.second }
            .zip(SpiralTiler.landmarkTiles(side, foot))
            .map { (species, tile) -> PlacedLandmark(species, tile) }
```

Then in the `GardenState(...)` constructor call at the end of `foldAllTime` (around lines 143-146), replace the two inline `gridSide` calls with the hoisted local and add the new field:

```kotlin
            gridRows = side,
            gridCols = side,
```

and add `landmarks = landmarks,` immediately after the `houseLevel = level,` argument.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, `failures=0 errors=0`, tests = 270.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/game/GardenModel.kt \
        app/src/main/java/com/expensegarden/app/game/GardenFolder.kt \
        app/src/test/java/com/expensegarden/app/game/RareFoldTest.kt
git commit -m "feat: place earned landmarks on their reserved plots in the fold"
```

---

## Task 4: Load landmark sprites

`loadStructures` already returns a `Map<String, ImageBitmap>` keyed by filename stem — exactly the id-keyed map landmarks need. It just filters on `house_`. Widen it rather than adding a parallel map.

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/render/SpritePainter.kt:43-54`
- Test: `app/src/androidTest/java/com/expensegarden/app/render/RareSpriteLoadTest.kt`

- [ ] **Step 1: Write the failing test**

Append inside `class RareSpriteLoadTest`. **snake_case names — spaces are fatal here at minSdk 26.**

```kotlin
    @Test fun every_shipped_landmark_sprite_is_reachable_through_the_structure_map() {
        // A landmark has no archetype, so it cannot key into SpriteLoader's (archetype,
        // variant) map — it loads by id through loadStructures instead. A mis-keyed landmark
        // fails exactly as silently as a mis-keyed rare: nothing throws, nothing logs, and the
        // reward the user waited a year for simply is not on the island.
        val present = shippedAssets()
        val structures = SpriteLoader.loadStructures(context)
        for (species in RareCatalog.pool(RareTier.LANDMARK)) {
            val file = "${species.spriteName}.png"
            if (file !in present) continue                 // not generated yet
            assertTrue(
                "asset $file ships but loadStructures has no entry for id '${species.id}'",
                structures.containsKey(species.id),
            )
        }
    }

    @Test fun widening_the_structure_map_did_not_drop_the_house_sprites() {
        // The house ladder shares this map. Losing house_0 would render the homestead blank.
        val structures = SpriteLoader.loadStructures(context)
        val present = shippedAssets()
        for (i in 0..3) {
            if ("house_$i.png" !in present) continue
            assertTrue("house_$i vanished from loadStructures", structures.containsKey("house_$i"))
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Start the emulator first if it is not running:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 -no-boot-anim &
```

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.render.RareSpriteLoadTest
```

Expected: PASS, not fail — no landmark art exists yet, so the `continue` skips both species and the test is vacuously true. That is intentional: the test guards mis-keying, and a missing file is a to-do rather than a bug. It becomes a real assertion in Task 6.

`widening_the_structure_map_did_not_drop_the_house_sprites` must PASS now and after Step 3.

- [ ] **Step 3: Widen loadStructures**

In `app/src/main/java/com/expensegarden/app/render/SpritePainter.kt`, replace:

```kotlin
    /** Named non-plant structures (house levels). Same graceful partial-pack behavior. */
    fun loadStructures(context: Context): Map<String, ImageBitmap> {
        val present = runCatching { context.assets.list("garden")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        return present.filter { it.startsWith("house_") && it.endsWith(".png") }.mapNotNull { name ->
```

with:

```kotlin
    /** Named non-plant structures, keyed by file stem. Same graceful partial-pack behavior.
     *
     *  Two kinds live here: the house ladder (house_0..3) and, from 4B, the landmarks. A
     *  landmark is the only RareSpecies with no archetype — it is an island feature, not a
     *  plant — so it cannot key into [load]'s (archetype, variant) map and loads by id
     *  instead. Reusing this map rather than adding a parallel one keeps one loader, one
     *  partial-pack policy, and one thing to wire through GardenCanvas. */
    fun loadStructures(context: Context): Map<String, ImageBitmap> {
        val present = runCatching { context.assets.list("garden")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        val landmarkFiles = RareCatalog.pool(RareTier.LANDMARK).map { it.spriteName + ".png" }.toSet()
        return present.filter { it.endsWith(".png") && (it.startsWith("house_") || it in landmarkFiles) }.mapNotNull { name ->
```

Add these imports to the top of `SpritePainter.kt`:

```kotlin
import com.expensegarden.app.game.RareCatalog
import com.expensegarden.app.game.RareTier
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.render.RareSpriteLoadTest
```

Expected: PASS, 5 tests (3 pre-existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/SpritePainter.kt \
        app/src/androidTest/java/com/expensegarden/app/render/RareSpriteLoadTest.kt
git commit -m "feat: load landmark sprites by id through the structure map"
```

---

## Task 5: Draw landmarks on the island

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt` (the `drawHomestead` lambda around line 531, and a new extension near `house()` at line 889)

No test here. This is Canvas drawing with no return value; it is verified by eye in Task 7. Do not invent a screenshot-diff harness for it — that is not in scope.

- [ ] **Step 1: Add the drawing extension**

In `app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt`, add immediately after the `private fun DrawScope.house(...)` function (around line 903):

```kotlin
/** An earned landmark on its reserved plot (4B).
 *
 *  Bottom-anchored like a plant billboard, but drawn with the homestead rather than in the
 *  per-plant depth sort: a landmark sits on a reserved plot beside the house, so it belongs in
 *  the same depth band as the grove. It also never sways — the island's motion vocabulary is
 *  for living things, and a stone lantern that breathes reads as a bug.
 *
 *  Falls back to nothing at all when the sprite is missing, rather than to procedural art.
 *  There is no procedural koi pond, and a wrong shape here would be worse than an empty plot. */
private fun DrawScope.landmark(bmp: ImageBitmap?, cx: Float, baseY: Float, spanW: Float, shadow: Color) {
    if (bmp == null) return
    val w = spanW
    val h = w * bmp.height / bmp.width
    drawOval(
        shadow.copy(alpha = .18f),
        topLeft = Offset(cx - w * .34f, baseY - h * .06f),
        size = Size(w * .68f, h * .13f),
    )
    drawImage(
        image = bmp,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bmp.width, bmp.height),
        dstOffset = IntOffset((cx - w / 2f).toInt(), (baseY - h).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
    )
}
```

Note this sizes from **width**, not height, unlike `SpritePainter.drawPlant`. A koi pond is wider than it is tall, and height-driven sizing would blow it up to fill the island.

- [ ] **Step 2: Call it from drawHomestead**

In the `val drawHomestead = { with(ds) { ... } }` lambda, immediately **before** the grove `repeat(...)` block (which begins `val yard = backyardTiles.sortedBy { it.col }`), insert:

```kotlin
                    // Landmarks first: their plots flank the house, so anything the house
                    // overlaps must already be on the canvas when the house paints over it.
                    for (l in state.landmarks) {
                        val v = vis(l.tile)
                        val base = Offset(iso.tileCenterX(v), iso.tileCenterY(v) + iso.tileH * .18f)
                        landmark(structures[l.species.id], base.x, base.y, iso.tileW * 1.15f, GardenPalette.shadow)
                    }
```

`vis`, `iso`, `structures` and `state` are all already in scope inside this lambda — `drawHomestead` is declared after all of them.

**Put it inside `drawHomestead` and nowhere else.** Its sole call site is `if (houseBmp != null && houseRowsVisible && !houseDrawn) drawHomestead()`, and `houseBmp` is `if (worldMode) … else null`. That gates landmarks to world mode for free — the greenhouse renders this same `GardenCanvas` in non-world mode, where a landmark tile means nothing in the serpentine layout, so a draw call in the plant loop would leak landmarks onto every month card. Two consequences come with it, both accepted: landmarks do not draw if the house sprite is missing from a partial asset pack, and they do not draw when the house rows are culled by the camera — their plots flank the house, so if the house is off-screen they are too.

Landmarks deliberately do **not** lerp during a 1C.7 expansion tween. The grove does not either (it uses the new `iso` directly), and matching that keeps one behaviour rather than two.

- [ ] **Step 3: Verify it compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. If `ImageBitmap`, `IntOffset`, `IntSize`, `Size` or `Offset` are unresolved in the new extension, they are already imported at the top of this file for `house()` — check rather than adding duplicates.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt
git commit -m "feat: draw earned landmarks at the homestead's depth"
```

---

## Task 6: Generate the two sprites

**Files:**
- Modify: `tools/art/briefs.py`
- Modify: `tools/art/gen.py`
- Create: `app/src/main/assets/garden/koi_pond.png`
- Create: `app/src/main/assets/garden/stone_lantern.png`

Read `tools/art/README.md` first. The pipeline needs `~/.cache/expense-garden-art-venv/bin/python3` and the 9 GB model at `~/.cache/mflux-models/flux1-schnell-q4`. Expect several minutes per sprite; the machine lags noticeably during a run.

- [ ] **Step 1: Add the landmark style block**

In `tools/art/briefs.py`, add after the `BUILDING_STYLE` definition:

```python
# Landmarks are neither creatures nor buildings. STYLE opens with "cute cartoon plant creature
# with an oversized head and huge glossy eyes ... standing on a small soil mound" — that clause
# is exactly why BUILDING_STYLE exists, because it put googly eyes on the hut during the 1C.6
# pilot. A koi pond is ground geometry and a lantern is a garden prop; reusing STYLE would
# reproduce a bug this project has already paid for once.
#
# The no-cast-shadow clause is dropped deliberately. For a pond, water and its reflection ARE
# the subject, and the art review's usual "a pale ellipse on the ground is a baked ground plane"
# rule would reject correct art here.
LANDMARK_STYLE = (
    "2D game sprite for a cozy casual tower-defense-style mobile game. A small ornamental "
    "GARDEN FEATURE, not a character and not a building: no face, no eyes, no limbs. Soft "
    "airbrushed shading, rounded chunky volumes, thick clean dark-brown outlines, vibrant "
    "saturated colors, warm rim light from the upper left. Single object, centered, isolated "
    "on a solid bright {screen} background. No text, no watermark, no logo. "
    "This must be an ORIGINAL design."
)
```

- [ ] **Step 2: Add the two briefs**

Add to the `PROMPTS` dict in `tools/art/briefs.py`. The keys must match `RareCatalog`'s landmark ids exactly — a typo renders nothing, with no error to point at:

```python
    "koi_pond": (
        "A small oval ornamental KOI POND set into a low mound of dark earth, ringed by "
        "smooth grey stones, still turquoise water with two orange-and-white koi just below "
        "the surface, three flat lily pads and one pink lotus bloom at the near edge, gentle "
        "highlights on the water. Seen from a slightly raised three-quarter angle."
    ),
    "stone_lantern": (
        "A carved grey STONE GARDEN LANTERN on a low mound of dark earth: a square weathered "
        "pedestal, a tapered shaft, an open lamp chamber glowing warm amber from within, and "
        "a wide pagoda-style capstone with a small finial. A little moss in the crevices, "
        "two fern fronds at the base."
    ),
```

- [ ] **Step 3: Route landmark names to the new style**

In `tools/art/gen.py`, add after `CYAN_PREFIXES`:

```python
# Landmarks are props, not creatures — see LANDMARK_STYLE. Named explicitly rather than by
# prefix because landmark sprite names are RareCatalog ids, which carry no shared prefix.
LANDMARK_NAMES = ("koi_pond", "stone_lantern")
```

Change the import line to include the new block:

```python
from briefs import PROMPTS, STYLE, BUILDING_STYLE, LANDMARK_STYLE  # noqa: E402
```

Replace `style_of`:

```python
def style_of(name):
    # Houses are props, not creatures — the shared block's "huge glossy eyes" clause
    # put googly eyes on the hut during the 1C.6 pilot. Landmarks are props too (4B).
    if name.startswith("house"):
        return BUILDING_STYLE
    if name in LANDMARK_NAMES:
        return LANDMARK_STYLE
    return STYLE
```

Both landmarks stay on the **magenta** screen. Neither a turquoise pond nor grey stone triggers the magenta despill, which fires only when red and blue both exceed green by 40 — so do not add them to `CYAN_PREFIXES`.

- [ ] **Step 4: Verify the routing before spending GPU time**

```bash
cd tools/art && ~/.cache/expense-garden-art-venv/bin/python3 -c "
import gen
for n in ('koi_pond','stone_lantern','tulip_0','house_0'):
    print(n, gen.screen_of(n), gen.style_of(n).split('.')[0][:46])
"
```

Expected: both landmarks print `magenta` and a style beginning `2D game sprite for a cozy casual tower-defense-style mobile game` from `LANDMARK_STYLE` — check it contains "GARDEN FEATURE"; `tulip_0` shows `cyan`; `house_0` shows `magenta` and the building block.

- [ ] **Step 5: Generate**

```bash
~/.cache/expense-garden-art-venv/bin/python3 tools/art/gen.py koi_pond stone_lantern
```

Expected: two `saved .../koi_pond.png` / `stone_lantern.png` lines and **no** `WARNING ... the chroma screen was NOT keyed out`. Verify the files themselves, never the exit code — 1C.6 saw a "completed exit 0" that masked a failed `cd`.

- [ ] **Step 6: Check for chroma residue**

```bash
~/.cache/expense-garden-art-venv/bin/python3 tools/art/check_residue.py
```

Expected: `checked 54 sprites, 0 with residue`.

- [ ] **Step 7: Review over black — the check nothing automates**

```bash
~/.cache/expense-garden-art-venv/bin/python3 tools/art/contact_sheet.py koi_pond stone_lantern --out /tmp/landmarks.png
```

Open `/tmp/landmarks.png` and look. Reject and re-roll with `SEED_OFFSET=7` if you see: a face or eyes on either object, a baked patch of sky in a corner, or blobs in the alpha map that do not touch the object.

**One exception to the usual rule:** a soft ellipse under the pond is correct art here, not a baked ground plane. `tools/art/README.md` records that two statistics were tried as an automatic gate for that defect and both cried wolf on good sprites — this stays a human judgement.

If a re-roll is needed and the concept is what drifted rather than the style, reconcile the brief first. Re-rolling a seed re-rolls the same conflict.

- [ ] **Step 8: Confirm the sprites are reachable**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.render.RareSpriteLoadTest
```

Expected: PASS. `every_shipped_landmark_sprite_is_reachable_through_the_structure_map` now asserts on real files instead of skipping.

- [ ] **Step 9: Commit**

```bash
git add tools/art/briefs.py tools/art/gen.py \
        app/src/main/assets/garden/koi_pond.png \
        app/src/main/assets/garden/stone_lantern.png
git commit -m "feat: koi pond and stone lantern sprites with their own style block"
```

---

## Task 7: Verify on device

- [ ] **Step 1: Run both suites**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
```

Expected: `failures=0 errors=0`, 270 tests.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew connectedDebugAndroidTest
```

Expected: 67 tests, 0 failures.

- [ ] **Step 2: Install — last, and alone**

The instrumented run just uninstalled both APKs.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
~/Library/Android/sdk/platform-tools/adb shell pm list packages | grep expensegarden
```

Expected: `package:com.expensegarden.app`. If it is missing, `installDebug` reported success while doing nothing because Gradle still believed the app was installed — run `./gradlew uninstallDebug installDebug`.

- [ ] **Step 3: Look at a landmark on the island**

The fold derives house level from months tracked, so a clean emulator shows level 1 and no landmarks. Force the level instead of trying to log a year of history: `foldAllTime` already accepts `houseLevelOverride`, which `RareFoldTest` uses.

Temporarily pass `houseLevelOverride = 4` at the `GardenRepository.observeAllTimeGarden()` fold call, rebuild, and look:

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB shell am start -n com.expensegarden.app/.MainActivity
$ADB exec-out screencap -p > /tmp/island.png
```

Use `am start` with the explicit activity — `monkey -c LAUNCHER` silently no-ops on this emulator.

Confirm on screen: the pond sits left of the house and the lantern right of it, both at roughly the house's own height; neither is clipped by the house sprite; no plant is standing on either plot.

**Then revert the override.** It is a debugging aid, not a change — `git diff` must show it gone before Step 4.

- [ ] **Step 4: Final state check**

```bash
git status --short
```

Expected: clean, apart from the spec and plan documents. Those stay uncommitted unless Rajdweep says otherwise.

Do **not** `git push`. Ask first.

---

## Deferred

Recorded so they are decisions rather than omissions:

- **Tapping a landmark.** No interaction anywhere in this phase.
- **Animated water or a lit lantern.** The island's motion vocabulary (sway, breathe, drift) is per-plant and landmarks are props. If the pond reads as dead once it is on screen, that is a follow-up with evidence behind it.
- **A third landmark.** The house ladder tops out at level 4; a third has nothing to earn it.
- **Refactoring `GardenCanvas`.** It is 1001 lines around one ~790-line composable. Real, and not this phase's work.
- **Landmarks lerping during the 1C.7 expansion tween.** The grove does not either; matching it keeps one behaviour instead of two.

---

## Execution log — 2026-09-06

All 7 tasks executed inline. Six commits, `70296cc..90f2169`. Final state: **270 JVM + 67
instrumented tests, 0 failures**; both landmarks verified rendering on the emulator at house
level 4.

Three defects, all introduced by this plan and all caught by its own tests. Recorded because
each was a category of mistake rather than a typo.

**1. Task 1 — `capacity()` sized the island, `tiles()` still planted on the plot.**
The plan changed `capacity` to subtract the reserved landmark tiles and stopped there. Every
capacity assertion passed, and `landmark plots are never planted` failed: the tiler grew the
island to fit the reservation and then planted on it anyway, because `tiles()` builds its skip
set from `backyardTiles` alone. **Reserving space and refusing to use it are two separate
mechanisms.** Step 3 now covers both.

**2. Task 1 — there are two tiler test files, and the plan's Expected line only knew about one.**
The spec claimed no existing tiler test would change, verified by grepping `SpiralTilerTest`.
`SpiralTilerFootprintTest` also exists, parameterises `f` over `{2, 3, 4}`, and asserts the
pre-4B identity `side² − f² − 4`. It failed, correctly. The useful detail is *which* rows: f=2
passed for every k and f=3 was the first failure, which is exactly the signal that the
reservation had not leaked into the 1C.6 case. Step 4b now handles it.

**3. Task 2 — the defect being fixed was overstated, and had to be retracted.**
The plan (and the spec, and a commit message, and a message to Rajdweep) claimed `pickLandmark`
collided roughly half the time, leaving the stone lantern unreachable. Checked against the real
hashes during implementation: `seedFrom` is `scopeKey.hashCode()`, `"house:3"` and `"house:4"`
differ in one character so their hashes differ by exactly 1, and `% 2` therefore *always* returns
opposite indices. Both landmarks always shipped reachable. The change stands — the correctness
was accidental, and the fold needs a stable ordinal to index against `landmarkTiles` regardless —
but the commit verb went from `fix:` to `refactor:` and all four places were corrected.

**One plan prediction that held exactly:** the landmark sprite test was vacuous through Tasks 4
and 5 and became a real assertion at Task 6 Step 8, as designed.

**One flag deliberately not acted on.** `stone_lantern` measures outside the cast's colour band
(median saturation 20.5 against a p10 of 45; 50% dark-ink pixels against a p90 of 35). Judged on
the island instead of against the percentile: beside the tan villa its grey reads as a deliberate
ornament and the amber glow carries the warmth. A stone lantern is grey — a subject difference,
not a style one. Judging sprites against percentiles in isolation is what produced false
positives on `odd_mushroom_0` and `bell_flower_2` the same day.
