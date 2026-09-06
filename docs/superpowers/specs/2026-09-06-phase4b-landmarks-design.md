# Phase 4B — Landmarks on the Island: Design

**Status:** approved in conversation 2026-09-06.

**Goal:** Put the two earned Landmarks — the koi pond and the stone lantern — on the island,
so the tier that rewards a year of persistence stops being an album entry and becomes
something you see every time you open the app.

**Parent spec:** `docs/superpowers/specs/2026-09-06-phase4a-collections-design.md` §8, which
specified the Landmark *triggers* so the earning engine was built once, and deferred rendering
because "a pond is not a grid cell, and `SpiralTiler` has no concept of placing anything
outside its tiling."

---

## 1. The hook that makes this cheap

4A recorded the blocker as geometry work. It is smaller than that, because of a coincidence in
the existing code that turns out not to be a coincidence at all:

| House level | `footprint()` | Months tracked | Landmark earned |
|---|---|---|---|
| 1, 2 | 2 | 0, 3 | — |
| 3 | 3 | 6 | first |
| 4 | 4 | 12 | second |

Landmarks are earned at levels 3 and 4. Those are **exactly** the levels where the house
footprint grows. A footprint change already re-tiles the whole island — it is the one moment
`fitHome`'s invariant permits plants to move, since ring growth is specifically designed never
to shift a plant on screen.

So reserving tiles for a landmark is free precisely when a landmark is earned, and would be
expensive at any other time. The design hangs off that.

It also means the landmark count needs no new state and no new parameter: it is a function of
the footprint the tiler is already being handed.

```kotlin
fun landmarkCount(f: Int): Int = maxOf(0, f - 2)      // f=2 → 0, f=3 → 1, f=4 → 2
```

## 2. Where a landmark stands

Two reserved tiles flanking the house block, in the same spirit as the four backyard tiles that
already hold the investment grove.

```kotlin
fun landmarkTiles(side: Int, f: Int): List<Tile>      // (lo-1, lo-1) then (lo+f, lo+f)
```

A `List`, not a `Set` like `houseTiles` and `backyardTiles`. Those two are pure membership
tests — is this tile plantable — and a set says that precisely. Landmark tiles need membership
*and* order, because index *i* is the plot for landmark ordinal *i* (§3), and a set would drop
exactly the information the placement depends on. At two elements the `in` check costs nothing.

The specific tiles are load-bearing and were chosen by rendering, not by reasoning. In this
projection **screen-x tracks `(row + col)` and screen-y tracks `(col − row)`**, so a tile flanks
the house at its own height only when `col − row` matches the house centre's. The obvious-looking
pair `(lo, lo-1)` and `(lo, lo+f)` fails that test: it puts the two landmarks on opposite
diagonals, one visually behind the other. The pair above sits on the house's own diagonal
(`row == col`), two x-units outside its left and right screen corners, and reads as symmetric.

Ordering is deliberate. The first landmark takes the left plot, and the right plot is not
reserved until the second is earned — so there is never an empty plot advertising a reward that
has not arrived. The album already does the teasing, with tiered silhouettes and
"keep tracking — 6 months, then 12".

**Verified exhaustively:** across house levels 1–4 and plant counts 0–399, the landmark tiles
never overlap the house block, never overlap the grove, and never fall outside the grid. The
right-hand tile lands one column past the grove's end, so the lantern stands beside the trees
rather than among them.

**Capacity** subtracts `4 + landmarkCount(f)` where it currently subtracts a hardcoded 4.

### 2.1 One accepted consequence

At level 3 the right-hand tile is still plantable, so a plant may be standing there when level 4
reserves it. That plant relocates. It costs nothing extra — the level-4 re-tile happens anyway
because `f` changes from 3 to 4 — but a plant does move, and that is accepted rather than
designed around.

## 3. A defect in shipped 4A code that this must fix

```kotlin
fun pickLandmark(seed: Long): RareSpecies =
    LANDMARKS[(seed.hashCode().toLong().absoluteValue % LANDMARKS.size).toInt()]
```

`house:3` and `house:4` each pick **independently** from the pool. That happens to be correct
today, and only by luck: `seedFrom` is `scopeKey.hashCode()`, the two keys differ in one
character, so their hashes differ by exactly 1 and `% 2` always yields opposite indices.

**Corrected during implementation.** An earlier draft of this section claimed the two collided
about half the time and that the stone lantern was often unreachable. That was wrong — checked
against the real hashes, `house:3` resolves to `LANDMARKS[1]` and `house:4` to `LANDMARKS[0]`,
every time. Both landmarks ship reachable.

What is true is that the correctness rests entirely on the pool being exactly two *and* the
levels being adjacent. Each of these breaks it, none of them loudly:

- non-adjacent levels — `house:3` and `house:6` share a parity and collide;
- a third landmark — the pool size changes and the parity argument evaporates;
- renaming the scope key format.

A landmark would then simply never appear, with nothing thrown and nothing logged.

Assignment becomes **ordinal**: landmark earns sorted by scope key, index 0 → koi pond,
index 1 → stone lantern. Deterministic, collision-free, and genuinely a pure fold — which the
hash approach was only accidentally.

This moves resolution from a single `Earn` to the point where the whole set of earns is known,
because "which landmark is this" is a question about the set, not about one element.
`Earn.landmarkSpecies` is replaced by an ordinal lookup performed in the fold.

## 4. Rendering

`SpriteLoader` keys on `(Archetype, Int)`, and a landmark has no archetype — landmarks are the
only `RareSpecies` whose `spriteName` falls back to its own id. They therefore need a parallel
`Map<String, ImageBitmap>` keyed by species id, loaded from `garden/koi_pond.png` and
`garden/stone_lantern.png`.

Landmarks draw at the house block's depth, exactly as the grove already does. Both are reserved
plots adjacent to the house; both belong in the same depth band, drawn with the homestead rather
than interleaved into the per-plant sort.

`GardenCanvas` is 1001 lines around a single ~790-line composable. This phase does **not**
refactor it — that is unrelated work. But the landmark drawing lands as its own `DrawScope`
extension beside `house()` and `shoreShambler()`, not as another inline block, so it does not
add to the pile.

## 5. Art

Two sprites through `tools/art`, and they need a **third style block**.

`STYLE` opens with "cute cartoon plant creature with an oversized head and huge glossy eyes …
standing on a small soil mound". That clause is why `BUILDING_STYLE` exists: it put googly eyes
on the hut during the 1C.6 pilot. A koi pond is ground geometry and a stone lantern is a prop;
neither is a creature and neither stands on a mound. Reusing `STYLE` would reproduce a bug the
project has already paid for once.

Both sprites are reviewed under the rules `tools/art/README.md` now records — composited over
black before acceptance, since a baked ground plane or a leftover patch of sky is close to
invisible against the checkerboard and there is no statistic that catches it.

A pond is also the one asset where a cast shadow would be *correct* art rather than a defect,
which is worth stating explicitly so the review does not reject it on sight.

## 6. Testing

**JVM** (`SpiralTilerTest`, `RareEngineTest`, `RareFoldTest`):

- `landmarkTiles` never overlaps the house block, the grove, or the grid edge, across levels 1–4
  and a range of plant counts. This is the exhaustive check from §2, kept as a test.
- `landmarkCount` is 0, 0, 1, 2 for levels 1–4.
- Capacity accounts for the reserved landmark tiles, and `rings`/`gridSide` agree with it.
- Ordinal assignment: levels 3 and 4 yield **different** species. This is the §3 regression and
  the reason the phase touches `RareCatalog` at all.
- The fold is deterministic across repeated calls with landmarks present.

There are **two** tiler test files, and they are affected differently:

- `SpiralTilerTest` should not change at all. Every assertion in it uses the default `f = 2`,
  where `landmarkCount(2)` is 0 and the capacity formula is untouched — the same property that
  let 1C.7 grow the house footprint without disturbing 1C.6 behaviour.
- `SpiralTilerFootprintTest` **does** change, in exactly one place. Its capacity test
  parameterises `f` over `{2, 3, 4}` and asserts `side² − f² − 4`; that identity gains the
  landmark plots and becomes `side² − f² − 4 − landmarkCount(f)`.

**The tripwire is the f = 2 rows specifically.** A failure there means the reservation leaked
into the 1C.6 case and the code is wrong. A failure at f = 3 or f = 4 in the capacity identity is
the intended new behaviour — those tiles really are reserved now.

(An earlier draft of this section claimed no tiler test would change. That was verified by
grepping one of the two files, and it was wrong.)

**Instrumented** (`RareSpriteLoadTest`):

- Every shipped landmark sprite is reachable through the id-keyed map. A mis-keyed landmark
  fails exactly as silently as a mis-keyed rare did — no throw, no log, just a missing reward —
  which is why 4A added this test in the first place.

## 7. Scope

**In:** the two reserved tiles, the capacity change, the ordinal species fix, the id-keyed
sprite path, the landmark draw call, two sprites, and the tests above.

### 7.1 Out of scope

- **Tapping a landmark.** No interaction. YAGNI.
- **Animated koi, rippling water, a lit lantern.** The island's existing motion vocabulary
  (sway, breathe, drift) is per-plant; landmarks are props. If the pond looks dead once it is on
  screen, that is a follow-up with evidence behind it, not a guess now.
- **Any third landmark.** The ladder tops out at house level 4; a third has nothing to earn it.
- **Retroactive awards.** Unchanged from 4A §8.1.
- **Refactoring `GardenCanvas`.** Noted in §4, deliberately not done here.
