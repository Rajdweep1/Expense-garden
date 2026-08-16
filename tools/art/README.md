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
