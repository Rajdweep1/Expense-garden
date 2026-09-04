"""Prompt table for the garden sprite cast.

Mirrors docs/assets/sprite-briefs.md, which is the human-readable source of truth and is
the reason this file could be rebuilt after the scratchpad ate it — twice. Keep them in
sync: the doc is what survives, this is what runs.

Each image = style block (STYLE or BUILDING_STYLE) + the entry below.
{screen} is substituted by gen.py — warm-palette sprites shoot on cyan, not magenta.
"""

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
    "2D game sprite of a small storybook building for a cozy casual mobile game, soft "
    "airbrushed shading, rounded chunky volumes, thick clean dark-brown outlines, "
    "vibrant saturated colors, warm rim light from the upper left, on a soil base. "
    "NO face, no eyes, no mouth — this is a building, not a character. Single building, "
    "full body, centered, isolated on a solid bright {screen} background. No text, no "
    "watermark, no logo. No drop shadow, no cast shadow, no shadow ellipse."
)

_SUNNY = (
    "Sun-faced flower creature: giant beaming face wearing a ruff of rounded golden "
    "petals, brown disc face with rosy cheeks, wide open happy smile with tongue, "
    "curving green stem, two fat paddle leaves."
)
_DIVA = (
    "Plump tulip-bulb-headed creature: glossy pink bulb as the whole head, heavy-lidded "
    "eyes with elegant lashes, confident smirk, tiny beauty mark, graceful curving stem, "
    "one sweeping leaf posed like a hand on a hip."
)
_JINGLE = (
    "Bluebell-headed creature: drooping bell blossom as a hat over its head, brim "
    "shading big cheerful eyes, mouth open mid-song, slender stem swaying."
)
_SPRIG = (
    "Small grass-tuft gremlin: body of spiky green blades like wild hair, big round "
    "eager eyes peeking out, buck-tooth grin."
)
_BUMBLE = (
    "Big round bush creature: fluffy cloud-shaped green body, enormous friendly grin "
    "with tongue, big glossy eyes, small white berries dotted in its foliage."
)
_WARDEN = (
    "Stout barrel-shaped evergreen sentinel: thick sturdy rounded body of dense trimmed "
    "foliage with a flat-topped boxy silhouette, matte leafy surface never smooth or "
    "glossy, small calm patient eyes under heavy dignified brows, tiny content smile, "
    "neat leaf bowtie. Dignified and unbothered, never silly."
)
_TRUNDLE = (
    "Sturdy round boxwood shrub creature: dense leafy ball body clearly wider than tall "
    "on a thick little trunk, reliable easy smile, flower-button details, planted-firm "
    "stance."
)
_ELDER = (
    "Wise old fruit TREE creature: tall broad trunk with kind sleepy eyes and a gentle "
    "grandfather smile in the bark, full round canopy much wider than the trunk, small "
    "golden fruits."
)
_REGRET = (
    "Cartoon zombie garden-person freshly risen out of the soil: a crumpled white paper "
    "RECEIPT with a red scribble on it stuck on its head like a little hat, grey-green "
    "skin, big round skull with a heavy brow, mismatched wide white eyes with tiny "
    "pupils, dislocated open jaw with a few crooked teeth, torn olive-brown t-shirt with "
    "a stitched fabric patch, shredded blue-grey trousers. Exactly two arms attached at "
    "the shoulders, stretched forward with limp dangling fingers. No detached body "
    "parts. Soil clods at its feet, one small buzzing fly. Goofy and harmless, not scary "
    "or gory. No suit, no necktie, no traffic cone, no bucket."
)
_HOMESTEAD = (
    "Cozy storybook garden dwelling viewed from the front at a slight three-quarter "
    "angle suiting an isometric game, warm cream walls, front door facing viewer-left, "
    "round window, potted flower by the door."
)

PROMPTS = {
    # ---- Sunny (Food & Drinks) ----
    "petal_flower_0": _SUNNY,
    "petal_flower_1": _SUNNY + " Soft pink petals, winking.",
    "petal_flower_2": _SUNNY + " White daisy petals, shy closed-mouth smile.",
    # ---- Diva (Shopping) ----
    "tulip_0": _DIVA,
    "tulip_1": _DIVA + " Deep red bulb, chin up.",
    "tulip_2": _DIVA + " Violet bulb, eyes closed, nose in the air.",
    # ---- Jingle (Entertainment) ----
    "bell_flower_0": _JINGLE,
    "bell_flower_1": _JINGLE + " Periwinkle two-tone, eyes closed happily humming.",
    # ---- Sprig (Personal) ----
    "herb_tuft_0": _SPRIG,
    "herb_tuft_1": _SPRIG + " Sage-grey blades with a tiny pink flower clip.",
    # ---- Bumble (Misc) ----
    "bush_0": _BUMBLE,
    "bush_1": _BUMBLE + " Red berries, goofier grin.",
    # ---- Warden (Housing) ----
    "hedge_0": _WARDEN,
    "hedge_1": _WARDEN + " Rent landmark: taller, grander, columnar.",
    "hedge_2": _WARDEN
    + " Utilities landmark: squat and square-shouldered with a small "
    "lightning-bolt-shaped sprout on top.",
    # ---- Trundle (Transport) ----
    "perennial_shrub_0": _TRUNDLE,
    "perennial_shrub_1": _TRUNDLE + " Fuel landmark: amber-orange leaf tips.",
    # ---- Elder (Investments, grove) ----
    "tree_0": _ELDER,
    "tree_1": _ELDER + " Blossom-pink canopy.",
    # ---- weeds ----
    "thistle_weed_0": (
        "Scraggly thistle punk: spiky awkward purple-green tuft, guilty sideways glance, "
        "sheepish grimace."
    ),
    "odd_mushroom_0": (
        "Droopy dusty-purple mushroom creature: lopsided cap slid over one eye, "
        "half-asleep confused expression, tiny yawn."
    ),
    # ---- The Regret (zombies; variant = tier of what died) ----
    "zombie_0": _REGRET + " Tiny toddler-sized.",
    "zombie_1": _REGRET + " Standard shambler.",
    "zombie_2": _REGRET + " Big heavy bruiser, wider jaw, both arms fully out.",
    # ---- The Homestead (house levels; 2 and 3 rewritten in 1C.6 for grandeur) ----
    "house_0": _HOMESTEAD + " A small thatched-roof hut with a stitched thatch patch.",
    # house_1 originally carried no solidity clause at all — it predates the lesson — and
    # shipped with creature faces visible through the wall/base junction and through the notch
    # between the main roof and the porch roof. Same fix as the castle: forbid the overhangs.
    "house_1": _HOMESTEAD + " A STONE COTTAGE with a chimney, a shingled porch roof over the "
    "door built FLUSH against the wall, and a round window."
    " The building is ONE SOLID CONTINUOUS MASS: solid unbroken walls from the roof down to "
    "the ground, the walls meeting the ground with no gap, no open space beneath the roof "
    "eaves or the porch roof, no holes or openings in the walls, nothing see-through anywhere "
    "in the building."
    " The ENTIRE background behind and around the building is ONE FLAT SOLID UNIFORM MAGENTA "
    "COLOR filling the whole frame. No sky, no gradient, no horizon, no landscape.",
    "house_2": _HOMESTEAD + " A GRAND TWO-STORY house: brick walls, tall shuttered "
    "windows on both floors, a steep gabled roof, a chimney.",
    # house_3 is a CASTLE, chosen by Rajdweep 2026-09-04 after the villa re-rolls: he wanted
    # something between a stately villa and a classical manor, referencing medieval castles.
    #
    # Two hard-won clauses here, both naming a specific past failure:
    #  - Castles are full of overhangs (machicolations, corbels, bartizans) and those are
    #    exactly what punched see-through bands through the previous villa. Forbid them.
    #  - FLUX ignored "isolated on a magenta background" and painted a sunset sky, so the
    #    flood-fill had nothing to key. A positive instruction was not enough; the negatives
    #    naming sky/gradient/horizon are what fixed it.
    # Shipped asset = SEED_OFFSET 61 on this prompt.
    #
    # EXPECTED: facade_gaps reports ~38 rows for this sprite. That is CORRECT and must
    # not be 'fixed'. The gaps are the notches between the two towers and the lower
    # central block — inherent to a twin-tower silhouette, and legitimate: you expect to
    # see sky between castle towers. Verified on device: the castle stands tall enough
    # that those notches sit above the island's back edge, so only sky shows through them,
    # never plants. The gate counts transparent pixels and cannot tell a notch from a
    # hole in a wall — the island is the arbiter, not the alpha channel.
    "house_3": (
        "Cozy storybook fortified manor house viewed from the front at a slight three-quarter "
        "angle suiting an isometric game, warm pale honey-stone walls, a heavy arched timber "
        "door with iron studs facing viewer-left, a climbing rose by the door."
        " A GRAND CASTLE MANOR: symmetrical stone facade flanked by TWO square corner towers "
        "with crenellated tops, a central arched gatehouse entrance under a carved stone hood, "
        "rows of tall mullioned arched windows, stone quoins."
        " The building is ONE SOLID CONTINUOUS MASS: crenellated parapets sit directly on top "
        "of the walls, every tower rises from the ground, all walls solid and unbroken from the "
        "battlements down to the ground. NO machicolations, NO corbels, NO overhanging "
        "bartizans, NO projecting balconies, no open space beneath any part of the building, "
        "nothing see-through anywhere."
        " The ENTIRE background behind and around the building is ONE FLAT SOLID UNIFORM "
        "MAGENTA COLOR filling the whole frame. Absolutely NO sky, NO clouds, NO sunset, NO "
        "gradient, NO horizon, NO hills, NO trees, NO landscape or scenery of any kind — "
        "nothing behind the building except flat magenta."
    ),
    # ---- 1C.7 necessity split ----
    "vegetable_row_0": (
        "A low neat row of three plump round cabbages with crinkled blue-green outer "
        "leaves, chunky and sturdy."
    ),
    "vegetable_row_1": (
        "A low row of leafy dark-green chard with bright pale stems, broad ruffled "
        "leaves fanning outward, chunky and sturdy."
    ),
    "succulent_0": (
        "A single aloe rosette, thick tapered blue-green paddles radiating from the "
        "center, matte waxy surface, calm and sculptural."
    ),
    "succulent_1": (
        "A compact jade succulent, rounded fleshy blue-green leaves in tight clusters on "
        "short stems, matte waxy surface."
    ),
    "berry_bush_0": (
        "A rounded compact bush of small dark-green leaves studded with clusters of "
        "bright red berries, cheerful and generous."
    ),
    "berry_bush_1": (
        "A rounded compact bush of small dark-green leaves studded with clusters of deep "
        "purple-blue berries, cheerful and generous."
    ),
    # ---- 1C.7 food-volume split ----
    "curl_vine_0": (
        "A single coiled green vine spiralling upward in loose curls, small heart-shaped "
        "leaves along its length, springy and quick-looking."
    ),
    "curl_vine_1": (
        "A single coiled green vine spiralling upward, tighter corkscrew curls with a "
        "few tiny pale tendrils, springy and quick-looking."
    ),
    "chai_cluster_0": (
        "A low tight cluster of many tiny round cream-and-amber buds on short green "
        "stems, small and numerous."
    ),
    "chai_cluster_1": (
        "A low tight cluster of many tiny round pale-green buds on short stems, small "
        "and numerous, slightly taller sprigs at the center."
    ),
}
