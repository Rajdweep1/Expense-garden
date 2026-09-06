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
    # ================= Phase 4A: rares =================
    # Earned by restraint, never by spending. Each is deliberately the SAME creature as its
    # ordinary form, elevated — a rare must be the plant that purchase would already have
    # grown, or the garden would misreport what was bought (spec §4).
    #
    # These are written STANDALONE rather than as `_BASE + " and it is golden"`. The first
    # attempt did exactly that and produced an ordinary pink tulip: _DIVA opens with "glossy
    # pink bulb as the whole head", and the model weighted that concrete early clause over the
    # appended colour. A rare that looks like its common form defeats the entire collection, so
    # each brief now states its colour once, up front, with the contradicting words removed.

    # ---- Uncommon: "my tulip came up golden" ----
    "tulip_3": (
        "Plump tulip-bulb-headed creature whose entire bulb head is burnished metallic GOLD "
        "with fine engraved veining — golden, not pink, no pink anywhere on the flower. "
        "Heavy-lidded eyes with elegant lashes, confident smirk, tiny beauty mark, graceful "
        "curving green stem, one sweeping leaf posed like a hand on a hip. Soft warm glow "
        "around the golden petals and a few tiny drifting gold motes."
    ),
    "bell_flower_2": (
        "Bluebell-headed creature whose drooping bell blossom is DEEP INDIGO dusted with pale "
        "star-like speckles — indigo and midnight blue, not periwinkle. The bell brim shades "
        "big cheerful eyes, mouth open mid-song, slender stem. Faint cool moonlit glow along "
        "the rim of the bell."
    ),
    "hedge_3": (
        "Neatly clipped dense dark-green topiary hedge creature completely FLUSHED WITH TINY "
        "WHITE BLOSSOMS across its whole surface, like a flowering hawthorn, a few loose "
        "petals drifting off. Calm steady eyes set into the foliage, squared-off dignified "
        "shape, small soil base."
    ),
    "berry_bush_2": (
        "Round compact bush creature bowing under UNUSUALLY LARGE glossy berries in deep ruby "
        "and blackcurrant, short branches heavy and arching with the weight, rich dark-green "
        "leaves, wide pleased eyes, plump and abundant, rooted in a small mound of dark soil."
    ),
    "succulent_2": (
        "Rosette succulent creature with thick pale sage leaves edged in FROSTED SILVER, a "
        "faint pearlescent sheen across the whole plant, cool-toned and plump, calm sleepy "
        "eyes at the center of the rosette."
    ),
    "petal_flower_3": (
        "Sun-faced flower creature whose ruff of rounded petals is DEEP AMBER shading to warm "
        "cream at the tips — amber and honey, not yellow. Brown disc face with rosy cheeks, "
        "wide happy smile, curving green stem, two fat paddle leaves, strong golden-hour glow "
        "behind the head."
    ),
    "chai_cluster_2": (
        "Low tight cluster of many tiny round buds in warm CINNAMON and CLOVE BROWN on short "
        "stems, a few small star-anise-shaped seed pods among them, a faint haze of spice "
        "dust, small bright eyes peeking from the cluster."
    ),
    "vegetable_row_2": (
        "Short row of plump RIPE vegetables — deep orange carrots and rich purple beets with "
        "glossy taut skins, clearly at peak ripeness — full healthy leafy tops, cheerful eyes "
        "on the front vegetable."
    ),

    # ---- Rare: "what IS that" — still the same family, transformed ----
    "petal_flower_4": (
        "Serene LOTUS creature: broad layered pink-and-cream petals opening around a pale "
        "golden center, calm half-closed meditative eyes, gentle smile, sitting on a single "
        "round green lily pad with a little still water beneath."
    ),
    "tulip_4": (
        "NIGHT ORCHID creature: sweeping deep-violet orchid petals with pale speckled throats "
        "instead of a tulip bulb, faint blue-green bioluminescent glow tracing the petal "
        "edges, elegant half-lidded nocturnal eyes, slender dark stem."
    ),
    "herb_tuft_2": (
        "FERN creature with delicate arching feathery fronds instead of grass blades, deep "
        "green, small warm GOLDEN FIREFLY LIGHTS hovering and glowing among the fronds, big "
        "curious eyes peeking through."
    ),
    "bush_2": (
        "Miniature BONSAI TREE creature: a thick gnarled trunk twisting up from a shallow "
        "glazed ceramic pot, crowned by THREE distinct layered cloud-shaped pads of dense "
        "green foliage, a small wise face set into the bark of the trunk with calm closed "
        "eyes, ancient and deliberate. The pot rests on a small mound of dark soil."
    ),
    "hedge_4": (
        "Green hedge clipped into an elegant TOPIARY CRANE — long arched neck, folded wings, "
        "raised beak — unmistakably made of dense clipped foliage with visible leaf texture, "
        "standing on a small soil base, serene expression."
    ),
    "vegetable_row_3": (
        "Short row of rare HEIRLOOM vegetables with unusual patterned skins — green-and-red "
        "striped tomatoes, purple-and-white striped beets, speckled squash — lush leafy tops, "
        "proud little eyes on the front vegetable, growing from a low mound of dark soil."
    ),
}
