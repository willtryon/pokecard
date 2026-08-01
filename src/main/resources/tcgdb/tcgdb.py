#!/usr/bin/env python3
"""
tcgdb.py - mirror TCGCSV (TCGplayer catalog + daily market prices) into SQLite.

This is the *sync-only* build meant to be driven from the pokecard Java app.
All querying / price matching lives in Java against the SQLite file this writes.

Usage:
    python -m tcgdb sync                 # pull if upstream is newer than local
    python -m tcgdb sync --force         # pull regardless
    python -m tcgdb sync --category 3    # 3 = Pokemon (default)
    python -m tcgdb stats                # quick sanity dump

Env:
    TCGDB_PATH    sqlite file to write (default ./tcg.db)   <- Java sets this
    TCGCSV_BASE   base url (default https://tcgcsv.com)
"""

import argparse
import os
import re
import sqlite3
import sys
import time
from datetime import date

import requests

BASE = os.environ.get("TCGCSV_BASE", "https://tcgcsv.com").rstrip("/")
DB_PATH = os.environ.get("TCGDB_PATH", "tcg.db")
USER_AGENT = "tcgdb/1.0 (+sqlite mirror)"
SLEEP = 0.15  # be a good neighbor; docs ask for >=100ms

SCHEMA = """
PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE IF NOT EXISTS sets (
    group_id     INTEGER PRIMARY KEY,
    category_id  INTEGER NOT NULL,
    name         TEXT NOT NULL,
    abbreviation TEXT,
    published_on TEXT,
    modified_on  TEXT
);

CREATE TABLE IF NOT EXISTS products (
    product_id  INTEGER PRIMARY KEY,
    category_id INTEGER NOT NULL,
    group_id    INTEGER NOT NULL,
    name        TEXT NOT NULL,
    clean_name  TEXT,
    norm_name   TEXT NOT NULL,
    number      TEXT,
    norm_number TEXT,
    rarity      TEXT,
    card_type   TEXT,
    hp          TEXT,
    stage       TEXT,
    is_card     INTEGER NOT NULL DEFAULT 0,
    image_url   TEXT,
    url         TEXT
);

CREATE INDEX IF NOT EXISTS idx_products_norm  ON products(norm_name);
CREATE INDEX IF NOT EXISTS idx_products_group ON products(group_id);
CREATE INDEX IF NOT EXISTS idx_products_num   ON products(group_id, number);
-- idx_products_nnum is created in migrate(), after the column is guaranteed to exist

CREATE TABLE IF NOT EXISTS prices (
    product_id     INTEGER NOT NULL,
    sub_type       TEXT NOT NULL,
    low_price      REAL,
    mid_price      REAL,
    high_price     REAL,
    market_price   REAL,
    direct_low     REAL,
    as_of          TEXT NOT NULL,
    PRIMARY KEY (product_id, sub_type)
);

-- append-only, but only when market_price actually moves
CREATE TABLE IF NOT EXISTS price_history (
    product_id   INTEGER NOT NULL,
    sub_type     TEXT NOT NULL,
    as_of        TEXT NOT NULL,
    low_price    REAL,
    mid_price    REAL,
    market_price REAL,
    PRIMARY KEY (product_id, sub_type, as_of)
);
"""


# ---------- helpers ----------

def norm(s: str) -> str:
    """Loose key for matching OCR / user input against card names (used by Java)."""
    return re.sub(r"[^a-z0-9]+", "", (s or "").lower())


def norm_num(s: str) -> str:
    """
    Normalize a collector number so user/OCR input matches stored values.
    '004/102' -> '4'   '139/195' -> '139'   'TG05/TG30' -> 'tg5'   'SWSH001' -> 'swsh1'
    """
    if not s:
        return ""
    s = s.split("/")[0].strip().lower()
    s = re.sub(r"[^a-z0-9]+", "", s)
    return re.sub(r"(?<![0-9])0+(?=[0-9])", "", s)


def connect():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    con.executescript(SCHEMA)
    migrate(con)
    return con


def migrate(con):
    """Add norm_number to a DB created before this column existed, and backfill it."""
    cols = {r["name"] for r in con.execute("PRAGMA table_info(products)")}
    if "norm_number" not in cols:
        con.execute("ALTER TABLE products ADD COLUMN norm_number TEXT")
    con.execute("CREATE INDEX IF NOT EXISTS idx_products_nnum ON products(norm_number)")
    stale = con.execute(
        "SELECT product_id, number FROM products WHERE norm_number IS NULL AND number IS NOT NULL"
    ).fetchall()
    if stale:
        con.executemany(
            "UPDATE products SET norm_number=? WHERE product_id=?",
            [(norm_num(r["number"]), r["product_id"]) for r in stale],
        )
        con.commit()
        print(f"(migrated {len(stale)} rows: backfilled norm_number)", file=sys.stderr)


def make_session():
    s = requests.Session()
    s.headers["User-Agent"] = USER_AGENT
    return s


def get_json(session, path, tries=4):
    url = f"{BASE}{path}"
    for attempt in range(tries):
        try:
            r = session.get(url, timeout=30)
            if r.status_code in (429, 500, 502, 503, 504):
                raise requests.HTTPError(f"{r.status_code} on {url}")
            r.raise_for_status()
            return r.json()
        except Exception as e:
            if attempt == tries - 1:
                raise
            backoff = 2 ** attempt * 5
            print(f"  ! {e} -- retrying in {backoff}s", file=sys.stderr)
            time.sleep(backoff)


def meta_get(con, key):
    row = con.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    return row["value"] if row else None


def meta_set(con, key, value):
    con.execute(
        "INSERT INTO meta(key,value) VALUES(?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, str(value)),
    )


# ---------- sync ----------

EXT_KEYS = {
    "Number": "number",
    "Rarity": "rarity",
    "Card Type": "card_type",
    "HP": "hp",
    "Stage": "stage",
}


def parse_product(p, category_id):
    ext = {e["name"]: e.get("value") for e in (p.get("extendedData") or [])}
    fields = {dest: ext.get(src) for src, dest in EXT_KEYS.items()}
    is_card = 1 if (fields["number"] or fields["rarity"]) else 0
    return (
        p["productId"],
        category_id,
        p["groupId"],
        p["name"],
        p.get("cleanName"),
        norm(p.get("cleanName") or p["name"]),
        fields["number"],
        norm_num(fields["number"]),
        fields["rarity"],
        fields["card_type"],
        fields["hp"],
        fields["stage"],
        is_card,
        p.get("imageUrl"),
        p.get("url"),
    )


def sync(category_id: int, force: bool):
    con = connect()
    session = make_session()

    upstream = session.get(f"{BASE}/last-updated.txt", timeout=30).text.strip()
    local = meta_get(con, "last_upstream")
    if local == upstream and not force:
        print(f"up to date (upstream {upstream}); use --force to re-pull")
        return
    print(f"upstream {upstream} (local {local or 'none'})")

    groups = get_json(session, f"/tcgplayer/{category_id}/groups")["results"]
    print(f"{len(groups)} sets in category {category_id}")

    con.executemany(
        """INSERT INTO sets(group_id,category_id,name,abbreviation,published_on,modified_on)
           VALUES(?,?,?,?,?,?)
           ON CONFLICT(group_id) DO UPDATE SET
             name=excluded.name, abbreviation=excluded.abbreviation,
             published_on=excluded.published_on, modified_on=excluded.modified_on""",
        [
            (g["groupId"], category_id, g["name"], g.get("abbreviation"),
             g.get("publishedOn"), g.get("modifiedOn"))
            for g in groups
        ],
    )
    con.commit()

    as_of = upstream or date.today().isoformat()
    n_prod = n_price = n_hist = 0

    for i, g in enumerate(groups, 1):
        gid = g["groupId"]
        print(f"[{i}/{len(groups)}] {g['name']} ({gid})", flush=True)

        products = get_json(session, f"/tcgplayer/{category_id}/{gid}/products")["results"]
        time.sleep(SLEEP)
        prices = get_json(session, f"/tcgplayer/{category_id}/{gid}/prices")["results"]
        time.sleep(SLEEP)

        rows = [parse_product(p, category_id) for p in products]
        con.executemany(
            """INSERT INTO products(product_id,category_id,group_id,name,clean_name,norm_name,
                                    number,norm_number,rarity,card_type,hp,stage,is_card,
                                    image_url,url)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(product_id) DO UPDATE SET
                 group_id=excluded.group_id, name=excluded.name, clean_name=excluded.clean_name,
                 norm_name=excluded.norm_name, number=excluded.number,
                 norm_number=excluded.norm_number, rarity=excluded.rarity,
                 card_type=excluded.card_type, hp=excluded.hp, stage=excluded.stage,
                 is_card=excluded.is_card, image_url=excluded.image_url, url=excluded.url""",
            rows,
        )
        n_prod += len(rows)

        # snapshot history only where market price moved
        prev = {
            (r["product_id"], r["sub_type"]): r["market_price"]
            for r in con.execute(
                "SELECT p.product_id, p.sub_type, p.market_price FROM prices p "
                "JOIN products pr ON pr.product_id = p.product_id WHERE pr.group_id=?",
                (gid,),
            )
        }

        hist = []
        for pr in prices:
            key = (pr["productId"], pr["subTypeName"])
            if prev.get(key) != pr.get("marketPrice"):
                hist.append((pr["productId"], pr["subTypeName"], as_of,
                             pr.get("lowPrice"), pr.get("midPrice"), pr.get("marketPrice")))

        con.executemany(
            """INSERT INTO prices(product_id,sub_type,low_price,mid_price,high_price,
                                  market_price,direct_low,as_of)
               VALUES(?,?,?,?,?,?,?,?)
               ON CONFLICT(product_id,sub_type) DO UPDATE SET
                 low_price=excluded.low_price, mid_price=excluded.mid_price,
                 high_price=excluded.high_price, market_price=excluded.market_price,
                 direct_low=excluded.direct_low, as_of=excluded.as_of""",
            [
                (pr["productId"], pr["subTypeName"], pr.get("lowPrice"), pr.get("midPrice"),
                 pr.get("highPrice"), pr.get("marketPrice"), pr.get("directLowPrice"), as_of)
                for pr in prices
            ],
        )
        n_price += len(prices)

        con.executemany(
            """INSERT OR IGNORE INTO price_history
               (product_id,sub_type,as_of,low_price,mid_price,market_price)
               VALUES(?,?,?,?,?,?)""",
            hist,
        )
        n_hist += len(hist)

        con.commit()  # per-set commit: safe to Ctrl-C and resume

    meta_set(con, "last_upstream", upstream)
    meta_set(con, "last_sync", date.today().isoformat())
    con.commit()
    print(f"\ndone: {n_prod} products, {n_price} price rows, {n_hist} history rows")


def cmd_stats():
    con = connect()
    q = lambda s: con.execute(s).fetchone()[0]
    print(f"db           {os.path.abspath(DB_PATH)}")
    print(f"last upstream {meta_get(con,'last_upstream')}")
    print(f"last sync     {meta_get(con,'last_sync')}")
    print(f"sets          {q('SELECT COUNT(*) FROM sets')}")
    print(f"products      {q('SELECT COUNT(*) FROM products')}")
    print(f"  cards       {q('SELECT COUNT(*) FROM products WHERE is_card=1')}")
    print(f"price rows    {q('SELECT COUNT(*) FROM prices')}")
    print(f"history rows  {q('SELECT COUNT(*) FROM price_history')}")


# ---------- cli ----------

def main():
    ap = argparse.ArgumentParser(description="TCGCSV -> SQLite mirror (sync only)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("sync")
    s.add_argument("--category", type=int, default=3, help="3 = Pokemon")
    s.add_argument("--force", action="store_true")

    sub.add_parser("stats")

    a = ap.parse_args()
    if a.cmd == "sync":
        sync(a.category, a.force)
    elif a.cmd == "stats":
        cmd_stats()


if __name__ == "__main__":
    main()
