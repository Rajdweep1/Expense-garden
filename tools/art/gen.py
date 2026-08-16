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

MFLUX = "mflux-generate"
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
    key_out(raw, screen).save(out)
    print("saved", out)


if __name__ == "__main__":
    os.makedirs(RAW_DIR, exist_ok=True)
    os.makedirs(OUT_DIR, exist_ok=True)
    wanted = sys.argv[1:] or sorted(PROMPTS)
    for n in wanted:
        if n not in PROMPTS:
            sys.exit("unknown sprite: " + n)
        gen(n, PROMPTS[n])
