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
import configparser
import json
import logging
import os
import sys
from pathlib import Path

import numpy as np
import pymysql

SCRIPT_DIR = Path(__file__).parent
CONFIG_FILE = SCRIPT_DIR / "config.ini"

# ── Config ──────────────────────────────────────────────────────────────

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
    return pymysql.connect(host=cfg["db_host"], port=cfg["db_port"],
        database=cfg["db_name"], user=cfg["db_user"],
        password=cfg["db_password"], charset="utf8mb4", autocommit=True)

# ── Data loading ────────────────────────────────────────────────────────

def load_returns(conn, stock_ids: list, lookback_days=252) -> tuple:
    """Return (returns_matrix, symbols) where returns_matrix is N_stocks × N_days."""
    cur = conn.cursor()
    all_returns = []
    symbols = []
    for sid in stock_ids:
        cur.execute(
            "SELECT close, s.symbol FROM stock_prices sp JOIN stocks s ON s.id=sp.stock_id "
            "WHERE sp.stock_id=%s AND close>0 ORDER BY sp.trade_date DESC LIMIT %s",
            (sid, lookback_days + 1))
        rows = cur.fetchall()
        if len(rows) < 60:  # need at least 60 data points
            continue
        closes = np.array([float(r[0]) for r in reversed(rows)], dtype=np.float64)
        rets = np.diff(closes) / closes[:-1]
        all_returns.append(rets)
        symbols.append(rows[0][1])
    cur.close()
    if len(all_returns) < 2:
        return None, None
    # Align lengths (take last N days common to all)
    min_len = min(len(r) for r in all_returns)
    aligned = np.array([r[-min_len:] for r in all_returns])
    return aligned, symbols

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
               (SELECT sp.close FROM stock_prices sp
                WHERE sp.stock_id=h.stock_id AND sp.close>0
                ORDER BY sp.trade_date DESC LIMIT 1) AS price
        FROM holdings h JOIN stocks s ON s.id=h.stock_id
        WHERE h.portfolio_id=%s AND h.total_shares>0
        ORDER BY h.total_invested DESC
    """, (portfolio_id,))
    holdings = cur.fetchall()
    cur.close()

    if len(holdings) < 2:
        return {"error": "至少需要 2 只持仓才能优化", "holdings": len(holdings)}

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
