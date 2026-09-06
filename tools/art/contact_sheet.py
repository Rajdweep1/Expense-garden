#!/usr/bin/env python3
"""Build a contact sheet for eyeballing sprites — the review step nothing can automate.

The cast is dark-outlined art normally viewed on a light checkerboard, and against that a
baked cast shadow, a ground plate or a leftover patch of sky is nearly invisible. Over BLACK
they jump out. Three of 4A's rare sprites shipped with a baked ground plane after passing
every automated gate the pipeline has; all three were obvious the moment they were composited
over black beside their siblings.

Two statistics were tried as a gate for that defect and both failed to separate the known-bad
sprites from good art — see the README. This is the check that works, so it gets a command
rather than a paragraph asking someone to remember.

Each sprite is shown three ways: over the usual checkerboard (how it reads in the app), over
black (where pale background survives), and as an alpha map with partial pixels in red (where
floating debris disconnected from the character shows up).

Usage:
    python3 tools/art/contact_sheet.py                     # the whole cast
    python3 tools/art/contact_sheet.py tulip_3 tulip_4     # just these
    python3 tools/art/contact_sheet.py --family tulip      # a family, ordinary and rare
    python3 tools/art/contact_sheet.py --out /tmp/x.png    # default: ./contact_sheet.png
"""
import argparse
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "garden")
CELL = 190


def checker(img, size=10):
    out = Image.new("RGBA", img.size, (250, 250, 250, 255))
    for y in range(0, img.size[1], size):
        for x in range(0, img.size[0], size):
            if (x // size + y // size) % 2:
                out.paste((222, 222, 232, 255),
                          (x, y, min(x + size, img.size[0]), min(y + size, img.size[1])))
    out.alpha_composite(img)
    return out


def on_black(img):
    out = Image.new("RGBA", img.size, (0, 0, 0, 255))
    out.alpha_composite(img)
    return out


def alpha_map(img):
    """White = solid, red = partial, black = clear. Debris shows as a blob off the body."""
    out = Image.new("RGBA", img.size, (0, 0, 0, 255))
    src, dst = img.load(), out.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            a = src[x, y][3]
            dst[x, y] = (255, 255, 255, 255) if a >= 200 else (255, 60, 60, 255) if a else (0, 0, 0, 255)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("names", nargs="*")
    ap.add_argument("--family", help="prefix, e.g. tulip — shows every variant of it")
    ap.add_argument("--out", default="contact_sheet.png")
    args = ap.parse_args()

    have = sorted(f[:-4] for f in os.listdir(ASSETS) if f.endswith(".png"))
    if args.family:
        names = [n for n in have if n.startswith(args.family + "_")
                 and n[len(args.family) + 1:].isdigit()]
        names.sort(key=lambda n: int(n.rsplit("_", 1)[1]))
    else:
        names = args.names or have
    missing = [n for n in names if n not in have]
    if missing:
        raise SystemExit("no such sprite: " + ", ".join(missing))

    sheet = Image.new("RGBA", (CELL * 3 + 4, (CELL + 18) * len(names)), (245, 245, 245, 255))
    draw = ImageDraw.Draw(sheet)
    for row, name in enumerate(names):
        img = Image.open(os.path.join(ASSETS, name + ".png")).convert("RGBA")
        top = row * (CELL + 18)
        for col, view in enumerate((checker(img), on_black(img), alpha_map(img))):
            sheet.paste(view.resize((CELL - 6, CELL - 6), Image.LANCZOS), (col * CELL + 3, top + 3))
        draw.text((4, top + CELL), f"{name}    checker | over black | alpha", fill=(20, 20, 20, 255))

    sheet.convert("RGB").save(args.out)
    print(f"{len(names)} sprites -> {args.out}")
    print("Look for: a pale plate or ellipse under the plant, sky in the corners, and blobs "
          "in the alpha map that do not touch the character.")


if __name__ == "__main__":
    main()
