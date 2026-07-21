"""Recognizer interface + shared helpers.

A recognizer reads already-cropped text regions. Given a list of PIL crops it
returns one string per crop, in the same order.
"""
from __future__ import annotations

import re
from typing import List

from PIL import Image

_RUN = re.compile(r"(.)\1{5,}")  # any char repeated 6+ times = VLM degeneration


def clean_text(text: str) -> str:
    """Trim VLM repetition loops (e.g. 'Ueda\\\\\\\\...') and stray whitespace."""
    if not text:
        return ""
    text = _RUN.sub(r"\1", text)          # collapse long single-char runs
    return " ".join(text.split()).strip()


def resolve_device(device: str) -> str:
    """'auto' -> cuda (incl. AMD ROCm, which reports as cuda) / mps / cpu."""
    import torch

    if device != "auto":
        return device
    if torch.cuda.is_available():
        return "cuda"
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


class Recognizer:
    name = "base"

    def recognize(self, crops: List[Image.Image]) -> List[str]:
        raise NotImplementedError
