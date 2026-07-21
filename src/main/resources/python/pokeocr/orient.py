"""Orientation detection — get the card upright before we cut it.

A scan can arrive rotated 90/180/270. Splitting into top/middle/bottom bands is
only meaningful if the card is right-side-up first, and Qwen doesn't reliably
self-correct heavy rotation. We settle it with the OCR detector we already have:
rotate the image through all four 90-degree orientations, read each, and keep the
one whose text is most confidently recognized — that's the upright one. (Reading
is what distinguishes 0 from 180; box shape alone can't.)
"""
from __future__ import annotations

from typing import Sequence, Tuple

import numpy as np
from PIL import Image


def _score(reader, img: Image.Image) -> float:
    """Sum of confidence x text-length over all detections — higher = more
    readable, so the upright orientation wins."""
    arr = np.asarray(img.convert("RGB"))
    results = reader.readtext(arr)  # list of (quad, text, confidence)
    return sum(float(conf) * max(1, len(text.strip())) for _, text, conf in results)


def detect_orientation(
    image: Image.Image,
    langs: Sequence[str] = ("en",),
    gpu: bool = False,
    max_side: int = 720,
) -> int:
    """Return the rotation (0/90/180/270, counter-clockwise) that makes ``image``
    upright. Scored on a downscaled copy for speed; the angle applies to the
    full-res image unchanged."""
    from .detect import _reader

    reader = _reader(tuple(langs), gpu)

    small = image
    longest = max(image.size)
    if longest > max_side:
        s = max_side / longest
        small = image.resize((max(1, int(image.width * s)), max(1, int(image.height * s))))

    best_angle, best_score = 0, -1.0
    for angle in (0, 90, 180, 270):
        cand = small if angle == 0 else small.rotate(angle, expand=True)
        score = _score(reader, cand)
        if score > best_score:  # strict > keeps 0 on ties (tried first)
            best_score, best_angle = score, angle
    return best_angle


def correct_orientation(
    image: Image.Image,
    langs: Sequence[str] = ("en",),
    gpu: bool = False,
) -> Tuple[Image.Image, int]:
    """Return ``(upright_image, applied_angle)``."""
    angle = detect_orientation(image, langs=langs, gpu=gpu)
    upright = image if angle == 0 else image.rotate(angle, expand=True)
    return upright, angle
