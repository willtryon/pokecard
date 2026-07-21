"""Qwen2.5-VL recognizer — a strong general 3B VLM, the max-accuracy option.

Best reading of stylized / full-art text. In fp16 it needs ~7GB VRAM (fits a
16GB card comfortably, brushes the ceiling of an 8GB one). Pass ``load_4bit=True``
to quantize the weights to ~1.8GB (NF4) so it fits an 8GB card with room to
spare — negligible accuracy loss for OCR. 4-bit requires an NVIDIA GPU
(bitsandbytes is CUDA-only).
"""
from __future__ import annotations

from typing import List

from PIL import Image

from .base import Recognizer, clean_text, resolve_device

DEFAULT_MODEL = "Qwen/Qwen2.5-VL-3B-Instruct"
PROMPT = (
    "Transcribe the text in this image exactly as it appears, in natural reading "
    "order (top to bottom, left to right). Copy only text that is actually visible "
    "— do not guess, complete, or add any words that are not clearly present. "
    "Output only the text itself, with no commentary, labels, or extra words."
)


class QwenVLRecognizer(Recognizer):
    name = "qwen2.5-vl"

    def __init__(self, model_id=DEFAULT_MODEL, device="auto", batch_size=2,
                 max_new_tokens=128, load_4bit=False, min_pixels=None, max_pixels=None):
        import torch
        from transformers import AutoProcessor, Qwen2_5_VLForConditionalGeneration

        self.torch = torch
        self.device = resolve_device(device)
        self.dtype = torch.float16 if self.device in ("cuda", "mps") else torch.float32
        self.batch_size = batch_size
        self.max_new_tokens = max_new_tokens
        # Anti-degeneration decoding for single-line CROPS. These are harmful for
        # whole-card transcription (a card legitimately repeats 3-grams), so
        # run_page() neutralizes them — see engine.run_page.
        self.repetition_penalty = 1.3
        self.no_repeat_ngram_size = 3

        load_kwargs = {"dtype": self.dtype}
        if load_4bit:
            if self.device != "cuda":
                raise ValueError(
                    "load_4bit needs an NVIDIA CUDA GPU (bitsandbytes is CUDA-only); "
                    f"resolved device is {self.device!r}."
                )
            from transformers import BitsAndBytesConfig

            load_kwargs["quantization_config"] = BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_quant_type="nf4",
                bnb_4bit_use_double_quant=True,
                bnb_4bit_compute_dtype=self.dtype,
            )
            # A bitsandbytes-quantized model must be placed at load time via
            # device_map; calling .to(device) on it afterwards raises.
            load_kwargs["device_map"] = {"": self.device}

        # min_pixels/max_pixels control how big Qwen renders each image before
        # reading. Raising min_pixels forces small inputs (e.g. a thin split band)
        # to be upscaled to a resolution floor, so tiny text gets far more patches
        # -> much better accuracy on small print. Costs VRAM + time.
        proc_kwargs = {}
        if min_pixels is not None:
            proc_kwargs["min_pixels"] = min_pixels
        if max_pixels is not None:
            proc_kwargs["max_pixels"] = max_pixels
        self.processor = AutoProcessor.from_pretrained(model_id, **proc_kwargs)
        model = Qwen2_5_VLForConditionalGeneration.from_pretrained(model_id, **load_kwargs)
        if not load_4bit:
            model = model.to(self.device)
        self.model = model.eval()
        msgs = [{"role": "user", "content": [{"type": "image"}, {"type": "text", "text": PROMPT}]}]
        self._chat = self.processor.apply_chat_template(
            msgs, tokenize=False, add_generation_prompt=True
        )

    def recognize(self, crops: List[Image.Image]) -> List[str]:
        out: List[str] = []
        for i in range(0, len(crops), self.batch_size):
            batch = [c.convert("RGB") for c in crops[i : i + self.batch_size]]
            inputs = self.processor(
                text=[self._chat] * len(batch),
                images=batch,
                padding=True,
                return_tensors="pt",
            ).to(self.device)
            gen_kwargs = {"max_new_tokens": self.max_new_tokens, "do_sample": False}
            if self.repetition_penalty and self.repetition_penalty != 1.0:
                gen_kwargs["repetition_penalty"] = self.repetition_penalty
            if self.no_repeat_ngram_size:
                gen_kwargs["no_repeat_ngram_size"] = self.no_repeat_ngram_size
            with self.torch.inference_mode():
                gen = self.model.generate(**inputs, **gen_kwargs)
            trimmed = gen[:, inputs["input_ids"].shape[1] :]
            texts = self.processor.batch_decode(
                trimmed, skip_special_tokens=True, clean_up_tokenization_spaces=False
            )
            out.extend(clean_text(t) for t in texts)
        return out
