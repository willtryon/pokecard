"""Command-line entry point.

    python -m pokeocr card0.png                  # all outputs -> ./out/
    python -m pokeocr scans/*.png -o out --formats annotated pdf
"""
from __future__ import annotations

import argparse
import glob
import os
import sys
from typing import List

ALL_FORMATS = ["json", "annotated", "hocr", "pdf"]


def _expand(paths: List[str]) -> List[str]:
    out: List[str] = []
    for p in paths:
        hits = glob.glob(p)
        out.extend(hits if hits else [p])
    return out


def _process_page(path, out_dir, formats, recognizer, max_new_tokens) -> bool:
    """Whole-card mode: the VLM transcribes the entire image, no geometry."""
    from . import run_page

    if not os.path.isfile(path):
        print(f"  ! not found: {path}", file=sys.stderr)
        return False

    stem = os.path.splitext(os.path.basename(path))[0]
    result = run_page(path, recognizer, max_new_tokens=max_new_tokens)
    nchars = len(result.text)
    print(f"  transcribed {nchars} chars")

    # Always write the plain-text transcription.
    txt_path = os.path.join(out_dir, f"{stem}.txt")
    with open(txt_path, "w", encoding="utf-8") as fh:
        fh.write(result.text + "\n")
    if "json" in formats:
        import json
        with open(os.path.join(out_dir, f"{stem}.json"), "w", encoding="utf-8") as fh:
            json.dump(result.to_dict(), fh, indent=2, ensure_ascii=False)
    skipped = [f for f in formats if f in ("annotated", "hocr", "pdf")]
    if skipped:
        print(f"  (skipped {', '.join(skipped)} — page mode has no positions yet)")
    return True


def _process_split(path, out_dir, formats, langs, recognizer, max_new_tokens):
    """Split mode: rotate upright, cut into TOP/MIDDLE/BOTTOM bands, transcribe each.

    Writes the per-card ``.json`` (if requested) and returns a CSV row dict (the
    band text + derived identity/number). The whole batch is aggregated into one
    ``results.csv`` by the caller. Returns None if the image can't be read.
    """
    from . import run_split
    from .results import row_from_bands

    if not os.path.isfile(path):
        print(f"  ! not found: {path}", file=sys.stderr)
        return None

    stem = os.path.splitext(os.path.basename(path))[0]
    result = run_split(path, recognizer, langs=langs, max_new_tokens=max_new_tokens)
    if result.rotation:
        print(f"  rotated {result.rotation}deg to upright")
    print(f"  TOP {len(result.top)} / MIDDLE {len(result.middle)} / BOTTOM {len(result.bottom)} chars")

    if "json" in formats:
        import json
        with open(os.path.join(out_dir, f"{stem}.json"), "w", encoding="utf-8") as fh:
            json.dump(result.to_dict(), fh, indent=2, ensure_ascii=False)

    return row_from_bands(stem, result.top, result.middle, result.bottom, result.rotation)


def _process(path, out_dir, formats, langs, min_conf, dpi, recognizer) -> bool:
    from . import run_ocr, run_hybrid

    if not os.path.isfile(path):
        print(f"  ! not found: {path}", file=sys.stderr)
        return False

    stem = os.path.splitext(os.path.basename(path))[0]
    if recognizer is None:
        result = run_ocr(path, langs=langs, min_confidence=min_conf)
    else:
        result = run_hybrid(path, recognizer, langs=langs)
    print(f"  {len(result.items)} text regions")

    if "json" in formats:
        from .export import build_json
        build_json(result, os.path.join(out_dir, f"{stem}.json"))
    if "annotated" in formats:
        from .annotate import annotate
        annotate(result, os.path.join(out_dir, f"{stem}.annotated.png"))
    if "hocr" in formats:
        from .hocr import build_hocr
        build_hocr(result, os.path.join(out_dir, f"{stem}.hocr.html"))
    if "pdf" in formats:
        from .pdf import build_pdf
        build_pdf(result, os.path.join(out_dir, f"{stem}.pdf"), dpi=dpi)
    return True


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="pokeocr", description="OCR Pokémon card scans, PDF-style.")
    ap.add_argument("images", nargs="+", help="image path(s) or glob(s)")
    ap.add_argument("-o", "--out", default="out", help="output directory (default: out)")
    ap.add_argument(
        "-f", "--formats", nargs="+", default=["all"],
        choices=ALL_FORMATS + ["all"],
        help="which outputs to write (default: all)",
    )
    ap.add_argument("--langs", nargs="+", default=["en"], help="OCR languages (default: en)")
    ap.add_argument("--min-conf", type=float, default=0.0, help="drop detections below this 0..1 confidence (easyocr engine only)")
    ap.add_argument("--dpi", type=int, default=248, help="assumed scan DPI for PDF page size (default: 248)")
    ap.add_argument(
        "-e", "--engine", default="easyocr",
        choices=["easyocr", "trocr", "qwen2.5-vl", "got-ocr2"],
        help="easyocr = fast baseline; trocr/qwen2.5-vl = hybrid readers (much better on full-art). "
             "trocr is the recommended crop reader; qwen2.5-vl is max accuracy on a 16GB GPU.",
    )
    ap.add_argument(
        "--mode", default="region", choices=["region", "page", "split"],
        help="region = detect boxes then read each crop (keeps position/size); "
             "page = VLM transcribes the whole card in one pass; "
             "split = rotate upright, cut into TOP/MIDDLE/BOTTOM bands, transcribe each. "
             "page/split need a VLM engine (qwen2.5-vl).",
    )
    ap.add_argument("--max-new-tokens", type=int, default=None, help="decoder token budget (page mode defaults to 1024; a full card has a lot of text)")
    ap.add_argument("--min-pixels", type=int, default=None, help="qwen2.5-vl: min pixels per image; raise to upscale small inputs for sharper small text (page/split default ~800k)")
    ap.add_argument("--max-pixels", type=int, default=None, help="qwen2.5-vl: max pixels per image; caps resolution/VRAM (page/split default ~2M)")
    ap.add_argument("--device", default="auto", help="auto|cpu|cuda|mps (cuda covers AMD ROCm) for the VLM engines")
    ap.add_argument("--batch", type=int, default=None, help="VLM crops per batch (default 2; raise on a 16GB GPU, lower if OOM)")
    ap.add_argument("--model", default=None, help="override the HuggingFace model id for the chosen VLM engine")
    ap.add_argument("--load-4bit", action="store_true", help="load qwen2.5-vl in 4-bit (NF4) to fit an 8GB GPU (~1.8GB weights); needs bitsandbytes + CUDA")
    ap.add_argument("--csv", default="results.csv", help="split mode: filename (in --out) of the aggregated per-batch CSV (default: results.csv)")
    args = ap.parse_args(argv)

    formats = ALL_FORMATS if "all" in args.formats else args.formats
    os.makedirs(args.out, exist_ok=True)

    if args.mode in ("page", "split") and args.engine in ("easyocr", "trocr"):
        ap.error(f"--mode {args.mode} needs a whole-image VLM; --engine {args.engine} is single-line. Use --engine qwen2.5-vl.")

    recognizer = None
    if args.engine != "easyocr":
        from .recognizers import get_recognizer

        kw = {"device": args.device}
        if args.batch:
            kw["batch_size"] = args.batch
        if args.model:
            kw["model_id"] = args.model
        if args.load_4bit:
            if args.engine != "qwen2.5-vl":
                ap.error("--load-4bit is only supported for --engine qwen2.5-vl")
            kw["load_4bit"] = True
        if args.engine == "qwen2.5-vl":
            # 28x28 is Qwen's patch size. For whole-image transcription (page/split)
            # default to a high resolution floor so small print — especially the
            # thin bottom band — is upscaled and read accurately.
            mp, xp = args.min_pixels, args.max_pixels
            if args.mode in ("page", "split"):
                mp = mp or 1024 * 28 * 28   # ~800k px floor
                xp = xp or 2560 * 28 * 28   # ~2M px ceiling
            if mp:
                kw["min_pixels"] = mp
            if xp:
                kw["max_pixels"] = xp
        print(f">> loading {args.engine} recognizer on device={args.device} (first run downloads weights)...")
        recognizer = get_recognizer(args.engine, **kw)

    images = _expand(args.images)
    ok = 0
    split_rows = []
    for path in images:
        print(f"[{path}]")
        if args.mode == "page":
            done = _process_page(path, args.out, formats, recognizer, args.max_new_tokens)
        elif args.mode == "split":
            row = _process_split(path, args.out, formats, tuple(args.langs), recognizer, args.max_new_tokens)
            done = row is not None
            if row is not None:
                split_rows.append(row)
        else:
            done = _process(path, args.out, formats, tuple(args.langs), args.min_conf, args.dpi, recognizer)
        if done:
            ok += 1

    if args.mode == "split" and split_rows:
        from .results import write_csv
        csv_path = write_csv(split_rows, os.path.join(args.out, args.csv))
        print(f"\naggregated {len(split_rows)} card(s) -> {csv_path}")
    print(f"\nDone: {ok}/{len(images)} image(s) -> {args.out}/  ({', '.join(formats)}) via {args.engine} [{args.mode}]")
    return 0 if ok == len(images) else 1


if __name__ == "__main__":
    raise SystemExit(main())
