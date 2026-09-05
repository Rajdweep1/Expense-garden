# 1C.7 — Growing Homestead & Expanded Cast (design)

**Status:** approved by Rajdweep, 2026-08-16
**Predecessor:** [1C.6 Homestead](2026-07-17-1c6-homestead-design.md) — complete as of 2026-08-13 (`cc2a028`)

## Goal

Two things 1C.6 left on the table, both raised by Rajdweep after the house-ladder demo:

1. **The villa reads small next to the plants.** Fortune City makes a town-hall upgrade feel
   like growth by having the building *claim more land*. Ours grows in draw-scale only
   (2.0 → 2.66 tile-widths) while its footprint stays a fixed 2×2. Fix: the footprint grows.
2. **Too many categories grow the same plant.** All 24 creature sprites are unique files, but
   the *mapping* reuses them — three necessity roots share `HEDGE`, two share `PERENNIAL_SHRUB`.
   Fix: new archetypes, split where the reuse actually hurts.

## Decisions taken (and the ones rejected)

| Question | Decision | Rejected |
|---|---|---|
| Plants when the house claims land | **Full re-layout** — plants shuffle outward | Pre-reserve 4×4; pre-reserve + yard art |
| Footprint ladder | **2 → 2 → 3 → 4** | 2→3→4→5; 2→3→3→4 |
| The level-up moment | **Watch it happen** — glide between two folds | Static + card; nothing |
| Where new archetypes go | **Both splits** — necessity + food-volume | Either split alone |

**The re-layout knowingly breaks a 1C.6 invariant.** `IsoMath.fitHome` guarantees plants never
move on screen as the island grows; the proof holds only while the core stays 2×2. Growing the
core genuinely re-tiles every plant. This is accepted because it happens exactly **twice** in
the app's lifetime (6 months and 12 months tracked) and both are milestones worth dramatizing —
"plants never move" was never the goal, "the island doesn't churn under you" was.

---

## §1 · Footprint math (`SpiralTiler`)

One parameter `f` (house side in tiles) threads through every function, defaulting to `2`.
**Existing tests remain valid unchanged as the f=2 case** — they are not rewritten.

```
footprint(houseLevel) = 2, 2, 3, 4        for levels 1, 2, 3, 4

capacity(k, f)        = 4k² + 4fk − 4     // −4 = the grove, always 4 tiles
                                          // f=2 reduces to today's 4k² + 8k − 4
rings(n, f)           = smallest k with capacity(k, f) ≥ n
gridSide(n, f)        = f + 2 · rings(n, f)

houseTiles(side, f)   → lo = (side − f) / 2 ; spans lo..lo+f−1 on both axes
backyardTiles(side,f) → row = lo + f ; 4 columns starting at lo + f/2 − 2
                                          // f/2 is INTEGER division throughout
tiles(n, f)           → ring walk in house-relative coords, lo = −k, hi = f−1+k
```

Derivation of `capacity`: ring *i* around an f×f core has `(f+2i)² − (f+2i−2)² = 4f + 8i − 4`
tiles. Summing i=1..k gives `4fk + 4k²`. Subtract the 4 reserved grove tiles.

**Grove placement verified at each rung:**

| f | side | house cols | grove cols | note |
|---|---|---|---|---|
| 2 | 4 | 1–2 | 0–3 | reproduces today's placement exactly |
| 3 | 5 | 1–3 | 0–3 | one column left-biased |
| 4 | 6 | 1–4 | 1–4 | flush on the house |

The f=3 asymmetry is unavoidable when centering an even-width grove on an odd-width house, and
is invisible in isometric projection. Left-bias is chosen because it reproduces f=2 exactly.

### `IsoMath` requires no changes

The house is always centered, so its center index is `(side − f)/2 + (f − 1)/2 = (side − 1)/2`
— the `f` cancels. `fitHome`, `originX` and `originY` therefore keep working untouched. This is
load-bearing enough to assert in a test (§6).

## §2 · Fold (`GardenFolder`)

`foldAllTime` gains `houseLevelOverride: Int? = null`. Passing the previous level yields a
**fully coherent "before" state** — old level, old footprint, old draw scale together — rather
than a half-updated hybrid. `gridRows`/`gridCols` become `gridSide(mapped.size, f)`.

The monthly `fold()` behind the greenhouse is serpentine, not world-mode, and is untouched.

Month signposts (`monthMarkers`) re-tile along with the plants. Expected: they are pinned to a
plant's tile, so they follow it.

## §3 · The expansion beat

### Persistence

New `GardenPrefs` — a thin `SharedPreferences` wrapper holding one Int, `lastSeenHouseLevel`.

SharedPreferences over Room is deliberate on two grounds. It adds **zero dependencies** (it is
framework, not a library — DataStore would violate the pinned-matrix guardrail). And it is
architecturally correct: "has this device played this animation" is view state, not ledger
truth. In Room it would sync to the Phase 2 Go backend, where it is meaningless and wrong on a
second device. The local-first invariant says Room is the source of truth *for the ledger*;
this is not ledger data.

### Data flow

```
GardenViewModel
  lastSeen = prefs.lastSeenHouseLevel                  // 0 = fresh install
  if (lastSeen == 0) { prefs.write(current); expandFrom = null }
  expandFrom = if (footprint(lastSeen) != footprint(current))
                 foldAllTime(..., houseLevelOverride = lastSeen)
               else null                               // L1→L2: records, never animates
        ↓
GardenCanvas(state, expandFrom)
  Animatable 0→1 over ~1.5s, only when expandFrom != null:
    plant position = lerp(posIn(expandFrom.tile), posIn(state.tile))   keyed by txnUuid
    houseSpan      = lerp(old span, new span)
    house sprite   = crossfade old → new
    island slab    = grows with the lerped side
        ↓  onExpansionShown() → prefs.lastSeenHouseLevel = current
```

A plant present in `state` but absent from `expandFrom` (shouldn't occur — same transactions,
same order) falls back to its new tile with no lerp.

Because level 1→2 shares the 2×2 plot, it records the level and skips the tween entirely.

### Scope boundary

The tween is deliberately limited to position-lerp, span-lerp and crossfade. **No** dust
particles, camera push-in, or staggered per-plant delay. If it reads flat on device, stagger is
a one-line change to the progress mapping and can be added then.

### Fit with existing canvas machinery

`GardenCanvas` already holds `mutableStateMapOf<String, Animatable<Float, *>>` for pop-in and
tap-jiggle, and fires the revival burst from `LaunchedEffect(state.plants)`. The expansion tween
follows that established pattern rather than introducing a new animation idiom.

## §4 · The expanded cast

5 new archetypes, 10 new sprites (2 variants each).

`PlantMapper` gains an `archetypeBySubcat` map consulted **before** the root maps, so a
subcategory can override its root's family.

| Category | id | Archetype | Brief sketch |
|---|---|---|---|
| Groceries | 2 | `VEGETABLE_ROW` *(new)* | low row of cabbages/greens, chunky and leafy |
| Health | 5 | `SUCCULENT` *(new)* | aloe rosette, thick blue-green paddles |
| Family | 9 | `BERRY_BUSH` *(new)* | round bush studded with bright berries |
| Delivery | 102 | `CURL_VINE` *(new, subcat)* | coiled fast-growing vine — something that arrived |
| Chai & Snacks | 103 | `CHAI_CLUSTER` *(new, subcat)* | tiny low buds in clusters — small and frequent |
| Housing | 4 | `HEDGE` | unchanged — the topiary *is* the rent landmark |
| Transport | 3 | `PERENNIAL_SHRUB` | unchanged |
| Restaurants | 101 | `PETAL_FLOWER` | unchanged — the sunflower cast |

Taxonomy after: **16 archetypes, 34 creature sprites** (+ 4 houses = 38 asset files).

`ProceduralPainter`'s `when` over `Archetype` is exhaustive, so new enum values are a compile
error until handled. They are **aliased to the nearest existing procedural look** rather than
given five new hand-drawn fallbacks — the fallback only renders if a sprite fails to load, and
the sprites ship with the change.

Art follows the locked 1C.6 style: original characters from original briefs with explicit
negative prompts, FLUX.1-schnell q4 via `mflux`, border flood-fill keying, no baked shadows.

**Chroma screen per sprite, not per batch.** 1C.6 learned this the hard way — tulips shot on
magenta came out bleached white, because magenta despill destroys legitimate pink. `BERRY_BUSH`
has the same exposure: bright red/pink berries against a magenta screen will despill away. It
shoots on **cyan**, like the tulips. The four green-dominant newcomers (`VEGETABLE_ROW`,
`SUCCULENT`, `CURL_VINE`, `CHAI_CLUSTER`) shoot on magenta as usual. `gen.py` already carries
the per-sprite `screen_of()` switch this needs.

## §5 · Tooling home

`gen.py`, `briefs.py` and `seed_ladder.py` move from the session scratchpad into `tools/art/`
in the repo, and the 10 new briefs are appended to `docs/assets/sprite-briefs.md`.

This closes a hole that cost a rebuild during 1C.6: the session scratchpad **is** cleaned
between sessions, and it took the generator and seeder scripts with it. What survived was
exactly what lived somewhere durable — the committed sprites and the model cache under
`~/.cache`.

## §6 · Testing

| Suite | Coverage |
|---|---|
| `SpiralTilerTest` | existing suite = the f=2 case; add f=3/f=4 capacity, no house/grove overlap, grove placement per the §1 table, `footprint(level)` mapping |
| `IsoMathTest` | house screen position identical across footprints at equal side — the claim §1 rests on |
| `GardenFolderTest` | `houseLevelOverride` yields old level **and** old footprint together; default path unchanged |
| `PlantMapperTest` | subcat archetype wins over root; unmapped subcats still fall through to root; necessity splits map correctly |
| Device | ladder demo re-run, both expansions (6mo, 12mo) watched playing; fresh-install path records without animating |

## Out of scope

- Camera/zoom changes — `fitHome` is untouched, so framing behaviour is unchanged.
- Greenhouse monthly cards — serpentine, not world-mode.
- More zombie variants — noted by Rajdweep as a later want, not part of 1C.7.
- Any change to the gate, ledger, or payment path.
