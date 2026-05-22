#!/usr/bin/env python3
"""
Investory 组合风格诊断引擎

分析持仓的风格偏好（价值/成长/科技/防御等）、行业集中度、
风险特征，并给出数据驱动的优化建议。

用法:
    python3 portfolio_style_analyzer.py --portfolio-id <id> [--mode full|quick]

输出 JSON 到 stdout，供 Java 后端解析缓存。
"""

import argparse
import configparser
import json
import math
import os
import sys
import traceback
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).parent
CONFIG_FILE = SCRIPT_DIR / "config.ini"


def load_config() -> dict:
    cfg = configparser.ConfigParser()
    if CONFIG_FILE.exists():
        cfg.read(CONFIG_FILE, encoding="utf-8")
    def get(section, key, default=""):
        try: return cfg.get(section, key).strip()
        except: return default
    return {
        "db_host": os.getenv("DB_HOST", get("database", "host", "localhost")),
        "db_port": int(os.getenv("DB_PORT", get("database", "port", "3306"))),
        "db_name": os.getenv("DB_NAME", get("database", "name", "investory")),
        "db_user": os.getenv("DB_USER", get("database", "user", "root")),
        "db_password": os.getenv("DB_PASSWORD", get("database", "password", "")),
    }


def get_conn(cfg: dict):
    import pymysql
    return pymysql.connect(
        host=cfg["db_host"], port=cfg["db_port"], database=cfg["db_name"],
        user=cfg["db_user"], password=cfg["db_password"],
        charset="utf8mb4", autocommit=True,
    )


# ── Style classification ─────────────────────────────────────────────────

# Style categories
STYLE_TECH      = "科技成长"
STYLE_FINANCE   = "金融价值"
STYLE_CONSUMER  = "消费防御"
STYLE_ENERGY    = "能源材料"
STYLE_HEALTH    = "医疗健康"
STYLE_REALESTATE = "地产基建"
STYLE_MIXED     = "综合其他"

# Keyword-based classification from stock name
STYLE_KEYWORDS = {
    STYLE_TECH: [
        "科技", "软件", "网络", "数据", "信息", "通信", "电子", "半导体",
        "芯片", "光电", "互联网", "电商", "游戏", "传媒", "数字",
        "TECH", "SOFTWARE", "INTERNET", "SEMICONDUCTOR", "COMPUTER",
        "腾讯", "阿里", "百度", "京东", "美团", "网易", "小米", "拼多多",
        "苹果", "微软", "谷歌", "英伟达", "特斯拉", "META", "AMAZON",
        "台积电", "联发科", "高通", "AMD", "英特尔",
    ],
    STYLE_FINANCE: [
        "银行", "保险", "证券", "金融", "信托", "投资", "基金", "期货",
        "招商", "工商", "建设", "农业", "中国银行", "交通银行", "兴业",
        "浦发", "民生", "中信", "平安", "人寿", "太保", "华泰", "国泰",
        "海通", "广发", "东方", "JPMORGAN", "BANK", "INSURANCE",
    ],
    STYLE_CONSUMER: [
        "食品", "饮料", "白酒", "啤酒", "乳业", "消费", "零售", "家电",
        "汽车", "服装", "家居", "旅游", "酒店", "餐饮", "电商",
        "茅台", "五粮液", "伊利", "蒙牛", "海天", "美的", "格力",
        "海尔", "比亚迪", "长城", "吉利", "NIKE", "COCA", "PEPSI",
        "沃尔玛", "好事多", "星巴克", "麦当劳",
    ],
    STYLE_ENERGY: [
        "能源", "石油", "石化", "煤炭", "电力", "天然气", "新能源",
        "光伏", "风电", "锂电", "电池", "矿产", "钢铁", "有色", "化工",
        "中国石油", "中国石化", "中海油", "神华", "长江电力",
        "宁德", "隆基", "通威", "赣锋", "天齐", "华友",
        "EXXON", "CHEVRON", "SHELL", "TESLA",
    ],
    STYLE_HEALTH: [
        "医药", "医疗", "生物", "制药", "健康", "疫苗", "基因",
        "恒瑞", "迈瑞", "药明", "百济", "爱尔", "智飞", "长春高新",
        "JOHNSON", "PFIZER", "MERCK", "ABBVIE", "NOVARTIS",
    ],
    STYLE_REALESTATE: [
        "地产", "基建", "建筑", "房地产", "万科", "保利", "碧桂园",
        "中国建筑", "中国铁建", "中国交建", "绿地",
    ],
}


def classify_style(name: str, market: str, beta: float, volatility: float) -> str:
    """Classify a stock's investment style based on name + quantitative metrics."""
    # 1. Keyword matching
    for style, keywords in STYLE_KEYWORDS.items():
        for kw in keywords:
            if kw.upper() in name.upper():
                return style

    # 2. Quantitative classification
    if beta is not None and volatility is not None:
        if beta > 1.2 and volatility > 35:
            return STYLE_TECH
        if beta < 0.8 and volatility < 25:
            return STYLE_CONSUMER

    # 3. Market-based heuristics
    if market in ("SH", "SZ"):
        return STYLE_MIXED  # A-share: hard to classify without sector data
    if market == "HK":
        return STYLE_MIXED
    if market == "US":
        return STYLE_TECH  # US market bias

    return STYLE_MIXED


# ── Main analysis ─────────────────────────────────────────────────────────

def analyze_portfolio(conn, portfolio_id: int) -> dict:
    cur = conn.cursor()

    # 1. Load holdings
    cur.execute("""
        SELECT h.stock_id, h.total_shares, h.avg_cost, h.total_invested,
               h.total_dividends, s.symbol, s.name, s.market, s.currency
        FROM holdings h JOIN stocks s ON h.stock_id = s.id
        WHERE h.portfolio_id = %s AND h.total_shares > 0
        ORDER BY h.total_invested DESC
    """, (portfolio_id,))
    holdings = cur.fetchall()
    if not holdings:
        cur.close()
        return {"error": "no holdings"}

    # Latest prices
    stock_ids = [h[0] for h in holdings]
    id_str = ",".join(str(sid) for sid in stock_ids)
    cur.execute(f"""
        SELECT sp.stock_id, sp.close
        FROM stock_prices sp
        INNER JOIN (
            SELECT stock_id, MAX(trade_date) AS max_date
            FROM stock_prices WHERE stock_id IN ({id_str})
            GROUP BY stock_id
        ) latest ON sp.stock_id = latest.stock_id AND sp.trade_date = latest.max_date
    """)
    price_map = {row[0]: float(row[1]) for row in cur.fetchall()}

    # Load metrics
    metric_rows = {}
    if stock_ids:
        cur.execute(f"""
            SELECT stock_id, percentile_5y, beta_1y, volatility_1y, max_drawdown_1y
            FROM stock_metric_cache WHERE stock_id IN ({id_str})
        """)
        for row in cur.fetchall():
            metric_rows[row[0]] = {
                "pct": row[1], "beta": row[2], "vol": row[3], "mdd": row[4],
            }

    # Load exchange rates
    cur.execute("SELECT currency, rate FROM exchange_rates")
    rates = {row[0]: float(row[1]) for row in cur.fetchall()}
    if not rates:
        rates = {"CNY": 1.0, "HKD": 1.1, "USD": 7.2}

    cur.close()

    # 2. Compute per-holding metrics
    holdings_data = []
    total_value = 0.0
    for h in holdings:
        sid, shares, avg_cost, invested, divs, sym, name, market, currency = h
        price = price_map.get(sid, 0)
        mv = shares * price if price > 0 else invested
        pnl = mv - float(invested or 0) + float(divs or 0)
        pnl_pct = (pnl / float(invested)) * 100 if invested and invested > 0 else 0
        weight_pct = 0  # will compute after total

        m = metric_rows.get(sid, {})
        beta = float(m["beta"]) if m.get("beta") is not None else None
        vol = float(m["vol"]) if m.get("vol") is not None else None
        style = classify_style(name or sym, market, beta, vol)

        total_value += mv
        holdings_data.append({
            "symbol": sym, "name": name, "market": market, "currency": currency,
            "shares": int(shares), "avgCost": float(avg_cost or 0),
            "invested": float(invested or 0), "marketValue": mv, "pnl": pnl,
            "pnlPct": round(pnl_pct, 2), "price": price,
            "beta": round(beta, 2) if beta else None,
            "volatility": round(vol, 1) if vol else None,
            "style": style,
        })

    # Compute weights
    for hd in holdings_data:
        hd["weightPct"] = round(hd["marketValue"] / total_value * 100, 1) if total_value > 0 else 0
    holdings_data.sort(key=lambda x: x["weightPct"], reverse=True)

    # 3. Style allocation
    style_allocation = defaultdict(lambda: {"value": 0.0, "pct": 0.0, "count": 0, "stocks": []})
    for hd in holdings_data:
        s = style_allocation[hd["style"]]
        s["value"] += hd["marketValue"]
        s["count"] += 1
        s["stocks"].append(hd["symbol"])
    for style, data in style_allocation.items():
        data["pct"] = round(data["value"] / total_value * 100, 1) if total_value > 0 else 0

    # 4. Risk profile
    weighted_beta = 0
    beta_count = 0
    for hd in holdings_data:
        if hd["beta"] is not None:
            weighted_beta += hd["beta"] * hd["weightPct"] / 100
            beta_count += 1
    weighted_beta = round(weighted_beta, 2) if beta_count > 0 else None

    # Concentration risk
    top1_weight = holdings_data[0]["weightPct"] if holdings_data else 0
    top3_weight = sum(h["weightPct"] for h in holdings_data[:3]) if holdings_data else 0
    top_style_pct = max(s["pct"] for s in style_allocation.values()) if style_allocation else 0
    top_style_name = max(style_allocation, key=lambda k: style_allocation[k]["pct"]) if style_allocation else ""

    # Market allocation
    market_allocation = defaultdict(float)
    for hd in holdings_data:
        market_allocation[hd["market"]] += hd["weightPct"]

    # 5. Generate recommendations
    recommendations = []

    # Concentration check
    if top1_weight > 20:
        recommendations.append({
            "severity": "warning",
            "title": "单票集中度偏高",
            "detail": f"{holdings_data[0]['name']} 占比 {top1_weight:.0f}%，单一标的风险过大。建议将单票上限控制在 15-20% 以内。",
        })
    if top3_weight > 50:
        recommendations.append({
            "severity": "warning",
            "title": "前三大持仓占比过高",
            "detail": f"前三大持仓合计 {top3_weight:.0f}%，尾部风险集中。建议分散到更多标的中。",
        })

    # Style concentration
    if top_style_pct > 50:
        style_name = top_style_name
        recommendations.append({
            "severity": "info",
            "title": f"{style_name}风格占比过高 ({top_style_pct:.0f}%)",
            "detail": f"你的持仓中{style_name}类标的占比超过一半，建议适当配置其他风格以降低风格轮动风险。",
        })

    # Risk level diagnosis
    if weighted_beta is not None:
        if weighted_beta > 1.3:
            recommendations.append({
                "severity": "info",
                "title": f"高波动偏好 (Beta={weighted_beta})",
                "detail": "你的组合加权 Beta 偏高，属于进取型风格。牛市中弹性大，但熊市中回撤也会更剧烈。建议配置部分低 Beta 防御标的。",
            })
        elif weighted_beta < 0.7:
            recommendations.append({
                "severity": "info",
                "title": f"防御型配置 (Beta={weighted_beta})",
                "detail": "你的组合偏防御，Beta 较低。适合稳健型投资者，但可能在牛市中弹性不足。可适当增加成长型标的。",
            })

    # Market diversification
    market_count = len(market_allocation)
    if market_count < 2:
        recommendations.append({
            "severity": "info",
            "title": "市场过于集中",
            "detail": "你的持仓集中在单一市场。跨市场配置（A股+港股+美股）可以有效分散地域和政策风险。",
        })

    # Cash / position suggestion
    position_count = len(holdings_data)
    if position_count < 5:
        recommendations.append({
            "severity": "info",
            "title": "持仓数量偏少",
            "detail": f"当前仅有 {position_count} 只标的。学术研究表明 8-15 只标的可消除大部分非系统性风险。",
        })
    if position_count > 30:
        recommendations.append({
            "severity": "warning",
            "title": "持仓过于分散",
            "detail": f"当前持有 {position_count} 只标的，管理成本较高。过度分散可能稀释收益，建议聚焦 15-25 只核心标的。",
        })

    # Summary
    style_summary = "均衡型"
    if weighted_beta is not None:
        if weighted_beta > 1.2:
            style_summary = "进取成长型"
        elif weighted_beta < 0.7:
            style_summary = "稳健防御型"

    return {
        "totalValue": round(total_value, 2),
        "positionCount": len(holdings_data),
        "holdings": holdings_data,
        "styleAllocation": {k: dict(v) for k, v in style_allocation.items()},
        "marketAllocation": {k: round(v, 1) for k, v in market_allocation.items()},
        "weightedBeta": weighted_beta,
        "top1Weight": round(top1_weight, 1),
        "top3Weight": round(top3_weight, 1),
        "styleSummary": style_summary,
        "recommendations": recommendations,
    }


# ── Entry point ───────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Investory 组合风格诊断引擎")
    parser.add_argument("--portfolio-id", type=int, required=True)
    parser.add_argument("--mode", default="full", choices=["full", "quick"])
    args = parser.parse_args()

    cfg = load_config()
    conn = get_conn(cfg)
    try:
        result = analyze_portfolio(conn, args.portfolio_id)
        print(json.dumps(result, ensure_ascii=False, default=str))
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(1)
