"""
Investory MCP Tools — Watchlist & Configuration.

Tools: get_watchlist, add_to_watchlist, remove_from_watchlist
"""

from __future__ import annotations

from ..db import query, query_one, get_db


async def get_watchlist(user_id: int = 1) -> dict:
    """获取用户自选股列表。

    Args:
        user_id: 用户ID，默认1
    """
    rows = query("""
        SELECT s.symbol, s.name, s.market, s.currency, w.sort_order
        FROM watchlist w
        JOIN stocks s ON w.stock_id = s.id
        WHERE w.user_id = %s
        ORDER BY w.sort_order
    """, (user_id,))

    return {
        "user_id": user_id,
        "watchlist": [{
            "symbol": r["symbol"],
            "name": r["name"],
            "market": r["market"],
            "currency": r["currency"],
            "sort_order": r.get("sort_order", 0),
        } for r in rows],
        "count": len(rows),
    }


async def add_to_watchlist(symbol: str, user_id: int = 1) -> dict:
    """添加股票到自选列表。

    Args:
        symbol: 股票代码
        user_id: 用户ID
    """
    cleaned = symbol.replace("1.", "").replace("0.", "").replace("5.", "")
    stock = query_one("SELECT id, symbol, name FROM stocks WHERE symbol = %s", (cleaned,))

    if not stock:
        return {"error": f"股票 {symbol} 不存在", "added": False}

    # Check if already in watchlist
    existing = query_one(
        "SELECT id FROM watchlist WHERE user_id = %s AND stock_id = %s",
        (user_id, stock["id"]))

    if existing:
        return {"symbol": stock["symbol"], "name": stock["name"], "added": False,
                "message": "已在自选中"}

    # Get max sort_order
    max_order = query_one(
        "SELECT MAX(sort_order) AS m FROM watchlist WHERE user_id = %s",
        (user_id,))

    next_order = (max_order["m"] + 1) if max_order and max_order["m"] is not None else 0

    with get_db() as db:
        with db.cursor() as cur:
            cur.execute(
                "INSERT INTO watchlist (user_id, stock_id, sort_order) VALUES (%s, %s, %s)",
                (user_id, stock["id"], next_order))

    return {
        "symbol": stock["symbol"],
        "name": stock["name"],
        "added": True,
        "sort_order": next_order,
    }


async def remove_from_watchlist(symbol: str, user_id: int = 1) -> dict:
    """从自选列表移除股票。

    Args:
        symbol: 股票代码
        user_id: 用户ID
    """
    cleaned = symbol.replace("1.", "").replace("0.", "").replace("5.", "")
    stock = query_one("SELECT id, symbol, name FROM stocks WHERE symbol = %s", (cleaned,))

    if not stock:
        return {"error": f"股票 {symbol} 不存在", "removed": False}

    with get_db() as db:
        with db.cursor() as cur:
            cur.execute(
                "DELETE FROM watchlist WHERE user_id = %s AND stock_id = %s",
                (user_id, stock["id"]))
            affected = cur.rowcount

    return {
        "symbol": stock["symbol"],
        "name": stock["name"],
        "removed": affected > 0,
    }


TOOLS = [get_watchlist, add_to_watchlist, remove_from_watchlist]
