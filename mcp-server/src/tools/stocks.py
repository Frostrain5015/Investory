"""
Investory MCP Tools — Stock Data.

Tools: get_stock_quote, get_stock_detail, search_stocks, get_market_indices
"""

from __future__ import annotations

from ..db import query, query_one


async def get_stock_quote(symbol: str) -> dict:
    """获取实时行情：最新价、涨跌幅、成交量。

    Args:
        symbol: 股票代码，如 600519 或 1.600519
    """
    s = _find_stock(symbol)
    if not s:
        return {"error": f"股票 {symbol} 不存在"}

    price = query_one(
        "SELECT trade_date, open, close, high, low, volume FROM stock_prices "
        "WHERE stock_id = %s ORDER BY trade_date DESC LIMIT 2",
        (s["id"],))

    if not price:
        return {"symbol": s["symbol"], "name": s["name"], "market": s["market"],
                "currency": s["currency"], "price": None, "message": "暂无行情数据"}

    latest = price
    prev = None
    if len(query("SELECT 1 FROM stock_prices WHERE stock_id = %s ORDER BY trade_date DESC LIMIT 2", (s["id"],))) >= 2:
        prev = query_one(
            "SELECT close FROM stock_prices WHERE stock_id = %s ORDER BY trade_date DESC LIMIT 1,1",
            (s["id"],))

    current_close = float(latest["close"])
    prev_close = float(prev["close"]) if prev else current_close
    change_pct = (current_close - prev_close) / prev_close * 100 if prev_close > 0 else 0

    return {
        "symbol": s["symbol"],
        "name": s["name"],
        "market": s["market"],
        "currency": s["currency"],
        "trade_date": str(latest["trade_date"]),
        "open": float(latest["open"]),
        "high": float(latest["high"]),
        "low": float(latest["low"]),
        "close": current_close,
        "volume": int(latest.get("volume", 0) or 0),
        "change_pct": round(change_pct, 2),
        "prev_close": round(prev_close, 2),
    }


async def get_stock_detail(symbol: str, days: int = 60) -> dict:
    """获取股票详情：K线历史、基本信息。

    Args:
        symbol: 股票代码
        days: K线天数，默认60
    """
    s = _find_stock(symbol)
    if not s:
        return {"error": f"股票 {symbol} 不存在"}

    prices = query(
        "SELECT trade_date, open, close, high, low, volume "
        "FROM stock_prices WHERE stock_id = %s ORDER BY trade_date DESC LIMIT %s",
        (s["id"], days))

    price_list = [{
        "date": str(p["trade_date"]),
        "open": float(p["open"]),
        "close": float(p["close"]),
        "high": float(p["high"]),
        "low": float(p["low"]),
        "volume": int(p.get("volume", 0) or 0),
    } for p in reversed(prices)]

    return {
        "symbol": s["symbol"],
        "name": s["name"],
        "market": s["market"],
        "currency": s["currency"],
        "prices": price_list,
        "data_points": len(price_list),
    }


async def search_stocks(query_str: str, market: str = "", limit: int = 20) -> dict:
    """按名称/代码搜索股票。

    Args:
        query_str: 搜索关键词
        market: 市场筛选（SH/SZ/HK/US），留空则全部
        limit: 返回数量上限
    """
    sql = """
        SELECT symbol, name, market, currency FROM stocks
        WHERE (symbol LIKE %s OR name LIKE %s)
    """
    params: list = [f"%{query_str}%", f"%{query_str}%"]

    if market:
        sql += " AND market = %s"
        params.append(market)
    sql += " LIMIT %s"
    params.append(limit)

    results = query(sql, tuple(params))
    return {
        "query": query_str,
        "results": [{
            "symbol": r["symbol"],
            "name": r["name"],
            "market": r["market"],
            "currency": r["currency"],
        } for r in results],
        "count": len(results),
    }


async def get_market_indices() -> dict:
    """获取主要指数行情（上证、深证、恒生、标普等）。"""
    # Read from stock_prices for index-type stocks
    indices = query("""
        SELECT s.symbol, s.name, sp.close, sp.trade_date, sp.change_pct
        FROM stocks s
        JOIN (
            SELECT stock_id, MAX(trade_date) AS max_date
            FROM stock_prices GROUP BY stock_id
        ) latest ON s.id = latest.stock_id
        JOIN stock_prices sp ON sp.stock_id = latest.stock_id AND sp.trade_date = latest.max_date
        WHERE s.market = 'IDX'
        ORDER BY s.symbol
    """)

    return {
        "indices": [{
            "symbol": r["symbol"],
            "name": r["name"],
            "close": float(r["close"]) if r.get("close") else None,
            "trade_date": str(r.get("trade_date", "")),
        } for r in indices],
        "count": len(indices),
    }


def _find_stock(symbol: str) -> dict | None:
    """Find a stock by symbol (with or without market prefix)."""
    cleaned = symbol.replace("1.", "").replace("0.", "").replace("5.", "")
    row = query_one(
        "SELECT id, symbol, name, market, currency FROM stocks WHERE symbol = %s",
        (cleaned,))
    if not row:
        row = query_one(
            "SELECT id, symbol, name, market, currency FROM stocks WHERE symbol LIKE %s",
            (f"%{cleaned}%",))
    return row


TOOLS = [get_stock_quote, get_stock_detail, search_stocks, get_market_indices]
