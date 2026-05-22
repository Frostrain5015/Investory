#!/usr/bin/env python3
"""
Investory 全市场日K线统一抓取脚本

数据源:
  A股  → BaoStock        (大陆直连，前复权)
  港股  → Yahoo Finance   (需代理)
  美股  → Yahoo Finance   (需代理)
  指数  → Yahoo Finance + Sina (全球指数/商品/汇率)

用法:
    python fetch_stocks.py                  # 按当前时间自动选市场
    python fetch_stocks.py -m a             # 仅 A 股
    python fetch_stocks.py -m hk            # 仅港股
    python fetch_stocks.py -m us            # 仅美股
    python fetch_stocks.py -m idx           # 仅指数
    python fetch_stocks.py -m all           # 全部
    python fetch_stocks.py -m all --dry-run # 不写 DB（测试用）

配置:
    脚本同目录的 config.ini（参考 config.ini.example）
    或通过环境变量: DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD / PROXY_URL / DAYS_BACK
"""

import argparse
import configparser
import json
import logging
import math
import os
import re
import sys
import time
import traceback
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

# ─── 配置 ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR     = Path(__file__).parent
CONFIG_FILE    = SCRIPT_DIR / "config.ini"
CHECKPOINT_DIR = SCRIPT_DIR / ".checkpoints"


def load_config() -> dict:
    cfg = configparser.ConfigParser()
    if CONFIG_FILE.exists():
        cfg.read(CONFIG_FILE, encoding="utf-8")

    def get(section, key, default=""):
        try:
            return cfg.get(section, key).strip()
        except (configparser.NoSectionError, configparser.NoOptionError):
            return default

    return {
        "db_host":     os.getenv("DB_HOST",     get("database", "host",     "localhost")),
        "db_port":     int(os.getenv("DB_PORT", get("database", "port",     "3306"))),
        "db_name":     os.getenv("DB_NAME",     get("database", "name",     "investory")),
        "db_user":     os.getenv("DB_USER",     get("database", "user",     "root")),
        "db_password": os.getenv("DB_PASSWORD", get("database", "password", "")),
        "proxy_url":   os.getenv("PROXY_URL",   get("proxy",    "url",      "")),
        "days_back":   int(os.getenv("DAYS_BACK", get("fetch", "days_back", "7"))),
        "delay_a":     float(get("fetch", "delay_a",  "0.15")),
        "delay_hk":    float(get("fetch", "delay_hk", "0.05")),
        "delay_us":    float(get("fetch", "delay_us", "0.3")),
    }


# ─── 断点续传 ─────────────────────────────────────────────────────────────────

def load_checkpoint(market: str):
    cf = CHECKPOINT_DIR / f"{market}.txt"
    if cf.exists():
        return cf.read_text().strip() or None
    return None

def save_checkpoint(market: str, symbol: str):
    CHECKPOINT_DIR.mkdir(parents=True, exist_ok=True)
    (CHECKPOINT_DIR / f"{market}.txt").write_text(symbol)

def clear_checkpoint(market: str):
    cf = CHECKPOINT_DIR / f"{market}.txt"
    if cf.exists(): cf.unlink()

# ─── 日志 ─────────────────────────────────────────────────────────────────────

def setup_logging(verbose: bool) -> logging.Logger:
    fmt   = "[%(asctime)s] %(levelname)s %(message)s"
    level = logging.DEBUG if verbose else logging.INFO
    sys.stdout.reconfigure(line_buffering=True, encoding="utf-8")  # flush per line when piped to Java ProcessBuilder
    logging.basicConfig(format=fmt, datefmt="%H:%M:%S", level=level, stream=sys.stdout)
    return logging.getLogger("fetch")


# ─── 数据库 ───────────────────────────────────────────────────────────────────

def get_conn(cfg: dict):
    import pymysql
    return pymysql.connect(
        host=cfg["db_host"],
        port=cfg["db_port"],
        database=cfg["db_name"],
        user=cfg["db_user"],
        password=cfg["db_password"],
        charset="utf8mb4",
        autocommit=False,
    )


def upsert_prices(conn, rows: list) -> int:
    """
    批量 UPSERT stock_prices。
    rows 格式: [(stock_id, trade_date_str, open, close, high, low, volume), ...]
    返回影响行数（INSERT=1, UPDATE=2, MySQL ODKU 约定）。
    """
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany(
        """INSERT INTO stock_prices (stock_id, trade_date, open, close, high, low, volume)
           VALUES (%s, %s, %s, %s, %s, %s, %s)
           ON DUPLICATE KEY UPDATE
             open=VALUES(open), close=VALUES(close), high=VALUES(high),
             low=VALUES(low), volume=VALUES(volume)""",
        rows,
    )
    conn.commit()
    n = cur.rowcount
    cur.close()
    return n


def build_skip_set(conn, stock_ids: list, start: str, end: str) -> set:
    """
    一次 SQL 查出已有 end 日期数据的 stock_id 集合（精确匹配，避免跳过缺口）。
    """
    if not stock_ids:
        return set()
    threshold = end
    fmt = ",".join(["%s"] * len(stock_ids))
    cur = conn.cursor()
    cur.execute(
        f"SELECT stock_id FROM stock_prices "
        f"WHERE stock_id IN ({fmt}) AND trade_date >= %s "
        f"GROUP BY stock_id HAVING MAX(trade_date) >= %s",
        stock_ids + [start, threshold],
    )
    skip = {row[0] for row in cur.fetchall()}
    cur.close()
    return skip


# ─── A股 (BaoStock) ───────────────────────────────────────────────────────────

def fetch_a_shares(cfg: dict, start: str, end: str, dry_run: bool, log: logging.Logger,
                   market_filter: str | None = None, checkpoint_key: str = "a") -> None:
    label = {"SH": "沪市", "SZ": "深市"}.get(market_filter, "沪深")
    log.info(f"=== A股/{label} (BaoStock) | {start} ~ {end} ===")

    try:
        import baostock as bs
        import pandas as pd
    except ImportError:
        log.error("缺少依赖: pip install baostock pandas")
        return

    # BaoStock connects to Chinese mainland servers — must not go through any proxy
    for _k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
        os.environ.pop(_k, None)

    lg = bs.login()
    if lg.error_code != "0":
        log.error(f"BaoStock 登录失败: {lg.error_msg}")
        return

    conn = None
    try:
        if not dry_run:
            conn = get_conn(cfg)

        # ── 1. 获取全量股票列表 ──────────────────────────────────────────────
        rs = bs.query_stock_basic()
        raw = []
        while rs.error_code == "0" and rs.next():
            raw.append(rs.get_row_data())
        df = pd.DataFrame(raw, columns=rs.fields)

        prefix = {"SH": ("sh.",), "SZ": ("sz.",)}.get(market_filter, ("sh.", "sz."))
        df = df[
            df["code"].str.startswith(prefix) &
            (df["type"] == "1") &
            (df["outDate"] == "")
        ].copy()

        def _symbol(code):
            exch, num = code.split(".")
            return f"{'1' if exch == 'sh' else '0'}.{num}"

        df["symbol"] = df["code"].apply(_symbol)
        df["market"] = df["code"].apply(lambda c: "SH" if c.startswith("sh.") else "SZ")
        df["name"]   = df["code_name"]
        df = df[["code", "symbol", "name", "market"]].drop_duplicates(subset=["symbol"])
        log.info(f"共 {len(df)} 只（SH {(df.market=='SH').sum()}，SZ {(df.market=='SZ').sum()}）")

        # ── 2. 同步 stocks 表 ────────────────────────────────────────────────
        if not dry_run:
            cur = conn.cursor()
            cur.executemany(
                "INSERT IGNORE INTO stocks (symbol, name, market, currency) VALUES (%s,%s,%s,'CNY')",
                [(r.symbol, r.name, r.market) for r in df.itertuples()],
            )
            conn.commit()
            cur.execute("SELECT id, symbol FROM stocks WHERE market IN ('SH','SZ')")
            id_map = {row[1]: row[0] for row in cur.fetchall()}
            cur.close()
            df["stock_id"] = df["symbol"].map(id_map)
            df = df[df["stock_id"].notna()].copy()
            log.info(f"stocks 表已同步，共 {len(df)} 条有效记录")
        else:
            log.info("[dry-run] 跳过 DB 同步，模拟处理前 10 只")
            df = df.head(10)

        # ── 3. 逐只抓取 K 线 ─────────────────────────────────────────────────
        total     = len(df)
        total_rows = 0
        errors    = []
        skip_ids  = set() if dry_run else build_skip_set(conn, list(df["stock_id"].astype(int)), start, end)
        if skip_ids:
            log.info(f"已有完整数据，跳过 {len(skip_ids)} 只，剩余 {total - len(skip_ids)} 只")
        checkpoint = load_checkpoint(checkpoint_key)
        skipped    = checkpoint is not None

        for seq, (_, row) in enumerate(df.iterrows(), 1):
            bs_code  = row["code"]
            stock_id = None if dry_run else int(row["stock_id"])

            if skipped:
                if bs_code != checkpoint:
                    continue
                skipped = False
                continue

            if not dry_run and stock_id in skip_ids:
                continue

            rs2 = bs.query_history_k_data_plus(
                bs_code,
                "date,open,high,low,close,volume",
                start_date=start, end_date=end,
                frequency="d", adjustflag="2",
            )
            if rs2.error_code != "0":
                errors.append((bs_code, row["name"], rs2.error_msg))
                time.sleep(cfg["delay_a"])
                continue

            price_rows = []
            while rs2.next():
                d = rs2.get_row_data()  # [date, open, high, low, close, volume]
                try:
                    date_s, o, h, l, c, v = d
                    if not c or c == "0" or not v:
                        continue
                    price_rows.append((
                        stock_id, date_s,
                        round(float(o), 4), round(float(c), 4),
                        round(float(h), 4), round(float(l), 4),
                        int(float(v)),
                    ))
                except (ValueError, TypeError):
                    continue

            n = len(price_rows)
            if price_rows and not dry_run:
                n = upsert_prices(conn, price_rows)
                total_rows += n

            save_checkpoint(checkpoint_key, bs_code)
            pct = seq / total * 100
            log.info(f"  [{seq}/{total} {pct:.1f}%] {row['name']}({bs_code}) → {n}行")
            time.sleep(cfg["delay_a"])

        clear_checkpoint(checkpoint_key)
        log.info(f"A股/{label}完成: 写入 {total_rows} 行，失败 {len(errors)} 只")
        for sym, nm, msg in errors[:10]:
            log.warning(f"  ✗ {sym} {nm}: {msg}")
        if len(errors) > 10:
            log.warning(f"  ... 共 {len(errors)} 条失败")

    finally:
        if conn:
            conn.close()
        bs.logout()


# ─── 港股 (腾讯财经) ──────────────────────────────────────────────────────────

def _tencent_kline(code5d: str, start: str, end: str) -> list:
    """
    腾讯财经前复权日K线。
    返回 [(date, open, close, high, low, volume), ...] 失败返回 []。
    腾讯格式: [date, open, high, low, close, volume]（注意 close 在 index 4）
    """
    import requests
    tc = f"hk{code5d}"
    url = (
        f"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
        f"?_var=kline_dayqfq&param={tc},day,{start},{end},730,qfq"
    )
    try:
        r = requests.get(url, timeout=10)
        m = re.search(r"=(\{.+})", r.text)
        if not m:
            return []
        data   = json.loads(m.group(1))
        klines = data.get("data", {}).get(tc, {}).get("day", [])
        rows = []
        for k in klines:
            try:
                rows.append((
                    k[0],                    # date
                    round(float(k[1]), 4),   # open
                    round(float(k[4]), 4),   # close  （index 4！）
                    round(float(k[2]), 4),   # high
                    round(float(k[3]), 4),   # low
                    int(float(k[5])),        # volume
                ))
            except (ValueError, IndexError):
                continue
        return rows
    except Exception:
        return []


def _discover_hk(conn, log: logging.Logger) -> int:
    """扫描腾讯 API 发现 stocks 表中缺失的港股，自动补录。"""
    import requests
    cur = conn.cursor()
    cur.execute("SELECT REPLACE(symbol, '.HK', '') FROM stocks WHERE market='HK'")
    existing = {row[0] for row in cur.fetchall()}
    cur.execute(
        "SELECT MAX(CAST(REPLACE(symbol, '.HK', '') AS UNSIGNED)) FROM stocks WHERE market='HK'"
    )
    max_code = cur.fetchone()[0] or 9999
    scan_end = min(int(max_code) + 300, 99999)
    found = 0
    for i in range(1, scan_end + 1):
        code5d = f"{i:05d}"
        if code5d in existing:
            continue
        try:
            r = requests.get(f"http://qt.gtimg.cn/q=hk{code5d}", timeout=5)
            r.encoding = "gbk"
            m = re.search(r'"\d+~([^~]+)~', r.text)
            if not m:
                continue
            name = m.group(1).strip()
            if not name or name == code5d or name.isdigit():
                continue
            cur.execute(
                "INSERT IGNORE INTO stocks (symbol, name, market, currency) VALUES (%s,%s,'HK','HKD')",
                (f"{code5d}.HK", name),
            )
            conn.commit()
            found += 1
            existing.add(code5d)
        except Exception:
            pass
        time.sleep(0.015)
    cur.close()
    return found


def fetch_hk_stocks(
    cfg: dict, start: str, end: str, dry_run: bool,
    discover: bool, log: logging.Logger
) -> None:
    log.info(f"=== 港股 (Yahoo Finance) | {start} ~ {end} ===")

    # 代理必须在 import yfinance 之前注入 os.environ
    proxy_url = cfg.get("proxy_url", "").strip()
    if proxy_url:
        os.environ["HTTP_PROXY"]  = proxy_url
        os.environ["HTTPS_PROXY"] = proxy_url
        log.info(f"已设置代理: {proxy_url}")
    else:
        log.warning("未配置 proxy_url，从大陆直连 Yahoo Finance 可能失败")

    try:
        import yfinance as yf
    except ImportError:
        log.error("缺少依赖: pip install yfinance")
        return

    conn = None if dry_run else get_conn(cfg)

    def fetch_kline(ticker: str) -> list:
        t    = yf.Ticker(ticker)
        hist = t.history(start=start, end=end, interval="1d", auto_adjust=True)
        if hist.empty:
            return []
        rows = []
        for dt, row in hist.iterrows():
            try:
                rows.append((
                    dt.strftime("%Y-%m-%d"),
                    round(float(row["Open"]),  4),
                    round(float(row["Close"]), 4),
                    round(float(row["High"]),  4),
                    round(float(row["Low"]),   4),
                    int(float(row["Volume"])),
                ))
            except (ValueError, KeyError):
                continue
        return rows

    if dry_run:
        test_codes = ["0001.HK", "0700.HK", "9988.HK"]
        log.info(f"[dry-run] 测试 {len(test_codes)} 只港股 K 线请求")
        for ticker in test_codes:
            rows = fetch_kline(ticker)
            log.info(f"  {ticker} → {len(rows)} 行" + (f"，最新: {rows[-1]}" if rows else "，无数据"))
        return

    cur = conn.cursor()
    cur.execute("SELECT id, symbol, name FROM stocks WHERE market='HK'")
    stocks = cur.fetchall()
    cur.close()
    log.info(f"DB 中港股: {len(stocks)} 只")

    skip_ids = build_skip_set(conn, [s[0] for s in stocks], start, end)
    if skip_ids:
        log.info(f"已有完整数据，跳过 {len(skip_ids)} 只，剩余 {len(stocks) - len(skip_ids)} 只")
    total_rows = 0
    no_data    = 0
    checkpoint = load_checkpoint("hk")
    skipped    = checkpoint is not None

    for seq, (stock_id, symbol, name) in enumerate(stocks, 1):
        code5d = symbol.replace(".HK", "").replace("hk", "")

        if skipped:
            if code5d != checkpoint:
                continue
            skipped = False
            continue

        if stock_id in skip_ids:
            continue

        # Yahoo Finance HK ticker: 4-digit zero-padded format (00001 → 0001.HK)
        try:
            yahoo_ticker = f"{int(code5d):04d}.HK"
        except ValueError:
            log.warning(f"  [{seq}/{len(stocks)}] {name}({symbol}) → 无法解析代码，跳过")
            no_data += 1
            continue

        krows = fetch_kline(yahoo_ticker)

        if krows:
            db_rows = [(stock_id, r[0], r[1], r[2], r[3], r[4], r[5]) for r in krows]
            n = upsert_prices(conn, db_rows)
            total_rows += n
        else:
            no_data += 1

        save_checkpoint("hk", code5d)
        pct = seq / len(stocks) * 100
        log.info(f"  [{seq}/{len(stocks)} {pct:.1f}%] {name}({symbol}) → {n if krows else 0}行")
        time.sleep(cfg["delay_hk"])

    clear_checkpoint("hk")
    if conn:
        conn.close()
    log.info(f"港股完成: 写入 {total_rows} 行，无数据(停牌/错误) {no_data} 只")


# ─── 美股 (Yahoo Finance + 代理) ─────────────────────────────────────────────

# 维护一份内置列表，用于首次入库或 DB 为空时
_US_TICKERS_BUILTIN = sorted(set([
    # 科技
    "AAPL","MSFT","GOOGL","GOOG","AMZN","NVDA","META","TSLA","AVGO","ADBE",
    "CSCO","ORCL","CRM","ACN","IBM","INTC","AMD","QCOM","TXN","AMAT","MU",
    "NOW","INTU","SNPS","CDNS","PANW","CRWD","NET","ZS","DDOG","SNOW","PLTR",
    "UBER","LYFT","DASH","ABNB","COIN","HOOD","RBLX","SNAP","PINS","MTCH",
    "TWLO","ZM","DOCU","TEAM","WDAY","OKTA","SPLK","ESTC","MDB","DKNG","PENN",
    "MELI","SE","GLBE","VEEV","CDAY","APP","LNW","SMCI","ARM","TSM",
    "ASML","TTD","NFLX","DIS","CMCSA","T","VZ","TMUS","CHTR","WBD",
    "PARA","FOX","FOXA","EA","TTWO","U","GPRO","AMC","BB","NOK",
    "INFY","WIT",
    # 中概
    "BABA","BIDU","JD","PDD","NTES","NIO","XPEV","LI","BILI","TCEHY","TAL",
    "EDU","TCOM","VIPS","BEKE","IQ","ZTO","YUMC","GRPN","MNSO",
    "HUYA","DOYU","YY","KC","IREN","RIOT","MARA",
    # 金融
    "SQ","PYPL","AFRM","SOFI","UPST","MCO","SCHW",
    "JPM","BAC","WFC","C","GS","MS","BLK","V","MA","AXP","DFS","COF","SYF",
    "ALL","PGR","TRV","MET","PRU","AFL","FIS","FISV","GPN","PAYX","ADP",
    "NDAQ","ICE","CB","MMC","AON","BRO","MQ","NU",
    # 能源/工业
    "SLB","HAL","DVN","EOG","MPC","PSX","VLO","WMB","KMI","OXY","COP","CVX","XOM",
    "UNP","CSX","NSC","GWW","FAST","PCAR","DE","CAT","EMR","ETN","HON",
    "UPS","FDX","RTX","BA","LMT","NOC","GD","LHX","TDG","PH","ROK","ITW","MMM","GE",
    # 公用/REITs
    "AMT","PLD","CCI","EQIX","PSA","SPG","WELL","DLR","AVB","EQR",
    "D","SO","DUK","AEP","SRE","PCG","EIX","FE","XEL","EXC","NEE",
    # 消费/零售
    "HSY","K","MDLZ","GIS","KMB","CL","PG","KO","PEP","MCD","SBUX","WMT",
    "COST","TGT","LOW","HD","NKE","LULU","RL","HAS","MAT","EL","CHD","CLX",
    "LYB","PPG","SHW","RPM","CF","NEM","GOLD","NUE","STLD","FCX","DOW","DD",
    "ECL","IFF","APD","LIN",
    # 医药
    "ABT","ABBV","JNJ","UNH","PFE","LLY","MRK","BMY","AMGN","GILD",
    "VRTX","REGN","BIIB","MRNA","AZN","ISRG","MDT","SYK","ZTS","BSX","EW",
    "DXCM","TMO","DHR","IQV","IDXX","LH","DGX","RMD","STE","VTRS","CNC",
    "CI","HUM","ELV","MOH","WBA","CVS","MCK","CAH","ABC",
    # 新能源
    "ENPH","SEDG","FSLR","RUN","BE","PLUG","BLDP","FCEL","CLNE",
    # 媒体/SaaS
    "ROKU","HUBS","FVRR","UPWK","WIX","GLOB","EPAM","PATH","BKNG","EXPE",
    "MAR","HLT","RCL","CCL","NCLH","WYNN","MGM","W","STNE","PAGS",
    # 生物科技
    "BNTX","NVAX","INO","SGEN",
]))


def fetch_us_stocks(cfg: dict, start: str, end: str, dry_run: bool, log: logging.Logger) -> None:
    log.info(f"=== 美股 (Yahoo Finance) | {start} ~ {end} ===")

    # 代理必须在 import yfinance 之前注入 os.environ
    proxy_url = cfg.get("proxy_url", "").strip()
    if proxy_url:
        os.environ["HTTP_PROXY"]  = proxy_url
        os.environ["HTTPS_PROXY"] = proxy_url
        log.info(f"已设置代理: {proxy_url}")
    else:
        log.warning("未配置 proxy_url，从大陆直连 Yahoo Finance 可能失败")

    try:
        import yfinance as yf
    except ImportError:
        log.error("缺少依赖: pip install yfinance")
        return

    conn   = None if dry_run else get_conn(cfg)
    id_map: dict[str, int] = {}  # symbol(.US) → stock_id
    skip_ids: set = set()

    if not dry_run:
        cur = conn.cursor()
        cur.execute("SELECT id, symbol FROM stocks WHERE market='US'")
        id_map = {row[1]: row[0] for row in cur.fetchall()}
        cur.close()
        skip_ids = build_skip_set(conn, list(id_map.values()), start, end)
        if skip_ids:
            log.info(f"已有完整数据，跳过 {len(skip_ids)} 只")
        # DB 有记录就用 DB，否则用内置列表（以 DB 为主，避免重复抓取无人关注的股票）
        tickers = list(_US_TICKERS_BUILTIN)
    else:
        # dry-run: 只用少量 ticker 验证流程
        tickers = ["AAPL", "MSFT", "NVDA", "BABA", "TSM"]
        log.info(f"[dry-run] 只处理 {tickers}")

    def ensure_stock(ticker: str) -> Optional[int]:
        sym = f"{ticker}.US"
        if sym in id_map:
            return id_map[sym]
        if dry_run:
            return -1  # 哑 id
        name = ticker
        try:
            info = yf.Ticker(ticker).fast_info  # fast_info 不需要额外请求
            name = getattr(info, "company_officers", None) or ticker
        except Exception:
            pass
        cur = conn.cursor()
        cur.execute(
            "INSERT IGNORE INTO stocks (symbol, name, market, currency) VALUES (%s,%s,'US','USD')",
            (sym, name),
        )
        conn.commit()
        cur.execute("SELECT id FROM stocks WHERE symbol=%s", (sym,))
        row = cur.fetchone()
        cur.close()
        if row:
            id_map[sym] = row[0]
            return row[0]
        return None

    def fetch_kline(ticker: str) -> list:
        """
        返回 [(trade_date_str, open, close, high, low, volume), ...]
        Yahoo Finance 返回已复权价格（adjustedClose），列名: Open/High/Low/Close/Volume
        """
        t    = yf.Ticker(ticker)
        hist = t.history(start=start, end=end, interval="1d", auto_adjust=True)
        if hist.empty:
            return []
        rows = []
        for dt, row in hist.iterrows():
            try:
                rows.append((
                    dt.strftime("%Y-%m-%d"),
                    round(float(row["Open"]),  4),
                    round(float(row["Close"]), 4),
                    round(float(row["High"]),  4),
                    round(float(row["Low"]),   4),
                    int(float(row["Volume"])),
                ))
            except (ValueError, KeyError):
                continue
        return rows

    total      = len(tickers)
    total_rows = 0
    errors     = []
    checkpoint = load_checkpoint("us")
    skipped    = checkpoint is not None

    for seq, ticker in enumerate(tickers, 1):
        if skipped:
            if ticker != checkpoint:
                continue
            skipped = False
            continue

        stock_id = ensure_stock(ticker)
        if stock_id is None:
            errors.append((ticker, "无法获取 stock_id"))
            continue

        if not dry_run and stock_id in skip_ids:
            continue

        try:
            krows = fetch_kline(ticker)
            n = len(krows)
            if krows and not dry_run:
                db_rows = [(stock_id, r[0], r[1], r[2], r[3], r[4], r[5]) for r in krows]
                n = upsert_prices(conn, db_rows)
                total_rows += n
            elif dry_run and krows:
                log.info(f"  [dry] {ticker}: {len(krows)} 行，最新 {krows[-1][0]} close={krows[-1][2]}")
        except Exception as e:
            msg = str(e)[:100]
            errors.append((ticker, msg))
            log.warning(f"  ✗ {ticker}: {msg}")

        save_checkpoint("us", ticker)
        pct = seq / total * 100
        log.info(f"  [{seq}/{total} {pct:.1f}%] {ticker}.US → {n if krows else 0}行")
        time.sleep(cfg["delay_us"])

    clear_checkpoint("us")
    if conn:
        conn.close()

    log.info(f"美股完成: 写入 {total_rows} 行，失败 {len(errors)} 只")
    for t, msg in errors[:10]:
        log.warning(f"  ✗ {t}: {msg}")


# ─── 自动选市场 ───────────────────────────────────────────────────────────────

def auto_market() -> str:
    """
    按上海时间判断当前应抓哪个市场：
      15:30–16:30 → a（A股收盘后）
      16:30–17:30 → hk（港股收盘后）
      01:00–06:00 → us（美股收盘后，北京次日凌晨）
      其他        → all
    """
    cst_hour = (datetime.utcnow() + timedelta(hours=8)).hour
    if 15 <= cst_hour < 16:
        return "a"
    if 16 <= cst_hour < 17:
        return "hk"
    if 1 <= cst_hour < 6:
        return "us"
    return "all"


# ─── 指数/商品/汇率 (Yahoo Finance + Sina) ─────────────────────────────────

_INDICES = [
    # 中国 — Sina
    ('sina','sh000001','000001.SH','上证指数','SH','CNY'),
    ('sina','sz399001','399001.SZ','深证成指','SZ','CNY'),
    ('sina','sz399006','399006.SZ','创业板指','SZ','CNY'),
    # 香港 — Yahoo / akshare (HSTECH: yfinance 无历史数据，改用 akshare 东方财富)
    ('yf','^HSI','HSI.HK','恒生指数','HK','HKD'),
    ('yf','^HSCE','HSCE.HK','国企指数','HK','HKD'),
    ('ak','HSTECH','HSTECH.HK','恒生科技','HK','HKD'),
    # 美国 — Yahoo
    ('yf','^GSPC','GSPC.US','标普500','US','USD'),
    ('yf','^DJI','DJI.US','道琼斯工业','US','USD'),
    ('yf','^IXIC','IXIC.US','纳斯达克综合','US','USD'),
    # 全球 — Yahoo
    ('yf','^N225','N225.JP','日经225','JP','JPY'),
    ('yf','^KS11','KS11.KR','韩国KOSPI','KR','KRW'),
    ('yf','^FTSE','FTSE.GB','富时100','GB','GBP'),
    ('yf','^GDAXI','GDAXI.DE','德国DAX','DE','EUR'),
    ('yf','^FCHI','FCHI.FR','法国CAC40','FR','EUR'),
    ('yf','^TWII','TWII.TW','台湾加权','TW','TWD'),
    ('yf','^STI','STI.SG','新加坡STI','SG','SGD'),
    ('yf','^BSESN','BSESN.IN','印度SENSEX','IN','INR'),
    ('yf','^AXJO','AXJO.AU','澳洲ASX200','AU','AUD'),
    ('yf','^GSPTSE','GSPTSE.CA','加拿大TSX','CA','CAD'),
    ('yf','^BVSP','BVSP.BR','巴西Bovespa','BR','BRL'),
    # 商品/汇率 — Yahoo
    ('yf','DX-Y.NYB','DXY.IDX','美元指数','IDX','USD'),
    ('yf','GC=F','XAU.CMD','黄金/美元','CMD','USD'),
    ('yf','BTC-USD','BTC.CCY','比特币/美元','CCY','USD'),
    ('yf','CL=F','CL.CMD','WTI原油','CMD','USD'),
]


def fetch_indices(cfg: dict, start: str, end: str, dry_run: bool, log: logging.Logger) -> None:
    log.info(f"=== 指数/商品 (Yahoo+Sina) | {start} ~ {end} ===")

    proxy_url = cfg.get("proxy_url", "").strip()
    if proxy_url:
        os.environ["HTTP_PROXY"] = proxy_url
        os.environ["HTTPS_PROXY"] = proxy_url

    conn = None if dry_run else get_conn(cfg)

    def ensure_stock(db_symbol, name, market, currency):
        if dry_run:
            return -1
        cur = conn.cursor()
        cur.execute("SELECT id FROM stocks WHERE symbol=%s", (db_symbol,))
        row = cur.fetchone()
        if row:
            cur.close()
            return row[0]
        try:
            cur.execute(
                "INSERT IGNORE INTO stocks (symbol, name, market, currency) VALUES (%s,%s,%s,%s)",
                (db_symbol, name, market, currency))
            conn.commit()
        except Exception:
            pass
        cur.execute("SELECT id FROM stocks WHERE symbol=%s", (db_symbol,))
        row = cur.fetchone()
        cur.close()
        return row[0] if row else None

    def fetch_sina(code, start, end):
        """Sina K-line via JSON API"""
        import urllib.request, json
        url = (f"https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
               f"CN_MarketData.getKLineData?symbol={code}&scale=240&ma=no&datalen=5000")
        for attempt in range(3):
            try:
                req = urllib.request.Request(url, headers={
                    'User-Agent': 'Mozilla/5.0',
                    'Referer': 'https://finance.sina.com.cn/',
                })
                with urllib.request.urlopen(req, timeout=15) as resp:
                    data = json.loads(resp.read().decode('utf-8'))
                if not isinstance(data, list):
                    raise ValueError(f"Unexpected response")
                rows = []
                for item in data:
                    d = item['day']
                    if start <= d <= end:
                        rows.append((
                            d,
                            round(float(item['open']), 4),
                            round(float(item['close']), 4),
                            round(float(item['high']), 4),
                            round(float(item['low']), 4),
                            int(float(item['volume'])),
                        ))
                return rows
            except Exception:
                if attempt < 2:
                    time.sleep(2)
                else:
                    raise
        return []

    def fetch_yf(ticker, start, end):
        """Yahoo Finance K-line, returns same format as fetch_us_stocks"""
        import yfinance as yf
        t = yf.Ticker(ticker)
        hist = t.history(start=start, end=end, interval="1d", auto_adjust=True)
        if hist.empty:
            return []
        rows = []
        for dt, row in hist.iterrows():
            try:
                o = float(row["Open"])
                c = float(row["Close"])
                h = float(row["High"])
                l = float(row["Low"])
                v = int(float(row["Volume"]))
                if any(math.isnan(x) for x in [o, c, h, l]):
                    continue
                rows.append((dt.strftime("%Y-%m-%d"), round(o,4), round(c,4), round(h,4), round(l,4), v))
            except (ValueError, KeyError):
                continue
        return rows

    def fetch_ak(symbol, start, end):
        """akshare 东方财富港股指数日K线 (专用于 HSTECH 等 yfinance 无历史数据的指数)
        东方财富为国内直连，调用前临时清除代理环境变量。
        """
        try:
            import akshare as ak
        except ImportError:
            log.error("缺少依赖: pip install akshare")
            return []
        _proxy_keys = ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy")
        _saved = {k: os.environ.pop(k, None) for k in _proxy_keys}
        try:
            df = ak.stock_hk_index_daily_em(symbol=symbol)
        except Exception as e:
            log.warning(f"akshare 抓取 {symbol} 失败: {e}")
            return []
        finally:
            for k, v in _saved.items():
                if v is not None:
                    os.environ[k] = v
        if df.empty:
            return []
        rows = []
        for _, row in df.iterrows():
            try:
                d = row["date"].strftime("%Y-%m-%d")
                if not (start <= d <= end):
                    continue
                o = round(float(row["open"]), 4)
                c = round(float(row["latest"]), 4)
                h = round(float(row["high"]), 4)
                l = round(float(row["low"]), 4)
                if any(math.isnan(x) for x in [o, c, h, l]):
                    continue
                rows.append((d, o, c, h, l, 0))  # akshare HK指数不含成交量
            except (ValueError, KeyError):
                continue
        return rows

    def _fetch_index(src, ticker, start, end):
        if src == "yf":
            return fetch_yf(ticker, start, end)
        if src == "sina":
            return fetch_sina(ticker, start, end)
        if src == "ak":
            return fetch_ak(ticker, start, end)
        return []

    if dry_run:
        log.info(f"[dry-run] 测试 {len(_INDICES)} 个指数 K 线请求")
        for src, ticker, db_sym, name, mkt, cur in _INDICES[:5]:
            rows = _fetch_index(src, ticker, start, end)
            log.info(f"  {name}({db_sym}) → {len(rows)} 行")
        return

    total_rows = 0
    errors = 0
    import yfinance as yf
    for seq, (src, ticker, db_sym, name, mkt, cur) in enumerate(_INDICES, 1):
        sid = ensure_stock(db_sym, name, mkt, cur)
        if not sid:
            errors += 1
            continue
        try:
            rows = _fetch_index(src, ticker, start, end)
            if rows:
                db_rows = [(sid, r[0], r[1], r[2], r[3], r[4], r[5]) for r in rows]
                n = upsert_prices(conn, db_rows)
                total_rows += n
                pct = seq / len(_INDICES) * 100
                log.info(f"  [{seq}/{len(_INDICES)} {pct:.1f}%] {name}({db_sym}) → {n}行")
            else:
                pct = seq / len(_INDICES) * 100
                log.info(f"  [{seq}/{len(_INDICES)} {pct:.1f}%] {name}({db_sym}) → 无数据")
        except Exception as e:
            errors += 1
            pct = seq / len(_INDICES) * 100
            log.info(f"  [{seq}/{len(_INDICES)} {pct:.1f}%] {name}({db_sym}) → 错误: {e}")
        time.sleep(0.3)

    if conn:
        conn.close()
    log.info(f"指数完成: 写入 {total_rows} 行，错误 {errors}")


# ─── 入口 ─────────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(
        description="Investory 三市场日K线统一抓取脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument(
        "-m", "--market",
        choices=["a", "sh", "sz", "hk", "us", "idx", "all", "auto"],
        default="auto",
        help="抓取市场 (默认: auto，按当前时间自动判断；sh/sz 单独抓沪/深)",
    )
    p.add_argument(
        "--days", type=int, default=None,
        help="抓取最近 N 个日历日（覆盖 config.ini 的 days_back）",
    )
    p.add_argument(
        "--start", type=str, default=None,
        help="起始日期 YYYY-MM-DD（覆盖 --days）",
    )
    p.add_argument(
        "--end", type=str, default=None,
        help="结束日期 YYYY-MM-DD（覆盖 --days，默认今天）",
    )
    p.add_argument(
        "--discover", action="store_true",
        help="港股: 扫描腾讯 API 发现新股补入 stocks 表（仅 -m hk/all 有效）",
    )
    p.add_argument(
        "--dry-run", action="store_true",
        help="测试模式：跑完整流程但不写 DB",
    )
    p.add_argument(
        "-v", "--verbose", action="store_true",
        help="显示 DEBUG 级日志",
    )
    return p.parse_args()


def main():
    args = parse_args()
    log  = setup_logging(args.verbose)
    cfg  = load_config()

    if args.days is not None:
        cfg["days_back"] = args.days

    if args.start:
        start = args.start
        end   = args.end if args.end else datetime.today().strftime("%Y-%m-%d")
    else:
        days_back = cfg["days_back"]
        start = (datetime.today() - timedelta(days=days_back)).strftime("%Y-%m-%d")
        end   = datetime.today().strftime("%Y-%m-%d")

    market = args.market if args.market != "auto" else auto_market()
    do_sh  = market in ("sh", "a", "all")
    do_sz  = market in ("sz", "a", "all")
    do_hk  = market in ("hk", "all")
    do_us  = market in ("us", "all")
    do_idx = market in ("idx", "all")

    mode = "[DRY-RUN] " if args.dry_run else ""
    log.info(f"{mode}抓取市场: {market.upper()} | 区间: {start} ~ {end}")

    if not args.dry_run:
        # 快速验证 DB 连通性，避免抓完数据才发现写不进去
        try:
            conn = get_conn(cfg)
            conn.ping()
            conn.close()
            log.info("DB 连接正常")
        except Exception as e:
            log.error(f"DB 连接失败: {e}")
            log.error("请检查 config.ini 中的数据库配置，或使用 --dry-run 跳过 DB 验证")
            sys.exit(1)

    t0 = time.time()

    if do_sh and do_sz:
        # 沪深一起抓，checkpoint key = "a"（与定时任务兼容）
        fetch_a_shares(cfg, start, end, args.dry_run, log,
                       market_filter=None, checkpoint_key="a")
    elif do_sh:
        fetch_a_shares(cfg, start, end, args.dry_run, log,
                       market_filter="SH", checkpoint_key="sh")
    elif do_sz:
        fetch_a_shares(cfg, start, end, args.dry_run, log,
                       market_filter="SZ", checkpoint_key="sz")

    if do_hk:
        fetch_hk_stocks(cfg, start, end, args.dry_run, args.discover, log)

    if do_us:
        fetch_us_stocks(cfg, start, end, args.dry_run, log)

    if do_idx:
        fetch_indices(cfg, start, end, args.dry_run, log)

    elapsed = time.time() - t0
    log.info(f"全部完成，耗时 {elapsed:.1f}s")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已中断。")
        sys.exit(0)
    except Exception:
        traceback.print_exc()
        sys.exit(1)
