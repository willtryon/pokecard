"""Distill split-mode band text into a card identity + collector number.

Identity (from the TOP band):
  * ``ENERGY`` if the top's entire alphanumeric content is exactly the word
    "energy" (strict — "Basic Fire Energy" has other letters, so it does NOT
    count and falls through to the Pokemon branch);
  * else, if the top mentions trainer/supporter/stadium, those subtype label
    LINES (plus item / technical machine) are dropped and the remainder is name;
  * else it is a Pokemon: strip Basic / Stage 1 / Stage 2, ``LV.<n>``,
    ``evolves from <word>``, and the HP number (+ the letters HP); the remainder
    is the Pokemon name.

Number (from the BOTTOM band, only when NOT an energy): a ``left/right`` slash
where the right side contains a number (a letter prefix like TG30 is allowed),
or — if there is no slash — a standalone 3-digit number. Ambiguity (multiple
conforming slashes, multiple 3-digit numbers, or nothing found) becomes ``ERROR``.

All matching is case-insensitive; the remainder keeps its original case, minus
star/circle glyphs.

``--mode split`` writes each card's ``.json`` and one ``results.csv`` for the
whole batch. This tool rebuilds that CSV from existing split JSONs (fast, no GPU
— handy after tweaking the rules above):
    python -m pokeocr.results out                  # every split *.json in a dir
    python -m pokeocr.results out/card0.json       # one JSON
    python -m pokeocr.results out -o out/cards.csv  # custom CSV path
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys

# Trainer subtype labels are matched per LINE (normalized to alphanumerics), so a
# line that is PURELY the label is dropped while a name that merely contains the
# word (e.g. "Collapsed Stadium") is kept whole. Stage words (Basic/Stage 1/2) are
# NOT here — no Pokemon name contains them, so they're stripped inline instead,
# which also catches "BASIC Minccino" where the VLM merged them onto one line.
_TRAINER_LABELS = {"trainer", "supporter", "stadium", "item", "technicalmachine"}

# Star / circle / bullet glyphs (rarity marks) the VLM emits that we never want in
# a name — e.g. "BASIC Minccino ★". Stripped from every line-1 result.
_SYMBOL_RE = re.compile("[★☆✦✧✩✪✫✬✭✮✯✰⭐✨⋆∗●○◦◯⭕•⦿⚪⚫◉◎∘·]")


def _tidy(s: str) -> str:
    """Drop star/circle glyphs, collapse whitespace, trim stray separators."""
    s = _SYMBOL_RE.sub(" ", s)
    s = re.sub(r"\s+", " ", s)
    return s.strip(" \t\r\n-–—|•·:,")


def _alnum_only(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


def _keep_nonlabel_lines(top_text: str, labels: set) -> list:
    """Return the TOP lines except those that are PURELY one of the given labels."""
    return [ln for ln in top_text.splitlines() if _alnum_only(ln) not in labels]


def classify_top(top_text: str) -> tuple[str, bool]:
    """Return (line1, is_energy) for the TOP band text."""
    # 1) Energy — strict: the ONLY alphanumeric content is the word "energy".
    if _alnum_only(top_text) == "energy":
        return "ENERGY", True

    # 2) Trainer / Supporter / Stadium — drop the label LINES, keep the name.
    if re.search(r"\b(trainer|supporter|stadium)\b", top_text, flags=re.IGNORECASE):
        kept = _keep_nonlabel_lines(top_text, _TRAINER_LABELS)
        return _tidy(" ".join(kept)), False

    # 3) Pokemon — strip inline (stage words never appear inside a real name, so
    # substring stripping is safe and also catches them merged onto the name line).
    text = top_text
    # Basic / Stage 1 / Stage 2.
    text = re.sub(r"\bstage\s*[12]\b", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"\bbasic\b", " ", text, flags=re.IGNORECASE)
    # "Evolves from" + the one word after it. Handles both its-own-line (the line
    # goes empty) and merged-onto-the-name-line (only the pre-evolution word is cut).
    text = re.sub(r"\bevolves\s+from\s+\S+", " ", text, flags=re.IGNORECASE)
    # "LV." plus the level after it (a number, or X for Level-X cards).
    text = re.sub(r"\blv\.?\s*(?:\d+|x)\b", " ", text, flags=re.IGNORECASE)
    # HP: drop the number AFTER "HP"; if none, the number BEFORE it; then the letters HP.
    after = re.sub(r"\bhp\b\s*\d+", " ", text, flags=re.IGNORECASE)
    text = after if after != text else re.sub(r"\d+\s*\bhp\b", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"\bhp\b", " ", text, flags=re.IGNORECASE)
    return _tidy(text), False


def parse_card_number(bottom_text: str) -> str:
    """Return the collector number from the BOTTOM band, or 'ERROR' if ambiguous."""
    # Any TOKEN/TOKEN around a slash; it "conforms" when the right side is a number
    # (a letter prefix like TG30 / SWSH12 is allowed — it just needs a digit).
    pairs = re.findall(r"([A-Za-z0-9]+)\s*/\s*([A-Za-z0-9]+)", bottom_text)
    if pairs:
        conforming = [(l, r) for (l, r) in pairs if any(ch.isdigit() for ch in r)]
        if len(conforming) == 1:
            left, right = conforming[0]
            return f"{left}/{right}"
        return "ERROR"  # zero or multiple conforming slashes

    # No slash: fall back to a lone standalone 3-digit number.
    threes = re.findall(r"\b\d{3}\b", bottom_text)
    if len(threes) == 1:
        return threes[0]
    return "ERROR"  # none or multiple


def build_results(top_text: str, bottom_text: str) -> str:
    """Two-line ``identity\\nnumber\\n`` text (``ENERGY\\n`` for energy). Convenience."""
    identity, is_energy = classify_top(top_text or "")
    if is_energy:
        return "ENERGY\n"
    return f"{identity}\n{parse_card_number(bottom_text or '')}\n"


# ---- CSV aggregation (one row per card, one file per batch) ----------------

CSV_FIELDS = ["image", "identity", "number", "rotation", "top", "middle", "bottom"]


def row_from_bands(image, top, middle, bottom, rotation="") -> dict:
    """Build a CSV row (identity + number + raw bands) from a card's band text."""
    identity, is_energy = classify_top(top or "")
    number = "" if is_energy else parse_card_number(bottom or "")
    return {
        "image": image,
        "identity": identity,
        "number": number,
        "rotation": rotation,
        "top": top or "",
        "middle": middle or "",
        "bottom": bottom or "",
    }


def write_csv(rows, path) -> str:
    """Write rows (dicts keyed by CSV_FIELDS) to ``path`` with a header."""
    import csv

    with open(path, "w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in CSV_FIELDS})
    return path


def _row_from_json(path):
    """Load a split JSON and return a CSV row, or None if it isn't a split JSON."""
    import json

    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return None
    sections = data.get("sections")
    if data.get("mode") != "split" or not isinstance(sections, dict):
        return None
    image = os.path.splitext(os.path.basename(path))[0]
    return row_from_bands(
        image,
        sections.get("top", ""),
        sections.get("middle", ""),
        sections.get("bottom", ""),
        data.get("rotation", ""),
    )


def _json_inputs(paths) -> list:
    """Resolve args (split .json / directory / glob / stem base) to JSON paths."""
    found = []

    def add_dir(d):
        found.extend(sorted(glob.glob(os.path.join(d, "*.json"))))

    for a in paths:
        if os.path.isdir(a):
            add_dir(a)
        elif a.endswith(".json"):
            found.append(a)
        else:
            hits = glob.glob(a) if any(ch in a for ch in "*?[") else [a]
            for h in hits:
                if os.path.isdir(h):
                    add_dir(h)
                elif h.endswith(".json"):
                    found.append(h)
                elif os.path.isfile(h + ".json"):
                    found.append(h + ".json")  # bare stem base, e.g. out/card0

    seen, out = set(), []
    for j in found:
        if j not in seen:
            seen.add(j)
            out.append(j)
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog="pokeocr.results",
        description="Rebuild the split results.csv from per-card split JSON files.",
    )
    ap.add_argument(
        "paths", nargs="+",
        help="split JSON file(s), a directory of them, a glob, or a stem base (out/card0)",
    )
    ap.add_argument("-o", "--csv", default=None, help="output CSV path (default: results.csv next to the inputs)")
    ap.add_argument("--print", action="store_true", dest="show", help="also print identity | number per card")
    args = ap.parse_args(argv)

    jsons = _json_inputs(args.paths)
    if not jsons:
        print("no JSON files found for the given path(s)", file=sys.stderr)
        return 1

    rows = []
    for jp in jsons:
        row = _row_from_json(jp)
        if row is None:
            print(f"  ! skipped (not a split JSON): {jp}", file=sys.stderr)
            continue
        rows.append(row)
        if args.show:
            print(f"[{row['image']}] {row['identity']} | {row['number']}")

    if not rows:
        print("no split rows produced", file=sys.stderr)
        return 1

    csv_path = args.csv or os.path.join(os.path.dirname(jsons[0]) or ".", "results.csv")
    write_csv(rows, csv_path)
    print(f"\nWrote {len(rows)} row(s) -> {csv_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
