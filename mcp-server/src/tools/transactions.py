"""
Investory MCP Tools — Transactions & PnL.

Tools: get_transactions, get_pnl_calendar, get_dashboard
"""

from __future__ import annotations

from ..db import query, query_one


async def get_transactions(
    portfolio_id: int = 1,
    symbol: str = "",
    limit: int = 50,
    from_date: str = "",
) -> dict:
    """查询交易记录（买入/卖出/转入/转出）。

    Args:
        portfolio_id: 组合ID
        symbol: 股票代码筛选（可选）
        limit: 返回数量上限
        from_date: 起始日期（YYYY-MM-DD），可选
    """
    sql = """
        SELECT t.id, t.type, t.stock_symbol, t.stock_name, t.stock_market,
               t.shares, t.price, t.fee, t.trade_date, t.note
        FROM transactions t
        WHERE t.portfolio_id = %s
    """
    params: list = [portfolio_id]

    if symbol:
        cleaned = symbol.replace("1.", "").replace("0.", "").replace("5.", "")
        sql += " AND t.stock_symbol = %s"
        params.append(cleaned)
    if from_date:
        sql += " AND t.trade_date >= %s"
        params.append(from_date)

    sql += " ORDER BY t.trade_date DESC, t.id DESC LIMIT %s"
    params.append(limit)

    rows = query(sql, tuple(params))
    return {
        "portfolio_id": portfolio_id,
        "transactions": [{
            "id": r["id"],
            "type": r["type"],
            "symbol": r["stock_symbol"],
            "name": r["stock_name"],
            "market": r["stock_market"],
            "shares": float(r["shares"]),
            "price": float(r["price"]),
            "fee": float(r.get("fee", 0) or 0),
            "date": str(r["trade_date"]),
            "note": r.get("note", ""),
        } for r in rows],
        "count": len(rows),
    }


async def get_pnl_calendar(year: int = 0, month: int = 0) -> dict:
    """获取盈亏日历（按日/月聚合的P&L热力图数据）。

    Args:
        year: 年份，默认当前年
        month: 月份（1-12），0表示全年
    """
    import datetime
    now = datetime.date.today()
    if not year:
        year = now.year

    sql = """
        SELECT snapshot_date, total_value, total_cost, daily_pnl
        FROM daily_portfolio_value
        WHERE portfolio_id = 1 AND YEAR(snapshot_date) = %s
    """
    params: list = [year]
    if month:
        sql += " AND MONTH(snapshot_date) = %s"
        params.append(month)
    sql += " ORDER BY snapshot_date"

    rows = query(sql, tuple(params))
    return {
        "year": year,
        "month": month or "all",
        "data": [{
            "date": str(r["snapshot_date"]),
            "total_value": round(float(r.get("total_value", 0) or 0), 2),
            "total_cost": round(float(r.get("total_cost", 0) or 0), 2),
            "daily_pnl": round(float(r.get("daily_pnl", 0) or 0), 2),
        } for r in rows],
        "count": len(rows),
    }


async def get_dashboard(portfolio_id: int = 1, period: str = "6M") -> dict:
    """获取仪表盘数据：总价值曲线、盈亏汇总。

    Args:
        portfolio_id: 组合ID
        period: 时间范围（1M/6M/1Y/ALL）
    """
    from datetime import date, timedelta

    days_map = {"1M": 30, "6M": 180, "1Y": 365, "ALL": 3650}
    days = days_map.get(period, 180)
    start = (date.today() - timedelta(days=days)).isoformat()

    values = query("""
        SELECT snapshot_date, total_value, total_cost, daily_pnl
        FROM daily_portfolio_value
        WHERE portfolio_id = %s AND snapshot_date >= %s
        ORDER BY snapshot_date
    """, (portfolio_id, start))

    latest = values[-1] if values else None

    return {
        "portfolio_id": portfolio_id,
        "period": period,
        "latest_value": round(float(latest["total_value"]), 2) if latest else 0,
        "latest_cost": round(float(latest["total_cost"]), 2) if latest else 0,
        "total_pnl": round(float(latest["total_value"]) - float(latest["total_cost"]), 2) if latest else 0,
        "value_curve": [{
            "date": str(v["snapshot_date"]),
            "value": round(float(v["total_value"]), 2),
            "cost": round(float(v["total_cost"]), 2),
            "daily_pnl": round(float(v.get("daily_pnl", 0) or 0), 2),
        } for v in values],
        "data_points": len(values),
    }


TOOLS = [get_transactions, get_pnl_calendar, get_dashboard]
