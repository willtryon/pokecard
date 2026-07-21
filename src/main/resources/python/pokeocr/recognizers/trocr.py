"""TrOCR recognizer — a transformer trained for single text-LINE recognition.

Unlike page-oriented OCR VLMs (GOT-OCR2), TrOCR expects one cropped line and
emits exactly that line then stops — no hallucinated tail. Small (~558M) and
fast per crop, so it's the safe default for the hybrid engine, including on a
6GB GPU (GTX 1660 Super).
"""
from __future__ import annotations

from typing import List

from PIL import Image

from .base import Recognizer, clean_text, resolve_device

DEFAULT_MODEL = "microsoft/trocr-large-printed"


class TrOcrRecognizer(Recognizer):
    name = "trocr"

    def __init__(self, model_id=DEFAULT_MODEL, device="auto", batch_size=8, max_new_tokens=64):
        import torch
        from transformers import TrOCRProcessor, VisionEncoderDecoderModel

        self.torch = torch
        self.device = resolve_device(device)
        # fp16 tripped a Float/Half mismatch: VisionEncoderDecoderModel re-initializes
        # the ViT pooler in fp32 after the dtype cast, so half-precision activations
        # collide with it. TrOCR-large is only ~2GB in fp32 — fits an 8GB card with
        # room to spare — so just run fp32 everywhere and avoid the whole class of bug.
        self.dtype = torch.float32
        self.batch_size = batch_size
        self.max_new_tokens = max_new_tokens

        self.processor = TrOCRProcessor.from_pretrained(model_id)
        self.model = (
            VisionEncoderDecoderModel.from_pretrained(model_id, dtype=self.dtype)
            .to(self.device)
            .eval()
        )

    def recognize(self, crops: List[Image.Image]) -> List[str]:
        out: List[str] = []
        for i in range(0, len(crops), self.batch_size):
            batch = [c.convert("RGB") for c in crops[i : i + self.batch_size]]
            pixel_values = self.processor(images=batch, return_tensors="pt").pixel_values
            pixel_values = pixel_values.to(self.device, self.dtype)
            with self.torch.inference_mode():
                gen = self.model.generate(pixel_values, max_new_tokens=self.max_new_tokens)
            texts = self.processor.batch_decode(gen, skip_special_tokens=True)
            out.extend(clean_text(t) for t in texts)
        return out
