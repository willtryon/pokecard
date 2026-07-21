# pokeocr

OCR Pokémon card scans **PDF-style** — it doesn't just pull the text, it keeps
track of **where** each piece of text sits on the card and **roughly how big**
it is (glyph height in pixels, a good font-size proxy).

Engines, same outputs for all:

| Engine | Kind | Best for | Notes |
|--------|------|----------|-------|
| `easyocr` (default) | classic OCR | fast baseline, structured text box | weak on stylized full-art (HP/name over art) |
| **`trocr`** | hybrid, crop reader | **recommended** for hard cards | ~558M, crop-native, no hallucination, great on a 6GB GPU (GTX 1660 Super). Tends to output ALL-CAPS. |
| `qwen2.5-vl` | hybrid, VLM | **max accuracy** on a 16GB GPU (RX 9070 XT) | ~3B, preserves mixed case; ~7GB download |
| `got-ocr2` | hybrid, page VLM | ⚠️ not recommended for crops | page-oriented; hallucinates a garbage tail on single-line crops |

The **hybrid** engines share one idea: a proper text **detector** finds the boxes
(so position + font-size stay exact), then a stronger model reads each crop. That
keeps geometry precise while massively improving reading of stylized text.

On a real full-art card (Dragonite-EX), the `easyocr` baseline mangled the HP as
`Mp60`; the `trocr` hybrid read `180 HP`, `DRAGONITE`, the ability, `HYPER BEAM`,
and the EX rule — each with its box and font size.

## Outputs

For each input image you get:

| Format | File | What it is |
|--------|------|-----------|
| Searchable PDF | `NAME.pdf` | The scan with an invisible, aligned text layer — select/search text right on the card |
| Annotated image | `NAME.annotated.png` | Boxes drawn over detected text, labeled with size + confidence |
| hOCR | `NAME.hocr.html` | Standard HTML-with-coordinates OCR format (works with hocr-tools, OCRmyPDF, …) |
| JSON | `NAME.json` | Structured data: text, bbox, quad, `font_size`, `angle`, confidence |

## Setup (one-time, no sudo for the Python side)

Pick the torch build for the machine you're on:

```bash
bash setup.sh              # CPU only (this box, no GPU)
TORCH=cuda bash setup.sh   # NVIDIA CUDA  (GTX 1660 Super)
TORCH=rocm bash setup.sh   # AMD ROCm 6.4+ (RX 9070 XT)
```

This creates `./.venv` on Python 3.14 and installs torch/torchvision + easyocr +
the VLM libraries (transformers, accelerate). Model weights download on first use:

| Engine | Weights | Where |
|--------|---------|-------|
| `easyocr` | ~100 MB | `~/.EasyOCR/` |
| `trocr` | ~1.3 GB | `~/.cache/huggingface/` |
| `qwen2.5-vl` | ~7 GB | `~/.cache/huggingface/` |
| `got-ocr2` | ~1.4 GB | `~/.cache/huggingface/` |

> **transformers is pinned `<5`** — the 5.x line dropped the legacy tokenizer
> conversion TrOCR needs. `sentencepiece` is required for the same reason.
>
> Why not PaddleOCR? PaddlePaddle has no Python 3.14 wheel; the PyTorch stack
> does, so everything runs on your system Python with no separate environment.

### GPU notes

- **GTX 1660 Super (6 GB, CUDA):** turnkey. Use `--engine trocr` — small,
  crop-native, and comfortably <10 s/card. (A 3B VLM is tight on 6 GB.)
- **8 GB NVIDIA (e.g. RTX 4060):** `--engine trocr` runs great. For the stronger
  `qwen2.5-vl`, fp16 (~7 GB) brushes the ceiling and often OOMs; add `--load-4bit`
  to quantize the weights to ~1.8 GB so it fits with headroom:
  `--engine qwen2.5-vl --device cuda --batch 1 --load-4bit` (needs `pip install bitsandbytes`).
- **RX 9070 XT (16 GB, ROCm):** install ROCm 6.4+ system-wide first (that part
  needs sudo, e.g. AMD's installer / `sudo dnf install rocm`), then
  `TORCH=rocm bash setup.sh`. 16 GB comfortably runs `--engine qwen2.5-vl` (best
  reading, preserves case) — raise `--batch` for speed. `torch.cuda.is_available()`
  reports `True` under ROCm, so `--device auto` just works.

> **Memory:** hybrid engines resize crops before reading, so a big `--batch` can
> spike RAM/VRAM. Default is 2 (safe). GOT-OCR2 in particular resizes to 1024²;
> keep its batch small. If a run gets OOM-killed (exit 137), lower `--batch`.

## Usage

```bash
source .venv/bin/activate

# Fast baseline, all formats -> ./out/
python -m pokeocr card0.png

# Hard full-art cards: hybrid TrOCR reader (recommended)
python -m pokeocr card0.png --engine trocr

# Max accuracy on a 16GB GPU
python -m pokeocr card0.png --engine qwen2.5-vl --device cuda --batch 4

# Whole-card mode: let the VLM transcribe the entire card in one pass
# (no per-region position/size yet — writes card0.txt + card0.json)
python -m pokeocr card0.png --engine qwen2.5-vl --mode page --device cuda --load-4bit

# Split mode: auto-rotate upright, cut into TOP (top 1/6) / MIDDLE / BOTTOM (bottom 1/8),
# transcribe each band separately -> card0.TOP.txt, card0.MIDDLE.txt, card0.BOTTOM.txt
# (also writes card0.RESULTS.txt — see below)
python -m pokeocr card0.png --engine qwen2.5-vl --mode split --device cuda --load-4bit

# RESULTS: distill TOP/BOTTOM into a 2-line summary (card identity + collector number).
# Runs automatically at the end of --mode split; also standalone on existing files:
python -m pokeocr.results out/card0        # -> out/card0.RESULTS.txt
python -m pokeocr.results out --print      # every *.TOP.txt in a directory

# Several cards, just the PDF + annotated image
python -m pokeocr 'card*.png' -o out --formats pdf annotated
```

### As a library

```python
from pokeocr import run_ocr
result = run_ocr("card0.png", min_confidence=0.3)
for it in result.items:
    print(f"{it.text!r:30} @({it.x:.0f},{it.y:.0f}) size≈{it.font_size:.0f}px conf={it.confidence:.2f}")
```

## CLI options

| Flag | Default | Meaning |
|------|---------|---------|
| `-e, --engine` | `easyocr` | `easyocr` \| `trocr` \| `qwen2.5-vl` \| `got-ocr2` |
| `--mode` | `region` | `region` = detect boxes then read each crop (keeps position/size); `page` = VLM transcribes the whole card in one pass; `split` = auto-rotate upright then read TOP (1/6) / MIDDLE / BOTTOM (1/8) bands into separate files. `page`/`split` need `qwen2.5-vl` |
| `--max-new-tokens` | — | decoder token budget (page mode defaults to 1024) |
| `--min-pixels` | page/split: ~800k | qwen2.5-vl resolution floor; raise to upscale small inputs so small print (esp. the bottom band) reads more accurately |
| `--max-pixels` | page/split: ~2M | qwen2.5-vl resolution ceiling; caps VRAM/time |
| `-o, --out` | `out` | Output directory |
| `-f, --formats` | `all` | Any of `json annotated hocr pdf` (or `all`) |
| `--device` | `auto` | `auto` \| `cpu` \| `cuda` \| `mps` (cuda covers AMD ROCm) — hybrid engines |
| `--batch` | `2` | VLM crops per batch; raise on a 16GB GPU, lower if OOM (exit 137) |
| `--load-4bit` | off | load `qwen2.5-vl` in 4-bit NF4 (~1.8 GB weights) so it fits an 8 GB GPU; needs bitsandbytes + CUDA |
| `--model` | — | override the HuggingFace model id for the chosen hybrid engine |
| `--langs` | `en` | EasyOCR language codes (e.g. `en ja`) |
| `--min-conf` | `0.0` | Drop detections below this 0..1 confidence (`easyocr` engine only) |
| `--dpi` | `248` | Assumed scan DPI (physical PDF page size only; 620×865 cards ≈ 248) |

## RESULTS file (split mode)

`--mode split` also distills the bands into `{stem}.RESULTS.txt` — two lines:

**Line 1 — card identity (from TOP):**
- `ENERGY` if the top's *entire* alphanumeric content is exactly the word
  "energy" (strict: `Basic Fire Energy` has other letters, so it is **not**
  flagged as energy and is handled as a Pokémon).
- Trainer cards: the `trainer` / `supporter` / `stadium` / `item` /
  `technical machine` **subtype label lines** are dropped, leaving the card name.
- Pokémon: the `Basic` / `Stage 1` / `Stage 2` **stage lines** are dropped, then
  `LV.<n>` (and `LV.X`), `evolves from <word>`, and the HP number (+ the letters
  `HP`) are stripped inline, leaving the Pokémon name.

Label/stage stripping is **line-based** (it relies on the VLM emitting each row
on its own line): a line that is *purely* the label is removed, but a name that
merely *contains* the word is kept whole — `Collapsed Stadium` stays
`Collapsed Stadium`, and a `Technical Machine: …` name is kept while a bare
`Technical Machine` banner line is dropped.

**Line 2 — collector number (from BOTTOM, omitted for energy):**
- A `left/right` slash where the right side contains a number — a letter prefix
  is allowed, so `178/198`, `SV/123`, and `TG18/TG30` all work; otherwise a
  standalone 3-digit number.
- `ERROR` when it can't be pinned down: multiple conforming slashes, multiple
  3-digit numbers, or nothing found.

All matching is case-insensitive; the remaining text keeps its original case.

## Notes

- The **font-size estimate** is the detected glyph height in pixels, derived from
  the vertical edges of each text box (robust to slight rotation). Relative sizes
  across a card are reliable; absolute point size depends on scan DPI.
- Text over busy artwork (name banner, HP) is the hardest case for any OCR; the
  structured text box (attacks, rules, flavor text) reads most cleanly.
