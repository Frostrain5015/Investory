"""
Investory MCP Tools — Factor & Quant Analysis.

Tools: get_factor_scores, get_factor_breakdown, get_market_regime, get_daily_picks
"""

from __future__ import annotations

from ..db import query, query_one


async def get_factor_scores(symbols_str: str, regime: str = "") -> dict:
    """批量获取多因子评分（买入分/卖出分/综合分）。

    Args:
        symbols_str: 逗号分隔的股票代码，如 "600519,000858"
        regime: 市场环境过滤（可选）
    """
    symbols = [s.strip() for s in symbols_str.split(",") if s.strip()]
    if not symbols:
        return {"error": "请提供股票代码列表"}

    placeholders = ",".join(["%s"] * len(symbols))
    rows = query(f"""
        SELECT f.stock_symbol, f.factor_name, f.buy_score, f.sell_score
        FROM stocksage_factor_cache f
        INNER JOIN (
            SELECT stock_symbol, MAX(computed_at) AS max_t
            FROM stocksage_factor_cache
            WHERE stock_symbol IN ({placeholders})
            GROUP BY stock_symbol
        ) latest ON f.stock_symbol = latest.stock_symbol AND f.computed_at = latest.max_t
    """, tuple(symbols))

    scores: dict[str, dict] = {s: {"symbol": s, "buy_score": 0, "sell_score": 0, "factor_count": 0} for s in symbols}
    for r in rows:
        sym = r["stock_symbol"]
        scores[sym]["buy_score"] += float(r.get("buy_score", 0) or 0)
        scores[sym]["sell_score"] += float(r.get("sell_score", 0) or 0)
        scores[sym]["factor_count"] += 1

    for s in symbols:
        sc = scores[s]
        total_buy = sc["buy_score"]
        total_sell = sc["sell_score"]
        sc["total_score"] = round(total_buy / (total_buy + total_sell + 0.01) * 100, 1)
        sc["buy_score"] = round(total_buy, 1)
        sc["sell_score"] = round(total_sell, 1)

    return {"scores": scores}


async def get_factor_breakdown(symbol: str) -> dict:
    """单股逐因子拆解：价值/成长/动量/质量/技术等各因子详情。

    Args:
        symbol: 股票代码
    """
    rows = query("""
        SELECT factor_name, factor_group, factor_value, buy_score, sell_score, description
        FROM stocksage_factor_cache
        WHERE stock_symbol = %s
          AND computed_at = (SELECT MAX(computed_at) FROM stocksage_factor_cache WHERE stock_symbol = %s)
        ORDER BY buy_score DESC
    """, (symbol, symbol))

    if not rows:
        return {"symbol": symbol, "factors": [], "message": "暂无因子数据，请先执行每日扫描"}

    factors = [{
        "name": r["factor_name"],
        "group": r.get("factor_group", "other"),
        "value": round(float(r.get("factor_value", 0) or 0), 4),
        "buy_score": round(float(r.get("buy_score", 0) or 0), 1),
        "sell_score": round(float(r.get("sell_score", 0) or 0), 1),
        "description": r.get("description", ""),
    } for r in rows]

    buy_sum = sum(f["buy_score"] for f in factors)
    sell_sum = sum(f["sell_score"] for f in factors)

    return {
        "symbol": symbol,
        "total_score": round(buy_sum / (buy_sum + sell_sum + 0.01) * 100, 1),
        "buy_score": round(buy_sum, 1),
        "sell_score": round(sell_sum, 1),
        "factors": factors,
        "factor_count": len(factors),
    }


async def get_market_regime() -> dict:
    """获取当前市场环境（NORMAL/CAUTION/CRISIS/BULL/EXTREME_BULL/BEAR）。"""
    row = query_one("SELECT * FROM stocksage_regime_cache ORDER BY regime_date DESC LIMIT 1")
    if not row:
        return {"regime": "unknown", "message": "暂无环境数据"}

    return {
        "regime": row["regime"],
        "date": str(row["regime_date"]),
        "confidence": float(row.get("confidence", 0) or 0),
        "description": row.get("description", ""),
        "indicators": row.get("indicators_json", {}),
    }


async def get_daily_picks(strategy: str = "", limit: int = 10) -> dict:
    """获取今日推荐股票（多策略综合）。

    Args:
        strategy: 策略类型筛选（main/chip/hot），留空则全部
        limit: 返回数量上限
    """
    sql = "SELECT * FROM stocksage_daily_picks WHERE pick_date = CURDATE()"
    params: list = []
    if strategy:
        sql += " AND strategy_type = %s"
        params.append(strategy)
    sql += " ORDER BY total_score DESC LIMIT %s"
    params.append(limit)

    rows = query(sql, tuple(params))
    return {
        "date": str(query_one("SELECT CURDATE() AS d")["d"]),
        "picks": [{
            "symbol": r["stock_symbol"],
            "name": r["stock_name"],
            "buy_score": float(r.get("buy_score", 0) or 0),
            "sell_score": float(r.get("sell_score", 0) or 0),
            "total_score": float(r.get("total_score", 0) or 0),
            "strategy": r.get("strategy_type", ""),
            "regime": r.get("regime", ""),
            "reason": r.get("reason_text", ""),
        } for r in rows],
        "count": len(rows),
    }


TOOLS = [get_factor_scores, get_factor_breakdown, get_market_regime, get_daily_picks]
