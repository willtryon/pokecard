"""Draw detected text boxes over the original scan for visual verification."""
from __future__ import annotations

from PIL import Image, ImageDraw, ImageFont

from .model import OcrResult

# Fedora ships Noto; fall back gracefully if it moves.
_FONT_CANDIDATES = [
    "/usr/share/fonts/google-noto-vf/NotoSans[wght].ttf",
    "/usr/share/fonts/google-noto/NotoSans-Regular.ttf",
    "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf",
]


def _load_font(size: int):
    for path in _FONT_CANDIDATES:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def _color(conf: float):
    """Green (confident) -> red (unsure)."""
    conf = max(0.0, min(1.0, conf))
    return (int(255 * (1 - conf)), int(180 * conf), 40)


def annotate(result: OcrResult, out_path: str, show_text: bool = True) -> str:
    img = Image.open(result.image_path).convert("RGB")
    draw = ImageDraw.Draw(img, "RGBA")

    for it in result.items:
        color = _color(it.confidence)
        draw.polygon([tuple(p) for p in it.quad], outline=color, width=2)

        if show_text:
            label = f"{it.text}  [{it.font_size:.0f}px {it.confidence*100:.0f}%]"
            font = _load_font(max(10, min(int(it.font_size * 0.6), 18)))
            tx, ty = it.x, max(0, it.y - 14)
            l, t, r, b = draw.textbbox((tx, ty), label, font=font)
            draw.rectangle([l - 1, t - 1, r + 1, b + 1], fill=(0, 0, 0, 160))
            draw.text((tx, ty), label, fill=(255, 255, 255), font=font)

    img.save(out_path)
    return out_path
