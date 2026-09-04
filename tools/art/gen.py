#!/usr/bin/env python3
"""Generate the garden sprite cast locally with FLUX.1-schnell via mflux.

Lives in the repo, NOT the session scratchpad — the scratchpad is cleaned between
sessions and has already eaten this script twice (1C.6 and again during 1C.7 planning).

Usage:
    python3 tools/art/gen.py                  # every sprite in briefs.py
    python3 tools/art/gen.py berry_bush_0     # just these
    SEED_OFFSET=7 python3 tools/art/gen.py    # re-roll with different seeds
"""

import os
import subprocess
import sys
import zlib
from collections import deque

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # runnable from any cwd

from briefs import PROMPTS, STYLE, BUILDING_STYLE  # noqa: E402

# Resolve the CLI next to whichever interpreter is running us, so
# `~/.cache/expense-garden-art-venv/bin/python3 gen.py` works without touching PATH.
_SIBLING = os.path.join(os.path.dirname(sys.executable), "mflux-generate")
MFLUX = _SIBLING if os.path.exists(_SIBLING) else "mflux-generate"
MODEL = os.path.expanduser("~/.cache/mflux-models/flux1-schnell-q4")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join(REPO, "app/src/main/assets/garden")
RAW_DIR = os.path.join(HERE, "raw")
SEED_OFFSET = int(os.environ.get("SEED_OFFSET", "0"))

# Magenta despill destroys legitimate pink and red, so warm-palette sprites shoot on
# cyan instead. 1C.6 learned this when magenta bleached the tulips white; berries carry
# exactly the same risk.
CYAN_PREFIXES = ("tulip", "berry_bush")
SCREENS = {"magenta": (255, 0, 255), "cyan": (0, 255, 255)}


def screen_of(name):
    return "cyan" if name.startswith(CYAN_PREFIXES) else "magenta"


def size_of(name):
    return "1024" if name.startswith("house") else "768"


def style_of(name):
    # Houses are props, not creatures — the shared block's "huge glossy eyes" clause
    # put googly eyes on the hut during the 1C.6 pilot.
    return BUILDING_STYLE if name.startswith("house") else STYLE


def seed_of(name):
    # crc32, NOT hash(): Python randomizes string hashing per process, so hash() would
    # silently produce a different image every run and make re-rolls unreproducible.
    return zlib.crc32(name.encode()) % 100000 + SEED_OFFSET


def key_out(path, screen):
    """Drop the chroma screen by BORDER FLOOD-FILL, not a paint-anywhere colour match.

    This distinction is the whole trick. Baked shadow lobes touch the border, so a
    flood-fill eats them; a warm sprite's pink-drifted interior does NOT touch the
    border, so the fill can never punch holes in it. 1C.6 shipped a bug both ways
    before landing here.
    """
    # Lazy import: keeps prompts, seeds and screen routing introspectable without Pillow.
    from PIL import Image

    img = Image.open(path).convert("RGBA")
    w, h = img.size
    px = img.load()
    br, bgn, bb = SCREENS[screen]

    def is_bg(r, g, b):
        if screen == "cyan":
            fam = g > r + 55 and b > r + 40
        else:
            fam = r > g + 55 and b > g + 40
        return fam or (r - br) ** 2 + (g - bgn) ** 2 + (b - bb) ** 2 < 105**2 * 2

    seen = bytearray(w * h)
    q = deque()
    for x in range(w):
        q.append((x, 0))
        q.append((x, h - 1))
    for y in range(h):
        q.append((0, y))
        q.append((w - 1, y))
    while q:
        x, y = q.popleft()
        if x < 0 or y < 0 or x >= w or y >= h or seen[y * w + x]:
            continue
        r, g, b, _ = px[x, y]
        if not is_bg(r, g, b):
            continue
        seen[y * w + x] = 1
        px[x, y] = (0, 0, 0, 0)
        q.extend(((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))

    # Gentle despill: desaturate whatever screen tint survived on the edges. An
    # aggressive transparent-drop here ate legitimate pixels in 1C.6 — do not "improve" it.
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            if screen == "magenta" and r > g + 40 and b > g + 40:
                m = (r + b) // 2
                px[x, y] = (min(r, m), g, min(b, m), a)
            elif screen == "cyan" and g > r + 40 and b > r + 40:
                m = (g + b) // 2
                px[x, y] = (r, min(g, m), min(b, m), a)

    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
    side = max(img.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(img, ((side - img.width) // 2, side - img.height))
    return square.resize((512, 512), Image.LANCZOS)


def gen(name, prompt_core):
    screen = screen_of(name)
    prompt = style_of(name).format(screen=screen) + " " + prompt_core
    raw = os.path.join(RAW_DIR, name + ".png")
    # mflux UNIQUIFIES its output (name_1.png) rather than overwriting, so a re-roll
    # silently leaves the old file in place unless we clear it first.
    if os.path.exists(raw):
        os.remove(raw)
    subprocess.run(
        [
            MFLUX,
            "--model",
            MODEL,
            "--base-model",
            "schnell",
            "--prompt",
            prompt,
            "--steps",
            "4",
            "--seed",
            str(seed_of(name)),
            "--height",
            size_of(name),
            "--width",
            size_of(name),
            "--output",
            raw,
            "--mlx-cache-limit-gb",
            "8",
        ],
        check=True,
    )
    out = os.path.join(OUT_DIR, name + ".png")
    img = key_out(raw, screen)
    img.save(out)
    print("saved", out)
    if key_failed(img):
        print(f"  WARNING {name}: the chroma screen was NOT keyed out — the sprite is opaque")
        print("  edge to edge. FLUX drew a scene background instead of the flat screen, so the")
        print("  flood-fill had nothing to remove. Re-roll; do NOT ship a baked background.")
    elif name.startswith("house"):
        bad = facade_gaps(img)
        if bad:
            print(f"  WARNING {name}: {bad} rows are >30% see-through across the facade.")
            print("  A building with holes in it shows the garden through its walls and reads")
            print("  as stacked slabs. Re-roll with SEED_OFFSET rather than shipping it.")


def key_failed(img):
    """True when the background survived keying, leaving an opaque rectangle.

    Checked BEFORE facade_gaps, because a fully opaque image trivially has zero gaps —
    total keying failure would otherwise be reported as a clean pass. FLUX occasionally
    ignores the "solid bright {screen} background" clause and paints a sky instead.
    """
    w, h = img.size
    px = img.load()
    corners = [px[2, 2], px[w - 3, 2], px[2, h - 3], px[w - 3, h - 3]]
    return sum(1 for c in corners if c[3] > 40) >= 3


def facade_gaps(img, thresh=0.30):
    """Count rows where the building is substantially see-through side to side.

    Houses are the one archetype that must be a solid mass: they sit at the centre of a
    packed island, so any hole in the silhouette frames a creature's face and the building
    stops reading as one object. Creatures are exempt — gaps between leaves are the point.
    """
    w, h = img.size
    px = img.load()
    spans = []
    for y in range(h):
        xs = [x for x in range(w) if px[x, y][3] >= 40]
        spans.append((xs[0], xs[-1]) if len(xs) >= 2 else None)
    widest = max((hi - lo + 1 for s in spans if s for lo, hi in [s]), default=1)
    bad = 0
    for y in range(h):
        if not spans[y]:
            continue
        lo, hi = spans[y]
        width = hi - lo + 1
        # Only judge the building's BODY. Roof finials — a spire, a cupola, a chimney —
        # legitimately leave sky beside them, and those rows are narrow relative to the
        # facade. Counting them made the gate cry wolf on villas that were solid-walled.
        if width < 0.55 * widest:
            continue
        clear = sum(1 for x in range(lo, hi + 1) if px[x, y][3] < 40)
        # A row that is nearly all clear between two stray edge pixels is padding, not a
        # wall with a hole in it — the square-pad step leaves one antialiased pixel at each
        # extreme of the top and bottom rows.
        if width - clear < 0.10 * widest:
            continue
        if clear > thresh * width:
            bad += 1
    return bad


if __name__ == "__main__":
    os.makedirs(RAW_DIR, exist_ok=True)
    os.makedirs(OUT_DIR, exist_ok=True)
    wanted = sys.argv[1:] or sorted(PROMPTS)
    for n in wanted:
        if n not in PROMPTS:
            sys.exit("unknown sprite: " + n)
        gen(n, PROMPTS[n])
