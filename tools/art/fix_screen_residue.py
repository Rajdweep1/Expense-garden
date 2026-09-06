#!/usr/bin/env python3
"""Repair chroma-screen residue in sprites that shipped before gen.py handled pockets.

`gen.py` keys the screen out with a border flood-fill. A patch of screen fully ENCLOSED by
the character — between two leaves, inside a curl, under a mushroom cap — is walled off from
the border, so the fill never reaches it and it survives into the shipped PNG. 4A taught
gen.py a second pass for this, but the pass runs at generation time; the cast shipped in
1C.6/1C.7 still carries its pockets, and re-rolling that art is not an option (see below).

This repairs the SHIPPED PNGs in place. It never crops, resizes, re-pads or re-generates:
every pixel outside the residue keeps its exact value, so the island looks like itself.

WHY NOT JUST RE-RUN gen.py
    The files in tools/art/raw/ do NOT reliably correspond to what was shipped. Re-keying
    the cast from raw/ on 2026-09-06 produced a house_3 with a pale halo the committed one
    does not have — 24k opaque pixels ADDED, i.e. a different render. Regenerating also
    rolls new art, which is not a fix for a keying defect.

WHY NOT gen.py's ENCLOSED-POCKET PASS
    That pass seeds strictly and then grows with the LOOSE background test. On a raw render
    that is right: the background is uniform and only its thin blended rim needs the loose
    test. On a shipped sprite — already despilled, already downscaled with LANCZOS — the
    loose test escapes the pocket and eats art. Measured on 2026-09-06: it grew
    odd_mushroom_0's 2,053 residue pixels into 9,599 and cut a gash through the cap; it
    chewed both of thistle_weed_0's eyes open; it deleted curl_vine_0's flower bud. So here
    growth is not loose. A pixel is residue or it is not, judged on its own colour.

TWO TREATMENTS, AND WHY ONE RULE CANNOT SERVE BOTH
    CLEAR    the residue is background trapped behind the character — a gap between leaves.
             The honest result is a hole: you should see the island through that gap.
    DESPILL  the residue is a SHAPE the model drew in the screen colour, enclosed by the
             character's own outline. Deleting it punches a hole through drawn art, so the
             screen tint is pulled out and the shape survives.
    Nothing in the pixels distinguishes these — both are enclosed patches of screen colour.
    It is a judgement about what the region depicts, so each sprite below was classified by
    eye at 4x and carries its reason.

Usage:  python3 tools/art/fix_screen_residue.py                # repair in place
        python3 tools/art/fix_screen_residue.py --out-dir DIR  # write copies instead
Verify: python3 tools/art/check_residue.py                     # must report 0
"""
import argparse
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "garden")

CYAN_PREFIXES = ("tulip", "berry_bush")   # mirrors gen.py

# Every sprite this tool will touch, and what the residue in it actually is. Anything not
# listed is refused: this is a repair for a known defect in known files, not a filter to run
# over the cast. Counts are the residue pixels measured on 2026-09-06 at 512x512.
TREATMENT = {
    # --- background trapped between leaves; a hole is the correct result ---
    "hedge_1":         ("clear",   "3366px of screen in the leaf gaps at the base"),
    "thistle_weed_0":  ("clear",   "1244px wedge behind the stem, left of the trunk"),
    "bush_1":          ("clear",   "939px slab between the body and a foreground leaf"),
    "bush_0":          ("clear",   "448px sliver under the mouth, between stem leaves"),
    "chai_cluster_1":  ("clear",   "264px scattered between the berries"),
    "zombie_0":        ("clear",   "263px at the hand and between the base sprouts"),
    "zombie_2":        ("clear",   "260px in the crook of the raised arm"),
    "vegetable_row_1": ("clear",   "191px in the leaf gaps along the bottom row"),
    # --- shapes drawn in the screen colour; clearing them would hole the character ---
    "odd_mushroom_0":  ("despill", "2053px of gill striation under the cap. Clearing cuts "
                                   "a speckled gash through the rim and into the face."),
    "curl_vine_0":     ("despill", "828px flower bud at the foot of the curl. Clearing "
                                   "leaves a drawn outline with nothing inside it."),
}


def screen_of(name):
    return "cyan" if name.startswith(CYAN_PREFIXES) else "magenta"


def is_screen(screen, r, g, b):
    """The dead channel is the tell, exactly as check_residue.py explains it.

    The screens are (255, 0, 255) and (0, 255, 255), so residue always has one channel at
    essentially zero while the other two stay strong. No pigment holds green at 1 while red
    sits at 182 — and shading darkens a pocket without ever reviving its dead channel, which
    is why this cannot be a brightness test.
    """
    if screen == "cyan":
        return r < 25 and g > 100 and b > 70
    return g < 25 and r > 100 and b > 70


def spill(screen, r, g, b):
    """Estimated screen contamination in a pixel, in channel units.

    Magenta is white minus green, so it contaminates red and blue together; whatever they
    both hold above the untouched green channel is screen, not pigment. Cyan is the mirror.
    A negative result means the colour is naturally warm (or cool) and carries no spill.
    """
    return (min(g, b) - r) if screen == "cyan" else (min(r, b) - g)


def suppress(screen, r, g, b):
    """Subtract the full estimated spill from the two screen channels.

    Full, not a fraction: a partial despill deliberately leaves a known-wrong tint, and any
    fraction would be a constant nobody could later justify. It is also self-limiting —
    a pixel with no spill is returned untouched.
    """
    s = spill(screen, r, g, b)
    if s <= 0:
        return r, g, b
    if screen == "cyan":
        return r, max(0, g - s), max(0, b - s)
    return max(0, r - s), g, max(0, b - s)


def repair(path, treatment):
    name = os.path.basename(path).removesuffix(".png")
    screen = screen_of(name)
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    px = img.load()

    hits = [(x, y) for y in range(h) for x in range(w)
            if px[x, y][3] >= 128 and is_screen(screen, *px[x, y][:3])]

    # The blended rim around a pocket is part screen, part art, so it fails the test above
    # and survives as a pink outline once the pocket is gone — the "faint halo" that made
    # this defect easy to miss. Despill it: art in the rim has little or no spill and comes
    # back unchanged. Collected BEFORE any edit so the ring is the original neighbourhood.
    hitset = set(hits)
    ring = {(x + dx, y + dy) for x, y in hits for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
            if 0 <= x + dx < w and 0 <= y + dy < h
            and (x + dx, y + dy) not in hitset and px[x + dx, y + dy][3] != 0}

    for x, y in hits:
        r, g, b, a = px[x, y]
        px[x, y] = (0, 0, 0, 0) if treatment == "clear" else (*suppress(screen, r, g, b), a)

    touched_ring = 0
    for x, y in ring:
        r, g, b, a = px[x, y]
        nr, ng, nb = suppress(screen, r, g, b)
        if (nr, ng, nb) != (r, g, b):
            px[x, y] = (nr, ng, nb, a)
            touched_ring += 1

    return img, len(hits), touched_ring


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default=None, help="write copies here instead of in place")
    ap.add_argument("names", nargs="*", help="subset of the sprites listed in TREATMENT")
    args = ap.parse_args()

    wanted = args.names or sorted(TREATMENT)
    unknown = [n for n in wanted if n not in TREATMENT]
    if unknown:
        raise SystemExit(
            "not a known-defective sprite: " + ", ".join(unknown) + "\n"
            "Add it to TREATMENT only after looking at the region at 4x and deciding whether "
            "it is background (clear) or drawn art (despill). Guessing here holes a sprite."
        )
    if args.out_dir:
        os.makedirs(args.out_dir, exist_ok=True)

    print(f"{'sprite':18} {'treatment':10} {'residue':>8} {'rim':>5}")
    for name in wanted:
        treatment, why = TREATMENT[name]
        src = os.path.join(ASSETS, name + ".png")
        img, residue, rim = repair(src, treatment)
        dst = os.path.join(args.out_dir, name + ".png") if args.out_dir else src
        img.save(dst)
        print(f"{name:18} {treatment:10} {residue:8d} {rim:5d}   {why.splitlines()[0][:44]}")


if __name__ == "__main__":
    main()
