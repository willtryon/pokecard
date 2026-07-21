"""Emit results as hOCR — the standard HTML-with-coordinates OCR format.

hOCR encodes position in ``title`` attributes (``bbox x1 y1 x2 y2``) and is
consumable by many downstream tools (hocr-tools, OCRmyPDF, etc.).
"""
from __future__ import annotations

from html import escape

from .model import OcrResult

_HEADER = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
 "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
<meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
<meta name="ocr-system" content="pokeocr (EasyOCR)" />
<meta name="ocr-capabilities" content="ocr_page ocr_line ocrx_word" />
</head>
<body>"""


def build_hocr(result: OcrResult, out_path: str) -> str:
    parts = [_HEADER]
    parts.append(
        f'<div class="ocr_page" id="page_1" '
        f'title="image &quot;{escape(result.image_path)}&quot;; '
        f'bbox 0 0 {result.width} {result.height}">'
    )
    for i, it in enumerate(result.items):
        x1, y1 = int(it.x), int(it.y)
        x2, y2 = int(it.x + it.w), int(it.y + it.h)
        bbox = f"bbox {x1} {y1} {x2} {y2}"
        conf = int(round(it.confidence * 100))
        fs = int(round(it.font_size))
        parts.append(
            f'<span class="ocr_line" id="line_{i}" title="{bbox}; x_size {fs}">'
            f'<span class="ocrx_word" id="word_{i}" '
            f'title="{bbox}; x_wconf {conf}; x_fsize {fs}">'
            f"{escape(it.text)}</span></span>"
        )
    parts.append("</div>")
    parts.append("</body></html>")

    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(parts))
    return out_path
