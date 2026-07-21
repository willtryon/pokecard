"""GOT-OCR2.0 recognizer — a compact (0.58B) OCR-specialized VLM.

Recommended default for the hybrid engine: much stronger than EasyOCR on
stylized / full-art text, yet small enough to stay well under 10s/card even on
a 6GB card (GTX 1660 Super). Reads each detected crop in a short batch.
"""
from __future__ import annotations

from typing import List

from PIL import Image

from .base import Recognizer, clean_text, resolve_device

DEFAULT_MODEL = "stepfun-ai/GOT-OCR-2.0-hf"


class GotOcr2Recognizer(Recognizer):
    name = "got-ocr2"

    # GOT resizes every crop to 1024x1024, so each batched image costs real memory.
    # batch_size=2 is safe on CPU / 6GB GPUs; raise it on a 16GB GPU for speed.
    # Single-line crops are short, so a low token cap also curbs runaway repetition.
    def __init__(self, model_id=DEFAULT_MODEL, device="auto", batch_size=2, max_new_tokens=64):
        import torch
        from transformers import AutoProcessor, GotOcr2ForConditionalGeneration

        self.torch = torch
        self.device = resolve_device(device)
        self.dtype = torch.float16 if self.device in ("cuda", "mps") else torch.float32
        self.batch_size = batch_size
        self.max_new_tokens = max_new_tokens

        self.processor = AutoProcessor.from_pretrained(model_id)
        self.model = (
            GotOcr2ForConditionalGeneration.from_pretrained(model_id, dtype=self.dtype)
            .to(self.device)
            .eval()
        )

    def recognize(self, crops: List[Image.Image]) -> List[str]:
        out: List[str] = []
        for i in range(0, len(crops), self.batch_size):
            batch = [c.convert("RGB") for c in crops[i : i + self.batch_size]]
            inputs = self.processor(batch, return_tensors="pt").to(self.device)
            if "pixel_values" in inputs:
                inputs["pixel_values"] = inputs["pixel_values"].to(self.dtype)
            with self.torch.inference_mode():
                gen = self.model.generate(
                    **inputs,
                    max_new_tokens=self.max_new_tokens,
                    do_sample=False,
                    no_repeat_ngram_size=3,      # break token-loop degeneration
                    repetition_penalty=1.3,
                )
            trimmed = gen[:, inputs["input_ids"].shape[1] :]
            texts = self.processor.batch_decode(
                trimmed, skip_special_tokens=True, clean_up_tokenization_spaces=False
            )
            out.extend(clean_text(t) for t in texts)
        return out
