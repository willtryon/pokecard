"""Recognizer registry.

    get_recognizer("got-ocr2", device="cuda")
"""
from __future__ import annotations

from .base import Recognizer

NAMES = ["trocr", "qwen2.5-vl", "got-ocr2", "easyocr"]


def get_recognizer(name: str, **kwargs) -> Recognizer:
    if name == "trocr":
        from .trocr import TrOcrRecognizer

        return TrOcrRecognizer(**kwargs)
    if name == "got-ocr2":
        from .got_ocr2 import GotOcr2Recognizer

        return GotOcr2Recognizer(**kwargs)
    if name == "qwen2.5-vl":
        from .qwen_vl import QwenVLRecognizer

        return QwenVLRecognizer(**kwargs)
    if name == "easyocr":
        from .easyocr_rec import EasyOcrRecognizer

        # EasyOCR takes langs/gpu, not device/model kwargs
        allowed = {k: v for k, v in kwargs.items() if k in ("langs", "gpu")}
        return EasyOcrRecognizer(**allowed)
    raise ValueError(f"unknown recognizer {name!r}; choose from {NAMES}")


__all__ = ["Recognizer", "get_recognizer", "NAMES"]
