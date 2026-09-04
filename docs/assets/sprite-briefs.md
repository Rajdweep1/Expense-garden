# Sprite briefs — 1C.6 creature cast (AI-raster pipeline)

Approved at LOOK round-5 verdict 3 (2026-07-18): full creature cast, brief-driven
generation, PopCap-adjacent soft-shaded finish, ORIGINAL characters only.

## How these briefs are used

Each image = SHARED STYLE BLOCK + the character brief + VARIANT line (if any).
Generator: FLUX.1-schnell via `mflux` on Apple Silicon, q4-quantized copy at
`~/.cache/mflux-models/flux1-schnell-q4`, 4 steps, creatures at 768 px and houses
at 1024 px. Then chroma-key matting by border flood-fill, gentle despill, autocrop,
pad square, resize 512 px, save to `app/src/main/assets/garden/<archetype>_<variant>.png`
(loader contract unchanged). Script: `tools/art/gen.py` — **in the repo**, because the
session scratchpad is cleaned between sessions and has eaten it twice.

Warm-palette sprites (tulips, berries) shoot on a CYAN screen; everything else on
MAGENTA. Magenta despill destroys legitimate pink and red — it bleached the tulips
white before this rule existed.

This file is the human-readable source of truth; `tools/art/briefs.py` is the
executable mirror. Keep them in sync — this is the copy that survives a wipe.

## Shared style block (prepended to every prompt)

> 2D game sprite for a cozy casual tower-defense-style mobile game. Cute cartoon
> plant creature with an oversized head and huge glossy eyes, soft airbrushed
> shading, rounded chunky volumes, thick clean dark-brown outlines, vibrant
> saturated colors, warm rim light from the upper left, standing on a small soil
> mound. Single character, full body, centered, isolated on a solid bright
> {screen} background. No text, no watermark, no logo. No drop shadow, no cast
> shadow, no shadow ellipse on the ground.
> This must be an ORIGINAL character design. It must NOT depict or resemble
> Peashooter, Sunflower, Wall-nut, Crazy Dave, or any Plants vs. Zombies character.

`{screen}` is substituted per sprite — magenta by default, cyan for warm palettes.

The zombie brief swaps "plant creature" for its own subject line but keeps every
other clause. Houses use a separate BUILDING_STYLE block that drops every face
clause entirely — the shared creature block put googly eyes on the hut during the
1C.6 pilot.

## Palette consistency pass

`gen.py` normalizes every `house_*` sprite's median saturation to **70** (`HOUSE_TARGET_SAT`),
scaling saturation only — hue and lightness are untouched. The houses' hues already agree
(~28° across the ladder); it was richness alone that drifted.

The ladder had gone hut 72 / cottage 72 / two-story 56 / castle 51, so the upper two read as
washed out beside the vivid creature cast. The Acceptance section below has always asked for a
"consistency pass across the pack" — this makes it automatic instead of remembered. Applied to
the four shipped houses on 2026-09-04.

## Casting sheet

| File(s) | Character | Brief core |
|---|---|---|
| petal_flower_0/1/2 | **Sunny** (Food & Drinks) | Sun-faced flower creature: giant beaming face wearing a ruff of rounded golden petals, brown disc face with rosy cheeks, wide open happy smile with tongue, curving green stem, two fat paddle leaves. v1: soft pink petals, winking. v2: white daisy petals, shy closed-mouth smile. |
| tulip_0/1/2 | **Diva** (Shopping) | Plump tulip-bulb-headed creature: glossy pink bulb as the whole head, heavy-lidded eyes with elegant lashes, confident smirk, tiny beauty mark, graceful curving stem, one sweeping leaf posed like a hand on a hip. v1: deep red bulb, chin up. v2: violet bulb, eyes closed, nose in the air. |
| bell_flower_0/1 | **Jingle** (Entertainment) | Bluebell-headed creature: drooping bell blossom as a hat over its head, brim shading big cheerful eyes, mouth open mid-song, slender stem swaying. v1: periwinkle two-tone, eyes closed happily humming. |
| herb_tuft_0/1 | **Sprig** (Personal) | Small grass-tuft gremlin: body of spiky green blades like wild hair, big round eager eyes peeking out, buck-tooth grin. v1: sage-grey blades with a tiny pink flower clip. |
| bush_0/1 | **Bumble** (Misc) | Big round bush creature: fluffy cloud-shaped green body, enormous friendly grin with tongue, big glossy eyes, small white berries dotted in its foliage. v1: red berries, goofier grin. |
| hedge_0/1/2 | **Warden** (necessities: Groceries/Housing/Family) | Stout barrel-shaped evergreen sentinel: thick sturdy rounded body of dense trimmed foliage, small calm patient eyes under heavy dignified brows, tiny content smile, neat leaf bowtie. Dignified and unbothered, never silly. v1 (Rent landmark): taller, grander, columnar. v2 (Utilities landmark): squat and square-shouldered with a small lightning-bolt-shaped sprout on top. |
| perennial_shrub_0/1 | **Trundle** (Transport/Health) | Sturdy round shrub creature: dense leafy ball body on a thick little trunk, reliable easy smile, flower-button details, planted-firm stance. v1 (Fuel landmark): amber-orange leaf tips. |
| tree_0/1 | **Elder** (Investments, grove) | Wise old fruit-tree creature: broad trunk with kind sleepy eyes and a gentle grandfather smile in the bark, full round canopy, small golden fruits. v1: blossom-pink canopy. |
| thistle_weed_0 | **Scruff** (weed) | Scraggly thistle punk: spiky awkward purple-green tuft, guilty sideways glance, sheepish grimace. |
| odd_mushroom_0 | **Dozer** (weed) | Droopy dusty-purple mushroom creature: lopsided cap slid over one eye, half-asleep confused expression, tiny yawn. |
| zombie_0/1/2 | **The Regret** | Cartoon zombie garden-person freshly risen out of the soil: grey-green skin, big round skull with a heavy brow, mismatched wide white eyes with tiny pupils, dislocated open jaw with a few crooked teeth, torn olive-brown t-shirt with a stitched fabric patch, shredded blue-grey trousers, both arms stretched forward with limp dangling fingers, a crumpled white paper RECEIPT (red scribble on it) stuck on its head like a little hat, soil clods at its feet, one small buzzing fly. Goofy and harmless, not scary or gory. Extra negatives: no suit, no necktie, no traffic cone, no bucket. 0: tiny toddler-sized. 1: standard shambler. 2: big heavy bruiser, wider jaw, both arms fully out. |
| house_0..3 | **The Homestead** | Cozy storybook garden dwelling viewed from the front at a slight three-quarter angle suiting an isometric game, warm cream walls, front door facing viewer-left, round window, potted flower by the door, same outline + soft-shading style, on a soil base. 0: small thatched-roof hut with a stitched thatch patch. 1: stone cottage with a chimney. 2 (revised 1C.6): a GRAND TWO-STORY house — brick walls, tall shuttered windows on both floors, a steep gabled roof, a chimney. 3 (revised 2026-09-04): a GRAND CASTLE MANOR — symmetrical honey-stone facade flanked by two crenellated corner towers, a central arched gatehouse under a carved stone hood, mullioned arched windows, stone quoins. Rajdweep's pick after the villa re-rolls: he wanted something between a stately villa and a classical manor, referencing medieval castles. TWO hard clauses, each naming a past failure: (a) castle overhangs — machicolations, corbels, bartizans — are forbidden by name, because overhangs are exactly what left see-through bands across the previous villa; (b) the background clause is negative ("NO sky, NO gradient, NO horizon"), because a positive "isolated on magenta" was ignored and FLUX painted a sunset, leaving nothing for the keyer to remove. NOTE: `facade_gaps` reports ~38 rows for this sprite and that is correct — the gaps are the notches between the towers, which show only sky on the island. |

## 1C.7 additions

Five new archetypes splitting mappings that previously shared a look: three necessity
roots that all grew `HEDGE`/`PERENNIAL_SHRUB`, and two Food & Drinks subcategories that
all grew `PETAL_FLOWER`. These use the full shared STYLE block, faces included — the
whole 1C.6 cast is faced, and a faceless plant reads as an outsider next to Sunny and
Warden. The brief core below describes the body; the face comes from the style block.

| File(s) | Character | Brief core |
|---|---|---|
| vegetable_row_0/1 | **Patch** (Groceries) | A low neat row of three plump round cabbages with crinkled blue-green outer leaves, chunky and sturdy. v1: leafy dark-green chard with bright pale stems, broad ruffled leaves fanning outward. |
| succulent_0/1 | **Nurse** (Health) | A single aloe rosette, thick tapered blue-green paddles radiating from the center, matte waxy surface, calm and sculptural. v1: compact jade succulent, rounded fleshy leaves in tight clusters on short stems. |
| berry_bush_0/1 | **Kin** (Family) — **CYAN screen** | A rounded compact bush of small dark-green leaves studded with clusters of bright red berries, cheerful and generous. v1: deep purple-blue berries. Shot on cyan, not magenta: magenta despill would bleach the berries exactly as it bleached the tulips. |
| curl_vine_0/1 | **Dash** (Delivery) | A single coiled green vine spiralling upward in loose curls, small heart-shaped leaves along its length, springy and quick-looking — something that arrived. v1: tighter corkscrew curls with a few tiny pale tendrils. |
| chai_cluster_0/1 | **Kettle** (Chai & Snacks) | A low tight cluster of many tiny round cream-and-amber buds on short green stems, small and numerous. v1: pale-green buds, slightly taller sprigs at the center. |

## Acceptance

Recognition test unchanged (spec §3): "looks like it belongs in that genre" passes;
"that's <specific PvZ character>" fails. Consistency pass across the pack (same
outline weight, palette temperature, eye style) before integration; regenerate
outliers with the same prompt + a consistency note rather than accepting drift.
