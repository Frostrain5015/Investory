"""
Investory MCP Tools — Quantitative Analysis & Backtesting.

Tools: get_portfolio_risk, get_portfolio_style, run_backtest, get_backtest_history
"""

from __future__ import annotations

from ..db import query, query_one


async def get_portfolio_risk(portfolio_id: int = 1) -> dict:
    """获取组合风险指标：加权Beta、VaR、最大回撤。

    Args:
        portfolio_id: 组合ID
    """
    row = query_one(
        "SELECT * FROM portfolio_risk_cache WHERE portfolio_id = %s ORDER BY computed_at DESC LIMIT 1",
        (portfolio_id,))

    if not row:
        return {"portfolio_id": portfolio_id, "message": "暂无风险数据，请执行量化分析刷新"}

    return {
        "portfolio_id": portfolio_id,
        "weighted_beta": float(row.get("weighted_beta", 0) or 0),
        "var_95_pct": float(row.get("var_95_pct", 0) or 0),
        "max_drawdown_pct": float(row.get("portfolio_maxdd", 0) or 0),
        "computed_at": str(row.get("computed_at", "")),
    }


async def get_portfolio_style(portfolio_id: int = 1) -> dict:
    """获取组合风格诊断：成长/价值/动量/防御因子暴露。

    Args:
        portfolio_id: 组合ID
    """
    # Read from stock_metric_cache for holdings
    holdings = query("""
        SELECT s.symbol, s.name, s.market, h.total_shares, h.diluted_cost
        FROM holdings h JOIN stocks s ON h.stock_id = s.id
        WHERE h.portfolio_id = %s AND h.total_shares > 0
    """, (portfolio_id,))

    if not holdings:
        return {"portfolio_id": portfolio_id, "holdings": [], "message": "无持仓"}

    total_weight = sum(float(h["total_shares"]) * float(h["diluted_cost"] or 0) for h in holdings)

    styles = []
    for h in holdings:
        metric = query_one(
            "SELECT beta_1y, volatility_1y, percentile_5y, factor_style "
            "FROM stock_metric_cache WHERE stock_id = "
            "(SELECT id FROM stocks WHERE symbol = %s) ORDER BY computed_at DESC LIMIT 1",
            (h["symbol"],))

        weight = float(h["total_shares"]) * float(h["diluted_cost"] or 0)
        weight_pct = weight / total_weight * 100 if total_weight > 0 else 0

        styles.append({
            "symbol": h["symbol"],
            "name": h["name"],
            "weight_pct": round(weight_pct, 2),
            "beta": float(metric["beta_1y"]) if metric and metric.get("beta_1y") else None,
            "volatility_1y": float(metric["volatility_1y"]) if metric and metric.get("volatility_1y") else None,
            "percentile_5y": float(metric["percentile_5y"]) if metric and metric.get("percentile_5y") else None,
            "factor_style": str(metric["factor_style"]) if metric and metric.get("factor_style") else None,
        })

    # Aggregate style
    style_counts = {}
    for s in styles:
        if s["factor_style"]:
            style_counts[s["factor_style"]] = style_counts.get(s["factor_style"], 0) + 1

    return {
        "portfolio_id": portfolio_id,
        "styles": styles,
        "dominant_style": max(style_counts, key=style_counts.get) if style_counts else "未知",
        "style_distribution": style_counts,
    }


async def get_backtest_history(limit: int = 10) -> dict:
    """获取历史回测结果列表。

    Args:
        limit: 返回数量上限
    """
    rows = query(
        "SELECT id, name, strategy_type, start_date, end_date, created_at FROM backtest_results "
        "ORDER BY created_at DESC LIMIT %s",
        (limit,))

    return {
        "backtests": [{
            "id": r["id"],
            "name": r["name"],
            "strategy_type": r["strategy_type"],
            "start_date": str(r["start_date"]),
            "end_date": str(r["end_date"]),
            "created_at": str(r["created_at"]),
        } for r in rows],
        "count": len(rows),
    }


async def get_exchange_rates() -> dict:
    """获取当前汇率（CNY/HKD/USD）。"""
    rows = query("SELECT currency, rate FROM exchange_rates")
    return {
        "rates": {r["currency"]: float(r["rate"]) for r in rows},
    }


TOOLS = [get_portfolio_risk, get_portfolio_style, get_backtest_history, get_exchange_rates]
