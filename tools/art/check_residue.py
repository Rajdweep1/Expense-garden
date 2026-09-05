#!/usr/bin/env python3
"""Flag chroma-key residue in generated sprites.

`gen.py` keys the shoot background out with a flood fill from the border, so a background
pocket fully ENCLOSED by the character — between two leaves, inside a curl — is never reached
and survives as bright screen colour. It is small, easy to miss by eye across a dozen sprites,
and unmistakable once it renders on the island.

Two things keep this from crying wolf, both learned the hard way on a first pass that flagged
eight perfectly good shipped sprites:

  * Only the screen a sprite was ACTUALLY shot on is checked. `screen_of` mirrors gen.py.
    Otherwise a periwinkle bell reads as cyan residue and a pink tulip as magenta.
  * Detection keys on the CHANNEL THAT CANNOT SURVIVE, not on brightness. The screens are
    (255, 0, 255) and (0, 255, 255), so residue always has one channel at essentially zero.
    Shading darkens a leftover pocket — the sliver that prompted this script measured
    (182, 1, 121) — so a brightness threshold misses exactly the pixels that are hardest to
    spot by eye. No natural pigment holds green at 1 while red sits at 182.

Finding, 2026-09-06: this flags six sprites that shipped in 1C.6 — hedge_1 (1.3%),
odd_mushroom_0, thistle_weed_0, vegetable_row_1, zombie_0, zombie_2. They are not enclosed
pockets but thin fringe around the silhouette, and the values cluster at (245, 3, 128) /
(239, 11, 128): magenta after gen.py's despill halves the blue channel. At sprite scale it
reads as a faint pink halo rather than a blob, which is why it went unnoticed. Fixing it means
improving the despill and re-rolling that art — deliberately NOT done as part of 4A.

Usage:  python3 check_residue.py [file.png ...]     (default: every sprite in assets/garden)
"""
import os
import sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "garden")

# Mirrors gen.py. Kept in sync by hand — it is two entries, and importing gen.py would pull in
# mflux and a 9GB model just to read a tuple.
CYAN_PREFIXES = ("tulip", "berry_bush")

def screen_of(name):
    return "cyan" if name.startswith(CYAN_PREFIXES) else "magenta"

def residue(path):
    name = os.path.basename(path).removesuffix(".png")
    screen = screen_of(name)
    im = Image.open(path).convert("RGBA")
    hits = 0
    for r, g, b, a in im.get_flattened_data() if hasattr(im, "get_flattened_data") else im.getdata():
        if a < 128:
            continue
        # The dead channel is the tell. Requiring the other two to be substantial keeps
        # near-black shadow pixels (where every channel is low) from counting.
        if screen == "magenta" and g < 25 and r > 100 and b > 70:
            hits += 1
        elif screen == "cyan" and r < 25 and g > 100 and b > 70:
            hits += 1
    return hits, screen, im.size[0] * im.size[1]

def main(argv):
    files = argv[1:] or sorted(
        os.path.join(ASSETS, f) for f in os.listdir(ASSETS) if f.endswith(".png")
    )
    bad = []
    for path in files:
        hits, screen, total = residue(path)
        # A few dozen pixels is antialiasing along a keyed edge; an enclosed pocket is hundreds.
        if hits > 100:
            bad.append(os.path.basename(path))
            print(f"RESIDUE  {os.path.basename(path):24} {hits:6d} {screen} px ({hits / total:.3%})")
    print(f"checked {len(files)} sprites, {len(bad)} with residue")
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
