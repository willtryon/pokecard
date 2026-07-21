"""Text *detection* — find where the text is, without reading it.

Reuses EasyOCR's CRAFT detector (already installed) to get precise boxes, which
the hybrid engine then hands to a stronger *recognizer*. Keeping detection and
recognition separate is what lets us preserve exact geometry while swapping in a
much better reader.
"""
from __future__ import annotations

from functools import lru_cache
from typing import List, Sequence, Tuple

import numpy as np
from PIL import Image

Quad = List[Tuple[float, float]]


@lru_cache(maxsize=4)
def _reader(langs: Tuple[str, ...], gpu: bool):
    import easyocr

    return easyocr.Reader(list(langs), gpu=gpu)


def detect_boxes(
    image: Image.Image,
    langs: Sequence[str] = ("en",),
    gpu: bool = False,
) -> List[Quad]:
    """Return a list of quads (TL, TR, BR, BL) for every detected text region."""
    arr = np.asarray(image.convert("RGB"))
    reader = _reader(tuple(langs), gpu)
    horizontal, free = reader.detect(arr)
    horizontal = horizontal[0] if horizontal else []
    free = free[0] if free else []

    quads: List[Quad] = []
    for x_min, x_max, y_min, y_max in horizontal:
        quads.append(
            [
                (float(x_min), float(y_min)),
                (float(x_max), float(y_min)),
                (float(x_max), float(y_max)),
                (float(x_min), float(y_max)),
            ]
        )
    for poly in free:
        quads.append([(float(px), float(py)) for px, py in poly])
    return quads
