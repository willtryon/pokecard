#!/usr/bin/env bash
# One-time environment setup for pokeocr.
#
# Usage:
#   bash setup.sh            # CPU build of torch (this machine, no GPU)
#   TORCH=cuda bash setup.sh # NVIDIA CUDA build (e.g. GTX 1660 Super)
#   TORCH=rocm bash setup.sh # AMD ROCm build   (e.g. RX 9070 XT, needs ROCm 6.4+)
#
# No sudo required for the Python side. (ROCm itself must be installed system-wide
# first — see README.)
set -euo pipefail
cd "$(dirname "$0")"

PY="${PYTHON:-python3}"
TORCH="${TORCH:-cpu}"
echo ">> Using $($PY --version) at $(command -v "$PY")   torch build: $TORCH"

if [ ! -d .venv ]; then
  echo ">> Creating .venv"
  "$PY" -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate
python -m pip install --upgrade pip

case "$TORCH" in
  cpu)  INDEX="https://download.pytorch.org/whl/cpu" ;;
  cuda) INDEX="https://download.pytorch.org/whl/cu124" ;;
  rocm) INDEX="https://download.pytorch.org/whl/rocm6.4" ;;
  *) echo "!! TORCH must be cpu|cuda|rocm" >&2; exit 1 ;;
esac
echo ">> Installing torch + torchvision ($TORCH) from $INDEX"
pip install torch torchvision --index-url "$INDEX"

echo ">> Installing OCR + VLM libraries"
pip install easyocr reportlab pillow numpy "transformers>=4.49,<5" sentencepiece accelerate safetensors einops tiktoken

# bitsandbytes powers `--load-4bit` (Qwen on an 8GB GPU). CUDA-only; skip on CPU/ROCm.
if [ "$TORCH" = "cuda" ]; then
  echo ">> Installing bitsandbytes (for --load-4bit)"
  pip install bitsandbytes
fi

echo
echo ">> Done. Activate with:  source .venv/bin/activate"
echo ">> First run of each engine downloads model weights:"
echo "     easyocr    -> ~/.EasyOCR/            (~100MB)"
echo "     got-ocr2   -> ~/.cache/huggingface/  (~1.4GB)"
echo "     qwen2.5-vl -> ~/.cache/huggingface/  (~7GB)"
