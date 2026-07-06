# Phase 1C Design — The Garden (Event Fold + Isometric Canvas Renderer)

**Date:** 2026-07-06 · **Status:** awaiting user approval · **Parent spec:** `2026-07-03-expense-garden-design.md` (§5.3, §9)
**User-validated via visual companion:** garden takes over the home surface; art direction = **open isometric diamond-tile field** (Fortune-City-style "3Dish" = flat-vector shapes at isometric projection with depth sorting — no 3D engine).

## 1. Scope

**In:** `game/` fold module (events → GardenState, pure Kotlin), `render/` module (Compose Canvas isometric renderer), **sprite-based flora** from an AI-generated asset pack (authored by Rajdweep from a written asset brief; CC0 packs as fallback source) with procedural flat-vector painters as dev-placeholder and permanent fallback, home-surface takeover (garden IS home; recent list relocates to dashboard), weather from budget health, month-end archival + greenhouse album-lite, streaks-lite (no-spend days, under-budget streak, `streak.hit`), `month.closed` emission via an idempotent on-open reconciler, plant tap → detail/regret dialog, investment back-row trees (cumulative, never reset monthly).

**Graphics bar (user-set):** lively and fluid at Fortune City level or better. "FC-style" decomposes into two halves — *ambient motion* (drifting clouds, per-plant sway, butterflies, pop-in springs, breathing sun, weather moods — all code, all in 1C core) and *asset detail* (the sprite pack). The animated companion sample (2026-07-07) fixed the motion language; the sprite track raises the detail ceiling.

**Deferred, with reasons:**
- Roaster character sprite → 1D (its body and its AI voice belong in one plan; the gate already speaks in text).
- Compost/fertilizer redemption, rare-species unlocks, collections → Phase 4 per parent spec ("gamification depth"). §9.1's redemption principle is still honored in 1C: weather recovers as soon as health does, and weeds flip back if a regret is cleared.
- Pinch-zoom/pan camera, rain particle systems → polish later; fixed camera + weather tint/sway now.
- FC-import retro months (1E) need no special work — the fold renders any month it finds data for; imported months become album entries automatically.

## 2. Decisions at a glance

| Topic | Decision | Alternatives rejected |
|---|---|---|
| Fold architecture | **Pure fold-on-read**: `GardenFolder` recomputes GardenState from events + ledger joins every time; no world-state tables, no v3 migration | Materialized `plant` tables (dual-write consistency risk, migrations, stateful fold); hybrid snapshot-per-archived-month (recorded as the escape hatch if personal-scale perf ever hurts — it shouldn't: a month is a few hundred events) |
| Determinism | Procedural variation (hue/scale/x-jitter) seeded from **txn uuid hash**, never `Random()` — every re-fold renders the identical garden | Persisting jitter (needs the tables we just rejected) |
| Plant placement | Chronological serpentine tiling: sort by `(occurredAt, uuid)`, fill diamond tiles front-to-back; grid grows by rows as the month fills | Hash-placed tiles (gaps/collisions); random (unstable across folds). Backdating inserts mid-sequence and shifts later plants one tile — acceptable, gardens change overnight |
| Weather source | **Live month stats** (same `MonthStatsFolder` numbers as the dashboard): overall severity OK/PACE/BREACH → SUNNY/OVERCAST/DROUGHT; archived months freeze at their final-state severity | Deriving weather from crossing events (can't recover when health recovers — violates redemption); the crossing events remain the historical record for future animation/digests |
| Time-based events | `GameEventReconciler` runs once per app-foreground: appends missing `month.closed` (previous months) and `streak.hit` (thresholds 3/7/14/30 days) idempotently — the local-first answer to "no server, no cron" | Background WorkManager job (new dependency, overkill for on-open needs) |
| Renderer | Single `GardenCanvas` composable; painter's algorithm (back-to-front by tile row); idle sway via one `infiniteTransition` | AndroidView/SurfaceView (unneeded); per-plant composables (defeats Canvas batching) |
| Flora rendering | **Sprite asset pack behind the `PlantPainter` seam** (user's call: procedural ceiling sits below the FC bar). Staged: procedural painters ship first so nothing blocks on art; sprites land as a skin swap judged at the look checkpoint. Sky, tiles, shadows, particles, butterflies stay procedural — motion belongs in code | Procedural-only (below the wanted bar); spriting the sky/tiles too (finicky tiling, kills the cheap weather/motion layers) |
| Asset pipeline | Written **asset brief** (sprite list, 2:1 iso camera, top-left key light, palette, 512px transparent PNGs, bottom-center anchor, no baked shadows) → Rajdweep generates via free image AI (parent spec blesses AI packs) or CC0 fallback (e.g. Kenney) → PNGs committed under `app/src/main/assets/garden/` → `SpritePainter` loads `ImageBitmap`s once, draws with tier scaling | Bundling paid art (₹0 rule); drawable resources (assets/ keeps density handling explicit and files swappable without recompiling resource ids) |
| Home restructure | Garden full-bleed; translucent stats strip top (spent · hint → dashboard); pending-confirm card + FABs overlay with existing spring animations; greenhouse icon top-left | Keeping recent list on home (fights the scene); bottom tabs (nav stays as-is) |

## 3. `game/` module (pure Kotlin, JVM-tested)

**Inputs to `GardenFolder.fold(monthKey, txns, categories, budgets, events, today)`:** LOGGED transactions of the month (by `occurredAt`), category tree, that month's budget rows, the month's game_events, and today's date (for streak/no-spend derivation of the live month).

**GardenState:** `monthKey`, `weather` (SUNNY/OVERCAST/DROUGHT), `plants: List<Plant>`, `backRowTrees: List<Tree>` (cumulative investments across all history), `butterflies` (dodges this month, capped 5), `streakDays`, `noSpendDays`, `archived`.

**Plant:** `txnUuid`, `archetype`, `sizeTier` (S < ₹100 ≤ M < ₹1000 ≤ L, on paise), `isWeed`, `tile(row,col)`, `seed` (uuid hash).

**Mapping rules (parent spec §9.3, verbatim where it speaks):**

| Ledger fact | Garden |
|---|---|
| Any LOGGED txn in month | A plant on the next serpentine tile — always (never punish the log) |
| Necessity category (flag on category, root of chain) | Hedge/perennial archetypes — dignified at any size |
| Discretionary within budget | Flower/fruit archetypes; species keyed by parent-category id, size by amount |
| Weed rule | `discretionary AND (breachedAtLogging OR regret == REGRET)` → thistle/odd-mushroom archetypes. Regret cleared → re-folds back to a flower |
| Investments (category 10 subtree) | Back-row trees, cumulative across months; trunk thickens with SIP count |
| Overall severity now (live) / at month end (archived) | Weather: SUNNY → OVERCAST → DROUGHT (sky + soil tint only — mood, never destruction) |
| Day with zero LOGGED txns (fully past, today excluded) | `noSpendDays` +1 → sparkle accents |
| Consecutive fully-past days each ending with cumulative spend ≤ that day's pace allowance | `streakDays`; thresholds emit `streak.hit` |
| `gate.dodged` event in month | A butterfly visits (capped at 5 visible) — the reward the 1A gate promised ("the game rewards it later"); dodging a bad payment is the one act that plants nothing yet still brightens the garden |

**Regret state** comes from the txn row (already re-taggable in 1B); the regret events stay the historical record.

**`GameEventReconciler`** (repository-level, on app foreground): decide-then-append, both idempotent by scanning existing events (`month.closed` payload `{month, spentPaise, overallBudgetPaise?}` once per elapsed month; `streak.hit` payload `{days, month}` once per threshold per streak run). Pure decision function JVM-tested; the append is a thin DAO call.

## 4. `render/` module

- **`IsoMath`** (pure, unit-tested): `(row, col) → screen Offset` for 2:1 diamonds (tileW 2×tileH), z-index = row+col, grid-bounds → canvas-fit scaling.
- **`GardenCanvas`**: sky gradient by weather (warm / grey / dusty), sun or clouds, tile field with front-edge soil wall (raised-bed depth), plants drawn back-to-front with ellipse shadows, back-row trees on the horizon line, sparkle accents on no-spend/streak state, gentle sine sway (one `infiniteTransition`, phase-offset per plant seed). New-plant pop-in: scale-in animation for uuids that appeared since the previous composition.
- **`PlantPainter`** (the seam): `interface PlantPainter { fun DrawScope.draw(plant: Plant, anchor: Offset, scale: Float, swayRadians: Float) }` with two implementations:
  - `ProceduralPainter` — ~10 archetype draw functions (petal flower, tulip, bell flower, herb tuft, bush, hedge, perennial shrub, sapling→tree, thistle weed, odd mushroom), seed-jittered hue/scale/lean. Ships first; permanent fallback for any sprite the pack lacks.
  - `SpritePainter` — decodes the asset-pack PNGs from `assets/garden/` once into cached `ImageBitmap`s; draws base-anchored with tier scaling and canvas-rotation sway (slight bitmap rotation around the anchor reads as wind). Falls back per-archetype to `ProceduralPainter` when a file is missing, so a partial pack still renders a full garden.
  - Both honor the same anchor/scale/sway contract, so motion, hit-testing, and mechanics are painter-agnostic.
- **Asset brief** (a committed doc, written during the plan): exact sprite inventory (10 archetypes + 2–3 weed variants + tree trunk stages), style guide (2:1 isometric camera, top-left key light, soft cartoon shading, FC-adjacent pastel palette chips), format rules (512×512 transparent PNG, subject fills ~80% height, bottom-center anchor at the stem base, no baked ground shadow — the renderer draws shadows). Rajdweep generates the pack with any free image AI; CC0 packs are the fallback source.
- **Interaction:** tap hit-test (nearest tile by inverse IsoMath) → plant detail dialog: payee, amount, category, date + the existing Worth it/Regret chips (retag re-folds live — a weed can bloom back on the spot).

## 5. UI restructure

- **Home = `GardenHomeScreen`**: full-bleed `GardenCanvas`; translucent top strip (month spent odometer + hint, tap → dashboard — the 1B skeleton behavior carries over); greenhouse icon (top-left) → album; pending-confirm card and the two FABs float above the garden with their existing spring animations.
- **Dashboard** gains the **Recent** section (rows + regret dialog move from home unchanged).
- **`GreenhouseScreen`** (album-lite): vertical list of archived month cards — each a small frozen `GardenCanvas` render (same fold, smaller viewport) + month name + spent/budget line; tap → full-screen frozen bed. Months with no data don't appear.
- Navigation: `garden` (start), `dashboard`, `entry`, `greenhouse`; existing transitions retained (entry sheet spring, default fades).

## 6. Testing & the look checkpoint

- **JVM:** `GardenFolder` (every mapping-table row incl. weed flip on regret clear, investment cumulation, weather from stats, serpentine stability under backdated insert, jitter determinism = same input → identical state), `IsoMath` (projection, z-order, fit), reconciler decision function (idempotence, threshold edges), streak/no-spend derivation (month boundaries, today-exclusion).
- **Instrumented:** reconciler end-to-end (emits once, re-run adds nothing), fold-over-real-DB integration (seeded txns → expected plant count/weeds).
- **Renderer:** no pixel tests; emulator screenshot smoke per UI task, plus **two user checkpoints**: (1) *motion checkpoint* on the procedural placeholder — synthetic full garden (flowers, hedge, weed, tree, drought sky, sway/clouds/pop-in) approved before the home takeover lands; (2) *look checkpoint* on the sprite pack once Rajdweep's generated PNGs are wired — this is where "FC or better" gets judged. If the pack disappoints, the brief iterates while the procedural garden keeps shipping.
- Full 1A+1B suites stay green (46 JVM + 20 instrumented untouched).

## 7. Dependencies & data

**No new libraries, no schema change** (fold-on-read needs no tables). New DAO queries only: LOGGED txns between bounds (full rows), events by type, events between bounds. `game_event` stays append-only; the reconciler only appends. The sprite pack is static PNGs under `app/src/main/assets/garden/` — repo-committed art, not a dependency; decoding uses the platform's `BitmapFactory` via Compose's `ImageBitmap`, nothing added to the version catalog. External input: the pack itself comes from Rajdweep (free image AI from the brief, or CC0) — the only step in Phase 1 that needs his hands mid-plan besides checkpoints.

## 8. Invariants upheld

Money paise-`Long` (size tiers compare paise). `game_event` append-only — the garden is a pure function of history and re-folds identically (uuid-seeded jitter). Never punish the log: logging always plants; weeds come only from pre-payment breach or explicit regret; necessities can never be weeds; redemption = regret-clear re-blooms + weather recovers with health. Gate untouched (offline, quip cache). Local-first: everything computes on-device from Room.

## 9. Open questions

None blocking. Roaster species (crow vs gnome) deliberately parked for 1D. Plant-archetype fine-tuning happens at the look checkpoint.
