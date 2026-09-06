# Sprite pipeline

Generates the garden creature cast locally at ₹0 using FLUX.1-schnell via `mflux` on
Apple Silicon. `briefs.py` holds the prompts; `gen.py` renders and chroma-keys them into
`app/src/main/assets/garden/`.

## Why these live in the repo

The session scratchpad is cleaned between sessions. It ate these scripts during 1C.6 and
again during 1C.7 planning, along with the Python virtualenv's source files. Everything
needed to regenerate the art now lives either here or under `~/.cache`, which survives.

## Setup

Two things live outside the repo because they are large:

| What | Where | Size |
|---|---|---|
| Quantized FLUX model | `~/.cache/mflux-models/flux1-schnell-q4` | 9 GB |
| Python environment | `~/.cache/expense-garden-art-venv` | ~400 MB |

**Model** (one-time; needs `HF_TOKEN` set and the FLUX.1-schnell licence accepted on
Hugging Face). This downloads ~32 GB before writing the 9 GB quantized copy:

    mflux-save --model schnell -q 4 --path ~/.cache/mflux-models/flux1-schnell-q4

**Environment** (one-time):

    python3.12 -m venv ~/.cache/expense-garden-art-venv
    ~/.cache/expense-garden-art-venv/bin/pip install mflux pillow

## Generating

    ~/.cache/expense-garden-art-venv/bin/python3 tools/art/gen.py                 # all sprites
    ~/.cache/expense-garden-art-venv/bin/python3 tools/art/gen.py berry_bush_0    # just these
    SEED_OFFSET=7 ~/.cache/expense-garden-art-venv/bin/python3 tools/art/gen.py   # re-roll

Creatures render at 768 px, houses at 1024 px, 4 steps. Expect ~95 s per sprite; the
machine lags noticeably during a run, so batch large runs when the Mac is free.

Seeds come from `zlib.crc32` of the sprite name, not `hash()` — Python randomizes string
hashing per process, which would make every run produce a different image and render
`SEED_OFFSET` re-rolls unrepeatable.

## Chroma keying

Sprites are shot against a solid screen and keyed by a **border flood-fill**, not a
paint-anywhere colour match. That distinction is the whole trick: baked shadow lobes touch
the image border so the fill eats them, while a warm sprite's pink-drifted interior does
not, so the fill can never punch holes in it.

Warm-palette sprites shoot on **cyan** (`CYAN_PREFIXES` in `gen.py` — currently tulips and
berries); everything else on **magenta**. Magenta despill destroys legitimate pink and red:
it bleached the tulips white before this rule existed, and berries carry the same risk.

## Gotchas

- `mflux` **uniquifies** its output (`name_1.png`) instead of overwriting, so `gen.py`
  deletes the raw first. Without that, a re-roll silently leaves the old file in place.
- Houses use `BUILDING_STYLE`, not `STYLE`. The shared creature block's "huge glossy eyes"
  clause put googly eyes on the hut during the 1C.6 pilot.
- Verify the output files themselves, never the exit code or a completion notification.
  1C.6 saw a "failed" that was really a `pkill` and a "completed exit 0" that masked a
  failed `cd`.
- **Composite over BLACK before accepting a sprite.** The cast is dark-outlined art on a
  checkerboard, and against that a pale cast shadow, a ground plate or a leftover patch of
  sky is nearly invisible. Over black they are obvious. Three of 4A's rare sprites shipped
  with a baked ground plane — `berry_bush_2` had a mint plate, sky in both top corners and
  floating debris — and all three had passed the flood-fill, `key_failed`, and
  `check_residue.py`. Nothing automated caught them.
- **There is no statistic for a baked ground plane, and looking for one wastes a day.**
  Two were tried against the whole cast on 2026-09-06 and both failed to separate the three
  known-bad sprites from good art: base width relative to the body (`berry_bush_2` landed
  exactly on the cast median while a perfectly good `tulip_1` was the widest), and base
  saturation (`bush_2` and `vegetable_row_3` sat ABOVE the median; `berry_bush_2` tied with
  `odd_mushroom_0` and `bell_flower_2`). A gate fitted to three examples would fire on those
  two good sprites, and a detector that cries wolf gets ignored — which is how
  `check_residue`'s first version, flagging eight good sprites, nearly got discarded. This
  one stays a human check.
- When a brief's CONCEPT contradicts the style block, re-rolling the seed re-rolls the same
  conflict. `bush_2` asked for a bonsai "in a shallow glazed ceramic pot" while STYLE asks
  for a creature "standing on a small soil mound" — a pot with nowhere to sit invites the
  model to invent a table under it. Reconcile the brief, then re-roll.
