#!/usr/bin/env python3
"""
Markowitz mean-variance portfolio optimizer.

Computes optimal weights for max Sharpe ratio, minimum variance,
and equal risk contribution (Risk Parity), then compares against
current allocations to suggest rebalancing trades.

Usage:
    python optimizer.py --portfolio-id N [--max-weight 0.25] [--mode sharpe|minvar|riskparity]
"""

import argparse
import json

import numpy as np

from db import get_conn as connect_db, load_config
from market_history import latest as latest_quotes


# ── Config ──────────────────────────────────────────────────────────────


def get_conn(cfg: dict):
    return connect_db(cfg, autocommit=True)

# ── Data loading ────────────────────────────────────────────────────────

def load_returns(conn, stock_ids: list, lookback_days=252) -> tuple:
    from market_history import recent_by_id
    cur = conn.cursor()
    series, symbols = [], []
    try:
        for sid in stock_ids:
            cur.execute("SELECT symbol FROM stocks WHERE id=%s", (sid,))
            identity = cur.fetchone()
            rows = recent_by_id(conn, sid, lookback_days + 1, "forward")
            if not identity or len(rows) < 60:
                return None, None  # Never optimize a subset against full-portfolio weights.
            series.append({row["date"]: row["close"] for row in rows})
            symbols.append(identity[0])
    finally:
        cur.close()
    dates = sorted(set.intersection(*(set(row) for row in series))) if series else []
    if len(dates) < 60 or len(series) < 2:
        return None, None
    closes = np.array([[row[day] for day in dates] for row in series], dtype=np.float64)
    return np.diff(closes, axis=1) / closes[:, :-1], symbols

# ── Optimization ────────────────────────────────────────────────────────

def max_sharpe_weights(cov: np.ndarray, mu: np.ndarray, max_weight=0.30):
    """Grid search over simplex for max Sharpe. Returns weights array."""
    n = len(mu)
    best_w, best_sharpe = None, -999
    # Start from equal weight, refine with Monte Carlo
    for _ in range(20000):
        w = np.random.dirichlet(np.ones(n))
        if max_weight and np.max(w) > max_weight:
            continue
        port_ret = np.dot(w, mu) * 252
        port_vol = np.sqrt(np.dot(w.T, np.dot(cov, w)) * 252)
        sharpe = (port_ret - 0.03) / port_vol if port_vol > 0 else 0
        if sharpe > best_sharpe:
            best_sharpe = sharpe
            best_w = w.copy()
    return best_w, best_sharpe

def min_variance_weights(cov: np.ndarray, max_weight=0.30):
    """Find minimum variance portfolio via Monte Carlo."""
    n = cov.shape[0]
    best_w, best_var = None, float("inf")
    for _ in range(20000):
        w = np.random.dirichlet(np.ones(n))
        if max_weight and np.max(w) > max_weight:
            continue
        port_var = np.dot(w.T, np.dot(cov, w))
        if port_var < best_var:
            best_var = port_var
            best_w = w.copy()
    return best_w, np.sqrt(best_var * 252)

def risk_parity_weights(cov: np.ndarray):
    """Approximate risk parity: weight ∝ 1/volatility."""
    vols = np.sqrt(np.diag(cov))
    inv_vols = 1.0 / vols
    w = inv_vols / np.sum(inv_vols)
    return w

# ── Analysis ────────────────────────────────────────────────────────────

def analyze(portfolio_id: int, max_weight=0.30, mode="sharpe"):
    cfg = load_config()
    conn = get_conn(cfg)

    # Load holdings
    cur = conn.cursor()
    cur.execute("""
        SELECT h.stock_id, s.symbol, s.name, s.market, h.total_shares,
               NULL AS price
        FROM holdings h JOIN stocks s ON s.id=h.stock_id
        WHERE h.portfolio_id=%s AND h.total_shares>0
        ORDER BY h.total_invested DESC
    """, (portfolio_id,))
    holdings = cur.fetchall()
    cur.close()

    if len(holdings) < 2:
        return {"error": "至少需要 2 只持仓才能优化", "holdings": len(holdings)}

    quotes = latest_quotes([h[1] for h in holdings])
    if any(h[1] not in quotes for h in holdings):
        conn.close()
        return {"error": "持仓报价不完整，不能生成再平衡建议"}
    holdings = [(*h[:5], quotes[h[1]]["price"]) for h in holdings]
    # Build current weights
    stock_ids = [h[0] for h in holdings]
    current_prices = [float(h[5]) if h[5] else 0 for h in holdings]
    shares = [float(h[4]) for h in holdings]
    market_values = [s * p for s, p in zip(shares, current_prices)]
    total_mv = sum(market_values)
    current_weights = [mv / total_mv for mv in market_values]

    # Historical returns
    returns_mat, symbols = load_returns(conn, stock_ids)
    if returns_mat is None:
        return {"error": "历史数据不足（每只至少需要 60 个交易日）"}

    # Covariance matrix & expected returns
    cov = np.cov(returns_mat)
    mu = np.mean(returns_mat, axis=1)

    # Optimize
    if mode == "sharpe":
        opt_weights, score = max_sharpe_weights(cov, mu, max_weight)
        score_label = "sharpe_ratio"
        score_val = round(float(score), 3)
    elif mode == "minvar":
        opt_weights, score = min_variance_weights(cov, max_weight)
        score_label = "annual_vol_pct"
        score_val = round(float(score) * 100, 1)
    elif mode == "riskparity":
        opt_weights = risk_parity_weights(cov)
        score_label = "method"
        score_val = "risk_parity"
    else:
        return {"error": f"未知模式: {mode}"}

    if opt_weights is None:
        return {"error": "优化未收敛"}

    # Build comparison table
    positions = []
    for i, h in enumerate(holdings):
        sid, sym, name, market, sh, price = h
        cur_w = round(current_weights[i] * 100, 1)
        opt_w = round(float(opt_weights[i]) * 100, 1)
        diff = round(opt_w - cur_w, 1)
        action = ""
        if diff > 2:
            action = "增持"
        elif diff < -2:
            action = "减持"
        else:
            action = "持有"

        positions.append({
            "symbol": sym, "name": name, "market": market,
            "currentWeightPct": cur_w, "targetWeightPct": opt_w,
            "diffPct": diff, "action": action,
            "shares": int(float(sh)), "price": round(float(price) if price else 0, 2),
        })

    # Portfolio-level summary
    port_ret = np.dot(opt_weights, mu) * 252 * 100
    port_vol = np.sqrt(np.dot(opt_weights.T, np.dot(cov, opt_weights)) * 252) * 100
    port_sharpe = (port_ret - 3) / port_vol if port_vol > 0 else 0

    # Diversification score: 1 - Herfindahl index, normalized to 0-1
    herf = sum(w*w for w in opt_weights)
    div_score = round((1 - herf) / (1 - 1/len(opt_weights)) * 100, 1) if len(opt_weights) > 1 else 0

    changes = [p for p in positions if p["action"] != "持有"]
    result = {
        "portfolioId": portfolio_id,
        "mode": mode,
        "totalValue": round(total_mv, 2),
        "holdingsCount": len(holdings),
        "constraints": {"maxSingleWeight": max_weight},
        "optimized": {
            "expectedReturnPct": round(float(port_ret), 2),
            "expectedVolPct": round(float(port_vol), 1),
            "expectedSharpe": round(float(port_sharpe), 3),
            "diversificationScore": div_score,
            score_label: score_val,
        },
        "positions": positions,
        "rebalancing": {
            "totalChanges": len(changes),
            "changes": [{k: p[k] for k in ("symbol", "name", "action", "diffPct", "currentWeightPct", "targetWeightPct")} for p in changes],
        },
    }
    conn.close()
    return result

# ── Main ────────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser(description="Investory 组合优化器")
    p.add_argument("--portfolio-id", type=int, required=True)
    p.add_argument("--max-weight", type=float, default=0.30, help="单票最大权重 (0.0-1.0)")
    p.add_argument("--mode", choices=["sharpe", "minvar", "riskparity"], default="sharpe")
    args = p.parse_args()

    result = analyze(args.portfolio_id, args.max_weight, args.mode)
    print(json.dumps(result, ensure_ascii=False, default=str))

if __name__ == "__main__":
    main()
