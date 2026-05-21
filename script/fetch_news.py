#!/usr/bin/env python3
"""
每日世界新闻晨报抓取脚本
数据源: Reuters RSS (Business + World)
流程: 拉取 → 财经/地缘关键词评分 → 国家识别 → 写入 world_news 表
"""
from __future__ import annotations

import configparser
import json
import os
import subprocess
import sys
import time
import urllib.parse
from datetime import date, datetime
from pathlib import Path

import feedparser
import pymysql
import requests

SCRIPT_DIR  = Path(__file__).parent
CONFIG_FILE = SCRIPT_DIR / "config.ini"

RSS_FEEDS = [
    ("Guardian World",    "https://www.theguardian.com/world/rss"),
    ("Guardian Business", "https://www.theguardian.com/business/rss"),
    ("Bloomberg Markets", "https://feeds.bloomberg.com/markets/news.rss"),
    ("Yahoo Finance",     "https://finance.yahoo.com/news/rssindex"),
    ("CNBC International","https://www.cnbc.com/id/100727362/device/rss/rss.html"),
]

FINANCE_WORDS = {
    # Markets & trading
    "market", "stock", "shares", "equity", "equities", "trade", "trading",
    "bull", "bear", "rally", "sell-off", "selloff", "dip", "plunge", "surge",
    "soar", "tumble", "volatility", "index", "futures", "options", "dividend",
    "buyback", "ipo", "etf", "hedge fund", "short sell", "margin call",
    "wall street", "nasdaq", "dow jones", "s&p", "ftse", "nikkei",
    # Economy
    "gdp", "inflation", "deflation", "stagflation", "cpi", "ppi", "pmi",
    "unemployment", "payroll", "consumer spend", "retail sales", "housing",
    "mortgage", "manufacturing", "service sector", "industrial output",
    "recession", "downturn", "slowdown", "contraction", "expansion",
    "gdp growth", "economic growth", "economic data", "macro", "micro",
    # Central banks & monetary policy
    "federal reserve", "the fed", "central bank", "ecb", "bank of england",
    "bank of japan", "pboc", "rba", "rbi", "interest rate", "rate hike",
    "rate cut", "quantitative easing", "qe", "tapering", "tightening",
    "dovish", "hawkish", "basis point", "yield curve", "basis points",
    "monetary policy", "monetary", "fiscal", "stimulus", "austerity",
    # Currencies
    "dollar", "currency", "forex", "exchange rate", "devaluation",
    "depreciation", "appreciation", "strong dollar", "weak dollar",
    "yuan", "renminbi", "yen", "pound", "sterling", "euro", "ruble",
    # Commodities & energy
    "oil", "crude", "brent", "wti", "natural gas", "lng", "gasoline",
    "gold", "silver", "copper", "lithium", "nickel", "iron ore",
    "steel", "aluminum", "commodity", "opec", "energy price",
    "wheat", "corn", "soybean", "agriculture",
    # Bonds & debt
    "bond", "treasury", "yield", "sovereign debt", "credit default",
    "junk bond", "investment grade", "spread", "debt ceiling",
    "government debt", "deficit", "surplus", "fiscal deficit",
    # Corporate
    "earnings", "revenue", "profit", "loss", "merger", "acquisition",
    "takeover", "spin-off", "bankrupt", "insolvent", "restructuring",
    "layoff", "hiring", "executive", "ceo", "board", "shareholder",
    # Tech & innovation
    "semiconductor", "chip", "ai", "artificial intelligence",
    "big tech", "faang", "nvidia", "apple", "microsoft", "google",
    "amazon", "meta", "tesla", "openai", "chatgpt", "blockchain",
    # Crypto
    "bitcoin", "crypto", "ethereum", "defi", "nft", "stablecoin",
    "digital currency", "mining",
    # China-specific economy
    "property market", "evergrande", "country garden", "local government debt",
    "shadow bank", "capital flight", "onshore", "offshore", "pmi",
    # Trade & sanctions
    "tariff", "trade war", "import", "export", "supply chain",
    "sanction", "embargo", "trade deal", "free trade", "protectionism",
    "wto", "nafta", "usmca", "rcep", "cptpp",
    # Banking & finance
    "bank", "banking", "finance", "investment", "capital", "lending",
    "loan", "credit", "liquidity", "solvency", "bailout", "bail-in",
    "systemic risk", "contagion", "stress test", "capital requirement",
}

GEO_WORDS = {
    # Armed conflict
    "war", "warfare", "conflict", "attack", "strike", "airstrike", "bombing",
    "offensive", "counter-offensive", "invasion", "incursion", "occupation",
    "insurgency", "guerrilla", "militia", "paramilitary", "mercenary",
    "civil war", "proxy war", "arms race", "militarization", "demilitarize",
    "ceasefire", "armistice", "truce", "peace talk", "peace deal",
    "peacekeeping", "mediation", "negotiation", "withdrawal", "pullout",
    "nato", "north atlantic treaty",
    # Weapons & military
    "military", "missile", "drone", "artillery", "tank", "fighter jet",
    "warship", "submarine", "nuclear weapon", "nuclear program", "nuclear deal",
    "ballistic", "hypersonic", "arms deal", "weapon", "defense system",
    "troop", "soldier", "deployment", "mobilization", "conscription",
    # Political
    "election", "vote", "ballot", "referendum", "campaign", "candidate",
    "president", "prime minister", "minister", "parliament", "congress",
    "senate", "legislature", "coalition", "opposition", "ruling party",
    "impeachment", "resignation", "corruption", "bribery", "investigation",
    "indictment", "prosecution", "conviction", "scandal", "whistleblower",
    "authoritarian", "democratic", "regime", "junta", "dictator", "autocrat",
    "coup", "uprising", "revolution", "protest", "demonstration", "riot",
    "crackdown", "crack down", "martial law", "state of emergency", "curfew",
    # Diplomacy & international
    "diplomat", "diplomacy", "embassy", "consulate", "ambassador",
    "summit", "bilateral", "multilateral", "alliance", "coalition",
    "united nations", "un security council", "security council",
    "veto", "resolution", "treaty", "accord", "pact", "agreement",
    "memorandum of understanding",
    # Security & intelligence
    "intelligence", "espionage", "spy", "cyber attack", "cyber warfare",
    "hacking", "ransomware", "disinformation", "propaganda", "misinformation",
    "information warfare", "hybrid war", "terrorism", "terrorist", "extremist",
    "radical", "jihadist", "counterterrorism",
    # Geopolitical hotspots
    "taiwan strait", "south china sea", "east china sea", "senkaku",
    "kashmir", "donbas", "crimea", "gaza", "west bank", "golan heights",
    "kurdish", "uyghur", "xinjiang", "hong kong protest",
    # Humanitarian & migration
    "refugee", "migrant", "asylum", "deportation", "border control",
    "visa ban", "travel ban", "humanitarian", "aid", "famine",
    "genocide", "war crime", "human rights", "freedom of",
    # Sanctions & trade restrictions
    "sanction", "embargo", "blacklist", "asset freeze", "travel ban",
    "export control", "technology transfer", "decoupling", "de-risk",
    "geopolitical tension", "geopolitical risk",
}

# country_code → keywords to detect (lowercase)
COUNTRY_HINTS: dict[str, list[str]] = {
    "US": ["united states", "america", "american", "washington", "biden", "trump",
           "federal reserve", "wall street", "nasdaq", "u.s.", "the fed"],
    "CN": ["china", "chinese", "beijing", "shanghai", "xi jinping", "pboc",
           "yuan", "renminbi", "ccp", "taiwan strait", "hong kong"],
    "TW": ["taiwan", "taipei"],
    "RU": ["russia", "russian", "moscow", "putin", "kremlin", "ruble"],
    "UA": ["ukraine", "ukrainian", "kyiv", "zelensky"],
    "DE": ["germany", "german", "berlin", "bundesbank", "scholz"],
    "GB": ["britain", "british", " uk ", "united kingdom", "london", "bank of england", "sterling"],
    "FR": ["france", "french", "paris", "macron", "ecb", "eurozone"],
    "JP": ["japan", "japanese", "tokyo", "boj", "bank of japan", "yen", "nikkei"],
    "KR": ["south korea", "korean", "seoul"],
    "IN": ["india", "indian", "new delhi", "rbi", "rupee", "modi"],
    "AU": ["australia", "australian", "sydney", "rba"],
    "CA": ["canada", "canadian", "ottawa", "toronto", "bank of canada"],
    "BR": ["brazil", "brazilian", "brasilia", "lula"],
    "SG": ["singapore", "singaporean", "mas"],
    "SA": ["saudi", "saudi arabia", "riyadh", "opec", "aramco"],
    "IR": ["iran", "iranian", "tehran"],
    "IL": ["israel", "israeli", "tel aviv", "gaza", "hamas", "netanyahu"],
    "TR": ["turkey", "turkish", "ankara", "erdogan"],
    "MX": ["mexico", "mexican", "peso"],
}


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
    }


def score_and_classify(text: str) -> tuple[int, str]:
    lower = text.lower()
    fin = sum(1 for w in FINANCE_WORDS if w in lower)
    geo = sum(1 for w in GEO_WORDS if w in lower)
    return fin + geo, ("finance" if fin >= geo else "geopolitics")


def detect_country(text: str) -> str | None:
    lower = " " + text.lower() + " "
    best_code, best_count = None, 0
    for code, hints in COUNTRY_HINTS.items():
        count = sum(1 for h in hints if h in lower)
        if count > best_count:
            best_count, best_code = count, code
    return best_code if best_count > 0 else None


def translate(text: str, proxy_url: str) -> str:
    """Translate English text to Simplified Chinese via Google Translate API with retry."""
    if not text or len(text) < 3:
        return text
    url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=zh-CN&dt=t&q=" + urllib.parse.quote(text)
    for attempt in range(3):
        try:
            r = subprocess.run(["curl", "-s", "--max-time", "12", "--proxy", proxy_url, url],
                               capture_output=True, text=True, timeout=15)
            body = r.stdout.strip()
            if not body:
                time.sleep(1.5)
                continue
            parts = json.loads(body)[0]
            result = "".join(p[0] for p in parts if p[0] is not None)
            return result.strip()
        except Exception:
            if attempt < 2:
                time.sleep(1.5)
    return text  # fallback to original


def parse_published(entry) -> datetime:
    try:
        t = entry.get("published_parsed")
        if t:
            return datetime(*t[:6])
    except Exception:
        pass
    return datetime.utcnow()


def fetch_feed(url: str, proxies: dict) -> list:
    resp = requests.get(url, proxies=proxies, timeout=20,
                        headers={"User-Agent": "Mozilla/5.0"})
    resp.raise_for_status()
    return feedparser.parse(resp.text).entries


def main():
    cfg = load_config()
    proxies = {}
    if cfg["proxy_url"]:
        proxies = {"http": cfg["proxy_url"], "https": cfg["proxy_url"]}

    conn = pymysql.connect(
        host=cfg["db_host"], port=cfg["db_port"],
        user=cfg["db_user"], password=cfg["db_password"],
        database=cfg["db_name"], charset="utf8mb4",
    )
    cur = conn.cursor()

    today = date.today()
    entries: list[dict] = []

    for source_name, url in RSS_FEEDS:
        try:
            raw = fetch_feed(url, proxies)
            kept = 0
            for entry in raw:
                title   = entry.get("title", "").strip()
                summary = entry.get("summary", entry.get("description", "")).strip()
                link    = entry.get("link", "")
                if not title or not link:
                    continue
                text = title + " " + summary
                score, category = score_and_classify(text)
                if score < 2:
                    continue
                country = detect_country(text)
                entries.append({
                    "title":        title[:500],
                    "source":       source_name,
                    "url":          link[:1000],
                    "summary":      summary[:2000],
                    "published_at": parse_published(entry),
                    "category":     category,
                    "score":        score,
                    "country_code": country,
                    "fetched_date": today,
                })
                kept += 1
            print(f"[{source_name}] 获取 {len(raw)} 条，评分通过 {kept} 条")
        except Exception as e:
            print(f"[{source_name}] 拉取失败: {e}", file=sys.stderr)

    # Top 20 by score
    entries.sort(key=lambda x: x["score"], reverse=True)
    top = entries[:20]

    # Translate titles to Chinese
    proxy_url = cfg["proxy_url"] or "socks5h://127.0.0.1:7897"
    for e in top:
        e["title"] = translate(e["title"], proxy_url)

    # Replace today's existing entries with fresh translated data
    cur.execute("DELETE FROM world_news WHERE fetched_date = %s", (today,))

    inserted = 0
    for e in top:
        try:
            cur.execute("""
                INSERT INTO world_news
                  (title, source, url, summary, published_at, category, score, country_code, fetched_date)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (e["title"], e["source"], e["url"], e["summary"],
                  e["published_at"], e["category"], e["score"],
                  e["country_code"], e["fetched_date"]))
            inserted += 1
        except Exception as ex:
            print(f"写入失败: {ex}", file=sys.stderr)

    conn.commit()

    cur.execute("DELETE FROM world_news WHERE fetched_date < CURDATE() - INTERVAL 7 DAY")
    conn.commit()
    cur.close()
    conn.close()

    with_loc = sum(1 for e in top if e["country_code"])
    print(f"世界新闻完成: 写入 {inserted} 行，有地理位置 {with_loc} 条，无数据(低分/错误) {len(entries) - inserted} 只")


if __name__ == "__main__":
    main()
