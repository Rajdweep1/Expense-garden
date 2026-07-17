# 1C.6 Design — Homestead (Center House, Spiral World, Zombies, PvZ-Inspired Art)

Approved through brainstorming on 2026-07-17 (Task 16 LOOK checkpoint rounds 4–5).
Amends the 1C spec (`2026-07-06-phase1c-garden-design.md`) and the 1C.5 living-world
amendment: the all-time island keeps growing forever, but its shape, anchor, regret
representation, and art direction change as described here.

## 1. Scope

Four connected changes to the garden world, gated by a 3-sprite style pilot:

1. **Topology** — the island becomes a square that grows in chronological rings
   around a fixed 2×2 **house** block at its center (replaces the serpentine ribbon
   for the home world; greenhouse postcards keep the serpentine monthly beds).
2. **The house** — the "point of authority" (Fortune City townhall analog). Levels
   up with months tracked.
3. **Zombies** — a regretted purchase's plant dies and rises as a zombie-plant on
   its own tile; marking it worth-it revives it. Replaces the weed representation
   for the regret trigger only.
4. **Art direction** — the whole sprite pack moves to a PvZ-inspired cartoon
   language (outlines, faces, cel shading) with original characters.

Out of scope: combat/defense gameplay, zombie pathing into the garden, additional
buildings, any DB/schema/event change, LLM involvement.

## 2. Decisions at a glance

| Decision | Choice |
|---|---|
| Island shape | Square, ring growth around center house; `SpiralTiler` replaces `SerpentineTiler` in the all-time fold only |
| House block | 2×2 tiles at grid center, reserved (never planted) |
| Backyard | The 4 ring-1 tiles directly behind the house, reserved for the investment grove (trees move from off-grid rim to here) |
| House leveling | `monthsTracked` = distinct `yyyy-MM` months (device zone) containing ≥1 LOGGED txn. L1 hut 1–2, L2 cottage 3–5, L3 brick house 6–11, L4 villa 12+ |
| Zombie trigger | `regret == REGRET && !ownNecessity` → `Archetype.ZOMBIE` (overrides category archetype). `breachedAtLogging`-only keeps today's thistle/mushroom weeds. Necessities can never zombify (existing guard) |
| Zombie size | Variant forced by amount tier: `zombie_0/1/2.png` = S/M/L |
| Redemption | Worth-it → one-shot revival burst, living plant returns (existing regret-clear data flow, new animation) |
| Shore shamblers | Optional last layer: 1–2 zombies on the waterline during DROUGHT only, outside the island |
| Art rule | Clone the PvZ *style system*, never a specific character — see §3 recognition test |
| Pilot gate | Petal flower (face) + zombie live on device, house on composite mock, before full-pack redraw or topology work |
| Chronology | Oldest plants hug the house; rings grow outward; month signposts keep the "first plant of month" mechanism |
| Camera | Home position = house center at all sizes; ring growth must not visually move the house |

## 3. Art direction — PvZ-inspired, original cast

Goal (user's words): characters that strongly resemble the PvZ look and trigger the
nostalgia, without copying any actual character or asset.

**Style DNA — clone this hard.** These are the elements that make a sprite read
"PvZ-era" and none of them belong to a specific character:

- Thick dark outlines (~6–8 px at 512 canvas), closed silhouettes.
- Oversized heads / faces relative to body; every character has a face.
- Big white eyes with small dark pupils (X or spiral eyes for the undead).
- Wide simple mouths — beaming, dopey, or deadpan. Goofy, never gory.
- 2–3 tone cel shading with a warm rim light; saturated garden palette.
- Squash-and-stretch idle poses (lean, bob, petal flop).

**Archetype casting — where the nostalgia lives.** Our cast occupies the same
*niches* the player remembers: a beaming sunflower-ish face on the petal flower, a
hunched vacant-eyed zombie among the plants, a cheerful suburban house behind a
lawn of tiles. Niche + style DNA = instant recognition, no copying required.

**The twist rule — what keeps it ours.** Every character carries at least one
signature element PvZ does not have (the zombie's crumpled-receipt hat is the
canonical example), and we never reproduce a specific character's identifying
combination (petal count + face + pose of Sunflower; cone/bucket/tie props on
zombies; the PvZ house silhouette).

**Recognition test (pilot acceptance rule).** Show a sprite to someone who played
PvZ. Pass: "this looks like a PvZ-style game." Fail: "that's Sunflower / that's
the PvZ zombie, redrawn." Applied at the pilot checkpoint and again on the full
pack contact sheet.

**Pipeline.** SVG-first: evolve the existing `art_pass.py`-style scripted pipeline
(sources in `docs/assets/sprite-src/`, resvg → 512 px PNGs in
`app/src/main/assets/garden/`). All 21 existing sprites are redrawn in the new
language after the pilot passes; loader contract (`<archetype>_<variant>.png`) is
unchanged. Escalation path if the hand-drawn ceiling disappoints: the same
per-sprite briefs feed an AI-generated raster pack that drops into the same files.

## 4. World topology — `SpiralTiler`

Pure object in `game/`, same contract shape as `SerpentineTiler` (plant index →
tile), used by `foldAllTime` only. `foldMonth` (greenhouse) keeps serpentine.

- Grid is square. `rings(n)` = smallest k ≥ 1 with capacity ≥ n; side = `2 + 2k`.
- Reserved tiles: the centered 2×2 house block, plus the 4 ring-1 tiles on its
  back edge (backyard). Reserved tiles are never assigned to plants.
- Capacity: ring k holds `4 + 8k` tiles; ring 1 loses its 4 backyard tiles.
  Cumulative capacity `C(k) = 4k² + 8k − 4` (C(1)=8, C(2)=28, C(3)=56, C(4)=92).
  The current 62-plant demo DB → k=4, a 10×10 island (vs 13×5 today).
- Fill order: chronological (occurredAt, uuid — same sort as today). Ring 1 first,
  then ring 2, etc. Within a ring: start directly in front of the house (an even
  side means two candidate front tiles — the plan pins the exact start index with
  a determinism test) and proceed clockwise. Partial rings simply end mid-arc, so
  growth visibly creeps around the island.
- Month markers: unchanged mechanism — the tile of each month's first plant. On a
  spiral the signposts wind outward around the house.
- Investments still plant no tile; they grow the grove (now in the backyard, up to
  3 trees, trunk tiers unchanged).

**Camera.** Default view centers the house at every island size; pan is bounded by
`islandRect`; zoom bounds unchanged (`WORLD_MIN_ZOOM` may need retuning for square
islands at the plan stage). Frontier pinning and the growth-glide compensation die
with the ribbon: when a ring is added the house must stay visually fixed (origin
math anchors the house block's world position, or pan is compensated in the same
frame — plan decides, test enforces "house does not move on growth").

**Row culling** carries over (it operates on screen-space rows, independent of the
tiler). Speckles, foam, grade, fauna, weather are untouched.

## 5. The house

- `GardenState` gains `houseLevel: Int` (1..4), derived in the fold from
  `monthsTracked` (distinct months with ≥1 LOGGED txn, any category — an
  investment-only month still counts as tracked).
- Thresholds: 1–2 → L1 hut, 3–5 → L2 cottage, 6–11 → L3 brick house, 12+ → L4
  villa. Tunable at the checkpoint; encode as constants with tests.
- Sprites `house_0.png` … `house_3.png`, drawn spanning the 2×2 block footprint
  (isometric-friendly base, front door facing the viewer). Loaded through a new
  structures map in `SpriteLoader` (keyed by name, alongside the archetype map);
  no procedural fallback — the house ships with the pilot.
- The grove trees render on the 4 backyard tiles behind the house: your SIPs
  literally shelter your home. One monument per driver: house = months tracked,
  grove = investments.

## 6. Zombies

- **State mapping (in `PlantMapper`).** Split today's weed rule:
  `regret == REGRET && !ownNecessity` → `Archetype.ZOMBIE`, variant = tier ordinal
  (S/M/L). `breachedAtLogging && regret != REGRET && !ownNecessity` → thistle/odd
  mushroom weeds exactly as today. Necessity purchases never weed or zombify.
- **No new state anywhere.** The zombie is a render mapping of the existing regret
  flag inside the pure fold. No schema change, no new events, no migration.
- **Look:** grey-green wilted plant risen from its tile, X eyes, crumpled-receipt
  hat, size follows the tier of what died.
- **Idle shamble:** master-clock bob/sway with an occasional lurch, same pattern as
  the fauna clocks. At most 4 zombies animate (nearest the viewport center); the
  rest draw a static frame.
- **Revival:** the canvas remembers the previous zombie tile-set; when a tile
  leaves the set but still holds a plant, play a one-shot burst (~0.8 s scale pop +
  color flash + petal particles), then the normal living plant. Never punish the
  log: redemption is celebrated, loudly.
- **Interactions:** tap opens that transaction's sheet (tile identity unchanged).
  Bees ignore zombies (they only target flower archetypes). Zombie tiles count in
  nothing else — leaf sums, severity, and budgets read transactions, not sprites.
- **Shore shamblers (stacked last, optional):** during DROUGHT only, 1–2 zombies
  walk the waterline outside the slab — the month's overspend at the border. They
  never enter the garden and despawn when severity improves. If the pilot tone
  feels too jokey for this, we drop it without touching anything else.

## 7. Pilot gate and sequencing

**Pilot (first deliverable, LOOK checkpoint round 5):**

1. Redraw `petal_flower_0` in the new language (outline + face + cel shading) —
   it is the most common sprite on screen.
2. Draw `zombie_0/1/2`; wire the `PlantMapper` split so the demo DB's existing
   regrets rise live **in the current serpentine world** (topology untouched for
   the pilot — the mapper split is small and permanent either way).
3. Draw `house_0`; judged on a composite mock (contact sheet + pasted in-scene
   shot), since live placement needs `SpiralTiler`.

Pass criteria: the recognition test (§3) plus the user's verdict on device.

**After the pilot passes:** `SpiralTiler` + camera (TDD) → house rendering +
leveling → revival animation + shamble → full 21-sprite redraw → shore shamblers.
Fail → iterate art only; no topology work happens before the art direction is
proven.

## 8. Testing

- `SpiralTilerTest` (new): capacity formula C(k); reserved tiles never assigned;
  deterministic fill order; ring/side derivation; chronological adjacency (plant
  i+1 is on the same or next ring).
- `PlantMapperTest`: zombie override beats category archetype; tier → variant;
  necessity immunity; breach-only still weeds; regret+breach → zombie (regret
  wins).
- `GardenFolderTest`: `monthsTracked` counting (investment-only month counts;
  same-month txns count once); `houseLevel` thresholds; markers on spiral tiles.
- `IsoMath`/`CameraMath`: house-centered framing regimes for square grids; "house
  does not move when a ring is added" invariant.
- `SpriteNamesTest`: zombie/house file names.
- Instrumented suites must stay green untouched (no DB or DAO changes).

## 9. Invariants upheld

- **Never punish the log.** The zombie represents a state the user already opted
  into (regret verdict) exactly where the weed stood; necessities are immune;
  redemption is instant, data-driven, and celebrated with the revival burst.
- **game_event append-only, no schema change.** Everything here is derived render
  state inside the pure fold.
- **Local-first, offline, ₹0.** Assets ship in the APK; no network anywhere.
- **Money is paise as Long.** Tier mapping reuses the existing thresholds.

## 10. Open questions

- House level thresholds (§5) — confirm or retune at the pilot checkpoint.
- Shore shamblers in or out — decide after seeing the pilot's tone.
- Greenhouse postcards deliberately keep the old style and no house for now;
  revisit only if the contrast feels wrong after the full-pack redraw.
