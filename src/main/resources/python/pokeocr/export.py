"""JSON export of the structured OCR result."""
from __future__ import annotations

import json

from .model import OcrResult


def build_json(result: OcrResult, out_path: str) -> str:
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(result.to_dict(), fh, indent=2, ensure_ascii=False)
    return out_path
