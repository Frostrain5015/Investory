"""
Investory MCP Tools — Portfolio & Positions.

Tools: get_portfolio_summary, get_holdings, get_closed_positions
"""

from __future__ import annotations

from ..db import query, query_one


async def get_portfolio_summary(portfolio_id: int = 1) -> dict:
    """获取投资组合总览：总市值、总成本、总盈亏、现金余额。

    Args:
        portfolio_id: 组合ID，默认为1（用户默认组合）
    """
    # Cash balance
    cash = query("SELECT currency, amount FROM cash_balances WHERE portfolio_id = %s", (portfolio_id,))

    # Latest daily value
    latest = query_one(
        "SELECT total_value, total_cost, daily_pnl FROM daily_portfolio_value "
        "WHERE portfolio_id = %s ORDER BY snapshot_date DESC LIMIT 1",
        (portfolio_id,))

    # Holdings count
    holding_count = query_one(
        "SELECT COUNT(*) AS cnt FROM holdings WHERE portfolio_id = %s AND total_shares > 0",
        (portfolio_id,))

    total_value = float(latest["total_value"]) if latest else 0
    total_cost = float(latest["total_cost"]) if latest else 0

    return {
        "portfolio_id": portfolio_id,
        "total_value_cny": round(total_value, 2),
        "total_cost_cny": round(total_cost, 2),
        "total_pnl_cny": round(total_value - total_cost, 2),
        "total_pnl_pct": round((total_value - total_cost) / total_cost * 100, 2) if total_cost > 0 else 0,
        "daily_pnl_cny": round(float(latest["daily_pnl"]), 2) if latest and latest.get("daily_pnl") else 0,
        "cash": {r["currency"]: float(r["amount"]) for r in cash},
        "holding_count": holding_count["cnt"] if holding_count else 0,
    }


async def get_holdings(portfolio_id: int = 1, market: str = "") -> dict:
    """获取持仓列表，含现价、市值、浮动盈亏、盈亏比例。

    Args:
        portfolio_id: 组合ID
        market: 市场筛选（SH/SZ/HK/US），留空则全部
    """
    sql = """
        SELECT s.symbol, s.name, s.market, s.currency,
               h.total_shares, h.avg_cost, h.diluted_cost, h.total_invested, h.total_dividends
        FROM holdings h
        JOIN stocks s ON h.stock_id = s.id
        WHERE h.portfolio_id = %s AND h.total_shares > 0
    """
    params: tuple = (portfolio_id,)
    if market:
        sql += " AND s.market = %s"
        params = (portfolio_id, market)
    sql += " ORDER BY s.market, s.symbol"

    rows = query(sql, params)
    holdings = []
    for r in rows:
        # Try to get latest price from stock_prices
        price_row = query_one(
            "SELECT close FROM stock_prices WHERE stock_id = "
            "(SELECT id FROM stocks WHERE symbol = %s) ORDER BY trade_date DESC LIMIT 1",
            (r["symbol"],))

        current_price = float(price_row["close"]) if price_row else 0
        shares = float(r["total_shares"])
        cost = float(r["diluted_cost"] or r["avg_cost"] or 0)
        market_value = shares * current_price
        pnl = market_value - (shares * cost)

        holdings.append({
            "symbol": r["symbol"],
            "name": r["name"],
            "market": r["market"],
            "currency": r["currency"],
            "shares": shares,
            "avg_cost": round(float(r["avg_cost"] or 0), 2),
            "diluted_cost": round(float(r["diluted_cost"] or 0), 2),
            "total_invested": round(float(r["total_invested"] or 0), 2),
            "current_price": round(current_price, 2),
            "market_value_cny": round(market_value, 2),
            "unrealized_pnl": round(pnl, 2),
            "pnl_pct": round(pnl / (shares * cost) * 100, 2) if cost > 0 and shares > 0 else 0,
        })

    total_value = sum(h["market_value_cny"] for h in holdings)
    total_pnl = sum(h["unrealized_pnl"] for h in holdings)

    return {
        "portfolio_id": portfolio_id,
        "holdings": holdings,
        "total_market_value_cny": round(total_value, 2),
        "total_unrealized_pnl_cny": round(total_pnl, 2),
    }


async def get_closed_positions(portfolio_id: int = 1, limit: int = 20) -> dict:
    """获取已清仓股票及实现盈亏。

    Args:
        portfolio_id: 组合ID
        limit: 返回数量上限
    """
    # Findstocks with 0 shares that had transactions (closed positions)
    rows = query("""
        SELECT DISTINCT s.symbol, s.name, s.market
        FROM holdings h
        JOIN stocks s ON h.stock_id = s.id
        WHERE h.portfolio_id = %s AND h.total_shares = 0
          AND h.total_invested > 0
        ORDER BY s.symbol
        LIMIT %s
    """, (portfolio_id, limit))

    positions = []
    for r in rows:
        txns = query("""
            SELECT type, shares, price, fee, trade_date
            FROM transactions
            WHERE portfolio_id = %s AND stock_symbol = %s
            ORDER BY trade_date DESC
        """, (portfolio_id, r["symbol"]))

        total_buy = sum(float(t["shares"]) * float(t["price"]) + float(t.get("fee", 0) or 0)
                        for t in txns if t["type"] == "BUY")
        total_sell = sum(float(t["shares"]) * float(t["price"]) - float(t.get("fee", 0) or 0)
                         for t in txns if t["type"] == "SELL")
        realized_pnl = total_sell - total_buy

        positions.append({
            "symbol": r["symbol"],
            "name": r["name"],
            "market": r["market"],
            "realized_pnl": round(realized_pnl, 2),
            "transaction_count": len(txns),
        })

    return {
        "portfolio_id": portfolio_id,
        "closed_positions": positions,
        "count": len(positions),
    }


TOOLS = [get_portfolio_summary, get_holdings, get_closed_positions]
