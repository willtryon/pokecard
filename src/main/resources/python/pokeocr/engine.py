"""EasyOCR wrapper that produces a normalized :class:`OcrResult`.

The EasyOCR reader is heavy to construct (it loads neural net weights), so it is
cached per language set and reused across calls.
"""
from __future__ import annotations

import math
from functools import lru_cache
from typing import Sequence, Tuple

import numpy as np
from PIL import Image

from .model import OcrResult, PageResult, SplitResult, TextItem
from .detect import detect_boxes
from .orient import correct_orientation


@lru_cache(maxsize=4)
def _get_reader(langs: Tuple[str, ...], gpu: bool):
    import easyocr  # imported lazily so the rest of the package works without torch

    return easyocr.Reader(list(langs), gpu=gpu)


def _quad_metrics(quad):
    """From a 4-point quad (TL, TR, BR, BL) derive bbox, glyph height, angle."""
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = quad
    xs = [p[0] for p in quad]
    ys = [p[1] for p in quad]
    x, y = min(xs), min(ys)
    w, h = max(xs) - x, max(ys) - y

    # glyph height ~= length of the vertical edges (robust to slight rotation)
    left = math.hypot(x3 - x0, y3 - y0)   # TL -> BL
    right = math.hypot(x2 - x1, y2 - y1)  # TR -> BR
    font_size = (left + right) / 2 or h

    top = math.hypot(x1 - x0, y1 - y0)    # TL -> TR
    angle = math.degrees(math.atan2(y1 - y0, x1 - x0)) if top else 0.0
    return x, y, w, h, font_size, angle


def run_ocr(
    image_path: str,
    langs: Sequence[str] = ("en",),
    gpu: bool = False,
    min_confidence: float = 0.0,
) -> OcrResult:
    """Run OCR on ``image_path`` and return a normalized result.

    ``min_confidence`` drops detections below the given 0..1 threshold.
    """
    img = Image.open(image_path).convert("RGB")
    arr = np.asarray(img)

    reader = _get_reader(tuple(langs), gpu)
    detections = reader.readtext(arr)  # list of (quad, text, confidence)

    result = OcrResult(image_path=image_path, width=img.width, height=img.height)
    for quad, text, conf in detections:
        conf = float(conf)
        if conf < min_confidence:
            continue
        q = [(float(px), float(py)) for px, py in quad]
        x, y, w, h, font_size, angle = _quad_metrics(q)
        result.items.append(
            TextItem(
                text=text,
                confidence=conf,
                quad=q,
                x=x,
                y=y,
                w=w,
                h=h,
                font_size=font_size,
                angle=angle,
            )
        )
    # top-to-bottom, then left-to-right reading order
    result.items.sort(key=lambda it: (round(it.y / 10), it.x))
    return result


def _configure_faithful(recognizer, max_new_tokens):
    """Set up a recognizer for faithful whole-image transcription: a large token
    budget and no anti-repetition penalties (those tame single-line crop
    degeneration but on a full image force the model to alter legitimately
    repeated text -> fabricated words)."""
    if max_new_tokens is not None:
        recognizer.max_new_tokens = max_new_tokens
    elif getattr(recognizer, "max_new_tokens", 0) < 512:
        recognizer.max_new_tokens = 1024
    if hasattr(recognizer, "repetition_penalty"):
        recognizer.repetition_penalty = 1.0
    if hasattr(recognizer, "no_repeat_ngram_size"):
        recognizer.no_repeat_ngram_size = 0


def _transcribe(recognizer, img: Image.Image) -> str:
    """Transcribe one whole image with the (already configured) recognizer."""
    texts = recognizer.recognize([img])
    return (texts[0] if texts else "").strip()


def run_page(image_path: str, recognizer, max_new_tokens: int = None) -> PageResult:
    """Read the *whole card* in a single VLM pass — no detector, no geometry.

    The recognizer transcribes the entire image at once. Position/size are
    dropped here (added back later); the result is just the raw text. Needs a
    page-capable VLM recognizer (qwen2.5-vl); TrOCR is single-line and won't
    transcribe a full card.
    """
    img = Image.open(image_path).convert("RGB")
    _configure_faithful(recognizer, max_new_tokens)
    text = _transcribe(recognizer, img)
    return PageResult(image_path=image_path, width=img.width, height=img.height, text=text)


def run_split(
    image_path: str,
    recognizer,
    langs: Sequence[str] = ("en",),
    gpu: bool = None,
    max_new_tokens: int = None,
) -> SplitResult:
    """Rotate the card upright, cut it into three horizontal bands, and transcribe
    each separately so it's clear where the text came from.

    Bands: TOP = top sixth, BOTTOM = bottom eighth, MIDDLE = everything between.
    Text straddling a cut line may be dropped — that's accepted by design.
    """
    if gpu is None:
        gpu = getattr(recognizer, "device", "cpu") in ("cuda", "mps")

    img = Image.open(image_path).convert("RGB")
    img, rotation = correct_orientation(img, langs=langs, gpu=gpu)
    w, h = img.width, img.height

    top_cut = h // 6          # top sixth
    bottom_cut = h * 7 // 8   # bottom eighth starts here
    bands = {
        "top": img.crop((0, 0, w, top_cut)),
        "middle": img.crop((0, top_cut, w, bottom_cut)),
        "bottom": img.crop((0, bottom_cut, w, h)),
    }

    _configure_faithful(recognizer, max_new_tokens)
    # Transcribe all three bands in ONE recognize() call so the VLM batches them
    # (governed by the recognizer's batch_size) rather than three serial passes.
    # Use --batch 3 to run all three together.
    names = ["top", "middle", "bottom"]
    out = recognizer.recognize([bands[n] for n in names])
    texts = {n: (out[i].strip() if i < len(out) else "") for i, n in enumerate(names)}

    return SplitResult(
        image_path=image_path,
        width=w,
        height=h,
        rotation=rotation,
        top=texts["top"],
        middle=texts["middle"],
        bottom=texts["bottom"],
    )


def _crop_region(img: Image.Image, x, y, w, h, pad_frac: float, upscale_to: int):
    """Crop the axis-aligned region with padding, then upscale small text so the
    recognizer sees roughly ``upscale_to`` px of glyph height (helps a lot)."""
    pad = h * pad_frac
    left = max(0, int(x - pad))
    top = max(0, int(y - pad))
    right = min(img.width, int(x + w + pad))
    bottom = min(img.height, int(y + h + pad))
    crop = img.crop((left, top, right, bottom))
    if h > 0:
        factor = max(1.0, min(4.0, upscale_to / h))
        if factor > 1.01:
            crop = crop.resize(
                (max(1, int(crop.width * factor)), max(1, int(crop.height * factor))),
                Image.LANCZOS,
            )
    return crop


def run_hybrid(
    image_path: str,
    recognizer,
    langs: Sequence[str] = ("en",),
    gpu: bool = None,
    pad_frac: float = 0.12,
    upscale_to: int = 48,
) -> OcrResult:
    """Detect boxes (precise geometry) then read each crop with ``recognizer``.

    ``recognizer`` is any object with ``recognize(list[PIL.Image]) -> list[str]``
    (see :mod:`pokeocr.recognizers`).

    ``gpu`` controls the EasyOCR *detector*; when left as ``None`` it follows the
    recognizer's own device so the whole pipeline lands on the GPU together.
    """
    if gpu is None:
        gpu = getattr(recognizer, "device", "cpu") in ("cuda", "mps")
    img = Image.open(image_path).convert("RGB")
    quads = detect_boxes(img, langs=langs, gpu=gpu)

    metas = []
    crops = []
    for quad in quads:
        x, y, w, h, font_size, angle = _quad_metrics(quad)
        if w < 1 or h < 1:
            continue
        crops.append(_crop_region(img, x, y, w, h, pad_frac, upscale_to))
        metas.append((quad, x, y, w, h, font_size, angle))

    texts = recognizer.recognize(crops) if crops else []

    result = OcrResult(image_path=image_path, width=img.width, height=img.height)
    for (quad, x, y, w, h, font_size, angle), text in zip(metas, texts):
        text = (text or "").strip()
        if not text:
            continue
        result.items.append(
            TextItem(
                text=text,
                confidence=1.0,  # VLM readers give no per-word score; box was detected
                quad=quad,
                x=x,
                y=y,
                w=w,
                h=h,
                font_size=font_size,
                angle=angle,
            )
        )
    result.items.sort(key=lambda it: (round(it.y / 10), it.x))
    return result
