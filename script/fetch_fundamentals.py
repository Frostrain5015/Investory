#!/usr/bin/env python3
"""
Fetch fundamental data for all tracked stocks.

A-shares  → BaoStock (profit_data, operation_data, growth_data)
HK / US   → Yahoo Finance Ticker.info

Usage:
    python fetch_fundamentals.py              # all markets
    python fetch_fundamentals.py -m a         # A-shares only
    python fetch_fundamentals.py -m hk        # HK only
    python fetch_fundamentals.py -m us        # US only
    python fetch_fundamentals.py --dry-run    # no DB writes

This script is run manually from the admin panel; no scheduled execution yet.
"""

import argparse
import configparser
import logging
import os
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

import pymysql

SCRIPT_DIR = Path(__file__).parent
CONFIG_FILE = SCRIPT_DIR / "config.ini"


# ── Config ──────────────────────────────────────────────────────────────

def load_config() -> dict:
    cfg = configparser.ConfigParser()
    if CONFIG_FILE.exists():
        cfg.read(CONFIG_FILE, encoding="utf-8")

    def get(section, key, default=""):
        try: return cfg.get(section, key).strip()
        except (configparser.NoSectionError, configparser.NoOptionError): return default

    return {
        "db_host": os.getenv("DB_HOST", get("database", "host", "localhost")),
        "db_port": int(os.getenv("DB_PORT", get("database", "port", "3306"))),
        "db_name": os.getenv("DB_NAME", get("database", "name", "investory")),
        "db_user": os.getenv("DB_USER", get("database", "user", "root")),
        "db_password": os.getenv("DB_PASSWORD", get("database", "password", "")),
        "proxy_url": os.getenv("PROXY_URL", get("proxy", "url", "")),
        "delay": float(get("fetch", "delay_a", "0.15")),
    }


def get_conn(cfg: dict):
    return pymysql.connect(
        host=cfg["db_host"], port=cfg["db_port"],
        database=cfg["db_name"], user=cfg["db_user"],
        password=cfg["db_password"], charset="utf8mb4", autocommit=False,
    )


# ── Logging ─────────────────────────────────────────────────────────────

def setup_logging() -> logging.Logger:
    sys.stdout.reconfigure(line_buffering=True, encoding="utf-8")
    logging.basicConfig(
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%H:%M:%S", level=logging.INFO, stream=sys.stdout,
    )
    return logging.getLogger("fundamentals")


# ── DB helpers ──────────────────────────────────────────────────────────

def ensure_table(cfg: dict):
    conn = get_conn(cfg)
    try:
        with conn.cursor() as cur:
            cur.execute("""
                CREATE TABLE IF NOT EXISTS stock_fundamentals (
                    stock_id        BIGINT PRIMARY KEY,
                    pe_ttm          DOUBLE,
                    pb              DOUBLE,
                    roe             DOUBLE,
                    eps_ttm         DOUBLE,
                    rev_growth      DOUBLE,
                    earnings_growth DOUBLE,
                    debt_ratio      DOUBLE,
                    market_cap      DOUBLE,
                    sector          VARCHAR(64),
                    industry        VARCHAR(256),
                    div_yield       DOUBLE,
                    updated_at      DATETIME
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
        conn.commit()
    finally:
        conn.close()


def upsert_fundamentals(conn, stock_id: int, data: dict):
    with conn.cursor() as cur:
        cur.execute("""
            INSERT INTO stock_fundamentals
                (stock_id, pe_ttm, pb, roe, eps_ttm, rev_growth, earnings_growth,
                 debt_ratio, market_cap, sector, industry, div_yield, updated_at)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW())
            ON DUPLICATE KEY UPDATE
                pe_ttm = VALUES(pe_ttm), pb = VALUES(pb), roe = VALUES(roe),
                eps_ttm = VALUES(eps_ttm), rev_growth = VALUES(rev_growth),
                earnings_growth = VALUES(earnings_growth),
                debt_ratio = VALUES(debt_ratio), market_cap = VALUES(market_cap),
                sector = VALUES(sector), industry = VALUES(industry),
                div_yield = VALUES(div_yield), updated_at = NOW()
        """, (
            stock_id, data.get("pe_ttm"), data.get("pb"), data.get("roe"),
            data.get("eps_ttm"), data.get("rev_growth"), data.get("earnings_growth"),
            data.get("debt_ratio"), data.get("market_cap"),
            data.get("sector"), data.get("industry"), data.get("div_yield"),
        ))


def get_stocks(conn, market: str) -> list:
    """Return [(id, symbol, name, market), ...] for the given market code."""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT s.id, s.symbol, s.name, s.market FROM stocks s "
            "INNER JOIN stock_prices sp ON sp.stock_id = s.id "
            "WHERE s.market = %s GROUP BY s.id, s.symbol, s.name, s.market "
            "ORDER BY s.symbol", (market,))
        return list(cur.fetchall())


# ── A-shares via BaoStock ───────────────────────────────────────────────

def fetch_a_share_fundamentals(conn, cfg: dict, dry_run: bool, log: logging.Logger):
    try:
        import baostock as bs
        bs.login()
    except Exception:
        log.warning("BaoStock not available, skipping A-shares")
        return

    stocks = get_stocks(conn, "SH") + get_stocks(conn, "SZ")
    if not stocks:
        log.info("No A-share stocks found")
        bs.logout(); return

    total = len(stocks)
    for idx, (stock_id, symbol, name, market) in enumerate(stocks):
        # symbol format: "1.600519" → code "sh.600519"
        parts = symbol.split(".")
        code = ("sh." if market == "SH" else "sz.") + parts[1]
        log.info(f"[{idx+1}/{total}] {name} ({code})")

        try:
            data = {}

            # Profit data (annual): ROE, EPS
            profit = bs.query_profit_data(code, year=datetime.now().year)
            if profit.data and len(profit.data) > 1:
                fields = profit.data[1]
                data["roe"] = to_float(fields, 5)
                data["eps_ttm"] = to_float(fields, 2)

            # Operation data: PE, PB
            op = bs.query_operation_data(code, year=datetime.now().year)
            if op.data and len(op.data) > 1:
                fields = op.data[1]
                data["pe_ttm"] = to_float(fields, 3)
                data["pb"] = to_float(fields, 4)

            # Growth data: revenue_growth, earnings_growth
            growth = bs.query_growth_data(code, year=datetime.now().year)
            if growth.data and len(growth.data) > 1:
                fields = growth.data[1]
                data["rev_growth"] = to_float(fields, 1)
                data["earnings_growth"] = to_float(fields, 4)

            # Balance data: debt ratio
            bal = bs.query_balance_data(code, year=datetime.now().year)
            if bal.data and len(bal.data) > 1:
                fields = bal.data[1]
                data["debt_ratio"] = to_float(fields, 6)

            if not dry_run:
                upsert_fundamentals(conn, stock_id, data)
            else:
                log.info(f"  DRY-RUN: {data}")

        except Exception as e:
            log.warning(f"  Failed: {e}")
        time.sleep(cfg["delay"])

    bs.logout()


def to_float(fields: list, idx: int) -> Optional[float]:
    try:
        v = fields[idx]
        return float(v) if v and v != "" else None
    except Exception:
        return None


# ── HK / US via Yahoo Finance ───────────────────────────────────────────

def fetch_yahoo_fundamentals(market: str, conn, cfg: dict, dry_run: bool, log: logging.Logger):
    try:
        import yfinance as yf
    except Exception:
        log.warning("yfinance not available, skipping Yahoo Finance fundamentals")
        return

    market_map = {"HK": "SH", "US": "US"}  # check both SH/SZ + HK/US
    stocks = get_stocks(conn, market)
    if not stocks:
        log.info(f"No {market} stocks found"); return

    # Set proxy if configured
    if cfg["proxy_url"]:
        os.environ["HTTP_PROXY"] = cfg["proxy_url"]
        os.environ["HTTPS_PROXY"] = cfg["proxy_url"]

    total = len(stocks)
    for idx, (stock_id, symbol, name, _) in enumerate(stocks):
        # symbol format: "116.00700" → ticker "0700.HK"
        parts = symbol.split(".")
        ticker = f"{parts[1]}.{'T' if market == 'US' else 'HK'}"
        log.info(f"[{idx+1}/{total}] {name} ({ticker})")

        try:
            t = yf.Ticker(ticker)
            info = t.info
            data = {
                "pe_ttm": info.get("trailingPE"),
                "pb": info.get("priceToBook"),
                "roe": info.get("returnOnEquity"),
                "eps_ttm": info.get("trailingEps"),
                "rev_growth": info.get("revenueGrowth", {}).get("yoy") if isinstance(info.get("revenueGrowth"), dict) else None,
                "earnings_growth": info.get("earningsGrowth"),
                "debt_ratio": info.get("debtToEquity"),
                "market_cap": info.get("marketCap"),
                "sector": info.get("sector"),
                "industry": info.get("industry"),
                "div_yield": info.get("dividendYield"),
            }
            # Scale dividend yield from decimal to percent
            if data["div_yield"] is not None and data["div_yield"] < 1:
                data["div_yield"] *= 100

            if not dry_run:
                upsert_fundamentals(conn, stock_id, data)
            else:
                log.info(f"  DRY-RUN: {data}")

        except Exception as e:
            log.warning(f"  Failed: {e}")
        time.sleep(cfg["delay"])


# ── Main ────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Fetch fundamental data for all tracked stocks")
    parser.add_argument("-m", choices=["a", "hk", "us"], help="Market: a/hk/us (default: all)")
    parser.add_argument("--dry-run", action="store_true", help="Read only, no DB writes")
    args = parser.parse_args()

    log = setup_logging()
    cfg = load_config()

    if args.dry_run:
        log.info("DRY-RUN MODE")

    ensure_table(cfg)
    conn = get_conn(cfg) if not args.dry_run else None

    try:
        if not args.m or args.m == "a":
            log.info("=== A-shares fundamentals ===")
            fetch_a_share_fundamentals(conn or get_conn(cfg), cfg, args.dry_run, log)

        if not args.m or args.m == "hk":
            log.info("=== HK fundamentals ===")
            fetch_yahoo_fundamentals("HK", conn or get_conn(cfg), cfg, args.dry_run, log)

        if not args.m or args.m == "us":
            log.info("=== US fundamentals ===")
            fetch_yahoo_fundamentals("US", conn or get_conn(cfg), cfg, args.dry_run, log)

        if conn:
            conn.close()
        log.info("Done.")
    except Exception as e:
        log.error(f"Fatal: {e}")
        if conn: conn.close()
        sys.exit(1)


if __name__ == "__main__":
    main()
