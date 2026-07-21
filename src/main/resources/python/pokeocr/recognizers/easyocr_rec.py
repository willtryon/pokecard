"""EasyOCR recognizer — the baseline reader, useful for A/B against the VLMs.

Uses the same EasyOCR model as detection, but only to read pre-cropped regions.
"""
from __future__ import annotations

from typing import List, Sequence, Tuple

import numpy as np
from PIL import Image

from ..detect import _reader
from .base import Recognizer


class EasyOcrRecognizer(Recognizer):
    name = "easyocr"

    def __init__(self, langs: Sequence[str] = ("en",), gpu: bool = False):
        self.reader = _reader(tuple(langs), gpu)

    def recognize(self, crops: List[Image.Image]) -> List[str]:
        out: List[str] = []
        for crop in crops:
            parts = self.reader.readtext(np.asarray(crop.convert("RGB")), detail=0)
            out.append(" ".join(parts).strip())
        return out
