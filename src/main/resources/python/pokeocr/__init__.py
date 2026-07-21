"""pokeocr — OCR Pokémon card scans, PDF-style (position + font-size aware).

Public API:
    from pokeocr import run_ocr
    result = run_ocr("card0.png")           # -> OcrResult
"""
from .model import TextItem, OcrResult, PageResult, SplitResult
from .engine import run_ocr, run_hybrid, run_page, run_split
from .results import build_results

__all__ = [
    "TextItem", "OcrResult", "PageResult", "SplitResult",
    "run_ocr", "run_hybrid", "run_page", "run_split",
    "build_results",
]
