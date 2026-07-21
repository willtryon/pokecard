"""Build a searchable PDF: the scan image with an invisible, aligned text layer.

Selecting/searching text in the PDF hits the OCR layer positioned over the
matching pixels — exactly how a scanned-then-OCR'd PDF behaves.
"""
from __future__ import annotations

from PIL import Image
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

from .model import OcrResult

_FONT = "Helvetica"


def build_pdf(result: OcrResult, out_path: str, dpi: int = 248) -> str:
    """``dpi`` sets the physical page size only; text alignment is DPI-independent.

    620x865 card scans are ~248 dpi, so that is the default.
    """
    img = Image.open(result.image_path).convert("RGB")
    scale = 72.0 / dpi  # pixels -> PDF points
    page_w, page_h = img.width * scale, img.height * scale

    c = canvas.Canvas(out_path, pagesize=(page_w, page_h))
    c.drawImage(ImageReader(img), 0, 0, width=page_w, height=page_h)

    for it in result.items:
        text = it.text.strip()
        if not text:
            continue
        font_pt = max(it.font_size * scale, 1.0)
        box_w = it.w * scale
        # PDF origin is bottom-left; nudge baseline up from the box bottom.
        baseline_y = page_h - (it.y + it.h) * scale + it.h * scale * 0.18

        to = c.beginText()
        to.setTextRenderMode(3)  # invisible
        to.setFont(_FONT, font_pt)
        str_w = c.stringWidth(text, _FONT, font_pt)
        if str_w > 0:
            to.setHorizScale(100.0 * box_w / str_w)  # stretch to match box width
        to.setTextOrigin(it.x * scale, baseline_y)
        to.textLine(text)
        c.drawText(to)

    c.save()
    return out_path
