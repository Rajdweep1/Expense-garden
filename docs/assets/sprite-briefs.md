# Sprite briefs — 1C.6 creature cast (AI-raster pipeline)

Approved at LOOK round-5 verdict 3 (2026-07-18): full creature cast, brief-driven
generation, PopCap-adjacent soft-shaded finish, ORIGINAL characters only.

## How these briefs are used

Each image = SHARED STYLE BLOCK + the character brief + VARIANT line (if any).
Generator: Gemini free-tier image model (`gemini-2.5-flash-image`), one image per
prompt, then chroma-key matting (magenta), autocrop, pad square, resize 512 px,
save to `app/src/main/assets/garden/<archetype>_<variant>.png` (loader contract
unchanged). Script: session scratchpad `gen_sprites.py`.

## Shared style block (prepended to every prompt)

> 2D game sprite for a cozy casual tower-defense-style mobile game. Cute cartoon
> plant creature with an oversized head and huge glossy eyes, soft airbrushed
> shading, rounded chunky volumes, thick clean dark-brown outlines, vibrant
> saturated colors, warm rim light from the upper left, standing on a small soil
> mound. Single character, full body, centered, isolated on a solid bright magenta
> background (#FF00FF). No text, no watermark, no logo.
> This must be an ORIGINAL character design. It must NOT depict or resemble
> Peashooter, Sunflower, Wall-nut, Crazy Dave, or any Plants vs. Zombies character.

The zombie and house briefs swap "plant creature" for their own subject line but
keep every other clause.

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
| house_0..3 | **The Homestead** | Cozy storybook garden cottage viewed from the front at a slight three-quarter angle suiting an isometric game, warm cream walls, front door facing viewer-left, round window, potted flower by the door, same outline + soft-shading style, on a soil base. 0: small thatched-roof hut with a stitched thatch patch. 1: stone cottage with a chimney. 2: two-story brick house with shutters. 3: villa with a balcony and flower trellis. |

## Acceptance

Recognition test unchanged (spec §3): "looks like it belongs in that genre" passes;
"that's <specific PvZ character>" fails. Consistency pass across the pack (same
outline weight, palette temperature, eye style) before integration; regenerate
outliers with the same prompt + a consistency note rather than accepting drift.
