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

---

**Checkpoint note (2026-07-16):** pack v1 (hand-authored SVGs, sources in
`docs/assets/sprite-src/`) approved as-is — no art corrections needed. The Task 16
shortfall was scene motion/depth (fluidity, 3D-ness, liveliness), tracked in the
1C plan's Execution amendments, not in this brief.
