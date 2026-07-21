"""Data model for OCR results — the 'PDF-like' representation.

Every detected chunk of text carries where it is (both the raw quadrilateral
from the detector and an axis-aligned bounding box) and roughly how big it is
(``font_size``, the estimated glyph height in pixels).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Tuple

Point = Tuple[float, float]


@dataclass
class TextItem:
    text: str
    confidence: float                 # 0..1
    quad: List[Point]                 # 4 corners, order TL, TR, BR, BL
    x: float                          # axis-aligned bbox, top-left origin
    y: float
    w: float
    h: float
    font_size: float                  # estimated glyph height in px
    angle: float                      # text baseline rotation, degrees

    def to_dict(self) -> dict:
        return {
            "text": self.text,
            "confidence": round(self.confidence, 4),
            "quad": [[round(px, 1), round(py, 1)] for px, py in self.quad],
            "bbox": {
                "x": round(self.x, 1),
                "y": round(self.y, 1),
                "w": round(self.w, 1),
                "h": round(self.h, 1),
            },
            "font_size": round(self.font_size, 1),
            "angle": round(self.angle, 2),
        }


@dataclass
class OcrResult:
    image_path: str
    width: int
    height: int
    items: List[TextItem] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "image": self.image_path,
            "width": self.width,
            "height": self.height,
            "item_count": len(self.items),
            "items": [it.to_dict() for it in self.items],
        }


@dataclass
class PageResult:
    """Whole-card transcription with no per-region geometry.

    Used by the 'page' mode, where the VLM reads the entire card in one pass.
    Position/size come back later; for now this just carries the raw text.
    """
    image_path: str
    width: int
    height: int
    text: str

    def to_dict(self) -> dict:
        return {
            "image": self.image_path,
            "width": self.width,
            "height": self.height,
            "mode": "page",
            "text": self.text,
        }


@dataclass
class SplitResult:
    """Whole-card transcription split into three horizontal bands.

    The image is rotated upright first, then cut into TOP (top sixth), MIDDLE
    (the rest), and BOTTOM (bottom eighth). Each band is transcribed separately
    so it's clear which part of the card the text came from. No per-region
    geometry beyond the band it belongs to.
    """
    image_path: str
    width: int                        # of the upright (post-rotation) image
    height: int
    rotation: int                     # degrees CCW applied to make it upright
    top: str
    middle: str
    bottom: str

    def to_dict(self) -> dict:
        return {
            "image": self.image_path,
            "width": self.width,
            "height": self.height,
            "mode": "split",
            "rotation": self.rotation,
            "sections": {
                "top": self.top,
                "middle": self.middle,
                "bottom": self.bottom,
            },
        }
