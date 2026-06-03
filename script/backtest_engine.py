#!/usr/bin/env python3
"""
Investory 量化回测引擎

用法:
    python3 backtest_engine.py --input <config.json>

输入 JSON 由 Java BacktestApiController 生成，包含:
  - strategy_type: "simple" | "advanced"
  - strategy: { stocks, entry, exit, position_sizing } 或 { code }
  - config: { start_date, end_date, initial_capital, commission_pct, slippage_pct }
  - result_id: 数据库回测结果 ID

输出写入 backtest_output_{result_id}.json
"""

import argparse
import configparser
import json
import math
import os
import signal
import sys
import time
import traceback
from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from typing import Optional

import numpy as np

from backtest_helpers import eval_indicator, eval_condition

SCRIPT_DIR = Path(__file__).parent
CONFIG_FILE = SCRIPT_DIR / "config.ini"


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
        "db_host": os.getenv("DB_HOST", get("database", "host", "localhost")),
        "db_port": int(os.getenv("DB_PORT", get("database", "port", "3306"))),
        "db_name": os.getenv("DB_NAME", get("database", "name", "investory")),
        "db_user": os.getenv("DB_USER", get("database", "user", "root")),
        "db_password": os.getenv("DB_PASSWORD", get("database", "password", "")),
        "proxy_url": os.getenv("PROXY_URL", get("proxy", "url", "")),
    }


def get_conn(cfg: dict):
    import pymysql
    return pymysql.connect(
        host=cfg["db_host"],
        port=cfg["db_port"],
        database=cfg["db_name"],
        user=cfg["db_user"],
        password=cfg["db_password"],
        charset="utf8mb4",
        autocommit=True,
    )


def resolve_symbol(conn, symbol: str) -> Optional[str]:
    """将用户输入的 symbol (如 600519.SH) 转换为 DB 格式 (如 1.600519)"""
    cur = conn.cursor()
    # Try direct match first
    cur.execute("SELECT symbol FROM stocks WHERE symbol = %s", (symbol,))
    row = cur.fetchone()
    if row:
        cur.close()
        return row[0]

    # Parse user format: CODE.MARKET → exchange.code
    if '.' in symbol:
        parts = symbol.rsplit('.', 1)
        code = parts[0]
        market = parts[1].upper()
        if market in ('SH', 'SZ'):
            prefix = '1' if market == 'SH' else '0'
            db_symbol = f"{prefix}.{code}"
            cur.execute("SELECT symbol FROM stocks WHERE symbol = %s", (db_symbol,))
            row = cur.fetchone()
            if row:
                cur.close()
                return row[0]

    # Try LIKE search
    cur.execute("SELECT symbol FROM stocks WHERE symbol LIKE %s", (f"%{symbol}%",))
    row = cur.fetchone()
    cur.close()
    return row[0] if row else None


def load_ohlcv(conn, symbol: str, start: str, end: str) -> dict:
    """加载单只股票的 OHLCV 数据，返回 { open, high, low, close, volume, dates } 各为 list"""
    db_symbol = resolve_symbol(conn, symbol)
    if db_symbol is None:
        print(f"  [警告] 未找到股票: {symbol}", flush=True)
        return None

    cur = conn.cursor()
    cur.execute("""
        SELECT s.id FROM stocks s WHERE s.symbol = %s
    """, (db_symbol,))
    row = cur.fetchone()
    if not row:
        cur.close()
        return None
    stock_id = row[0]

    cur.execute("""
        SELECT trade_date, open, high, low, close, volume
        FROM stock_prices
        WHERE stock_id = %s AND trade_date >= %s AND trade_date <= %s
        ORDER BY trade_date
    """, (stock_id, start, end))
    rows = cur.fetchall()
    cur.close()

    if len(rows) < 30:
        return None

    return {
        "open":   np.array([float(r[1] or 0) for r in rows]),
        "high":   np.array([float(r[2] or 0) for r in rows]),
        "low":    np.array([float(r[3] or 0) for r in rows]),
        "close":  np.array([float(r[4] or 0) for r in rows]),
        "volume": np.array([float(r[5] or 0) for r in rows]),
        "dates":  [str(r[0]) for r in rows],
    }


def compute_metrics(equity_curve: list, trade_log: list, risk_free_rate: float = 0.03) -> dict:
    """从权益曲线和交易日志计算性能指标"""
    if not equity_curve or len(equity_curve) < 2:
        return {
            "totalReturnPct": 0, "annualReturnPct": 0, "sharpeRatio": 0,
            "maxDrawdownPct": 0, "winRatePct": 0, "totalTrades": 0,
            "avgProfitPct": 0, "avgLossPct": 0, "profitFactor": 0,
        }

    initial = equity_curve[0]["equity"]
    final = equity_curve[-1]["equity"]
    total_return = (final / initial - 1) * 100 if initial > 0 else 0

    # Annualized return
    start_date = datetime.strptime(equity_curve[0]["date"], "%Y-%m-%d")
    end_date = datetime.strptime(equity_curve[-1]["date"], "%Y-%m-%d")
    years = max((end_date - start_date).days / 365.25, 0.1)
    annual_return = (((final / initial) ** (1.0 / years)) - 1) * 100 if initial > 0 else 0

    # Daily returns for Sharpe and max drawdown
    equities = np.array([e["equity"] for e in equity_curve])
    daily_returns = np.diff(equities) / equities[:-1]

    # Max drawdown
    peak = np.maximum.accumulate(equities)
    drawdowns = (equities - peak) / peak * 100
    max_dd = float(np.min(drawdowns)) if len(drawdowns) > 0 else 0

    # Sharpe ratio
    if len(daily_returns) > 0 and np.std(daily_returns) > 0:
        excess = np.mean(daily_returns) - (risk_free_rate / 252)
        sharpe = float(excess / np.std(daily_returns, ddof=1) * np.sqrt(252))
    else:
        sharpe = 0

    # Trade statistics
    completed_trades = [t for t in trade_log if t.get("pnl") is not None]
    wins = [t for t in completed_trades if t["pnl"] > 0]
    losses = [t for t in completed_trades if t["pnl"] <= 0]

    total_trades = len(completed_trades)
    win_rate = (len(wins) / total_trades * 100) if total_trades > 0 else 0
    avg_profit = np.mean([t["pnlPct"] for t in wins]) if wins else 0
    avg_loss = np.mean([t["pnlPct"] for t in losses]) if losses else 0
    total_wins = sum(t["pnl"] for t in wins) if wins else 0
    total_losses = abs(sum(t["pnl"] for t in losses)) if losses else 0
    profit_factor = (total_wins / total_losses) if total_losses > 0 else 0

    return {
        "totalReturnPct": round(total_return, 2),
        "annualReturnPct": round(annual_return, 2),
        "sharpeRatio": round(sharpe, 3),
        "maxDrawdownPct": round(max_dd, 2),
        "winRatePct": round(win_rate, 1),
        "totalTrades": total_trades,
        "avgProfitPct": round(float(avg_profit), 2),
        "avgLossPct": round(float(avg_loss), 2),
        "profitFactor": round(profit_factor, 2),
    }


def run_simple_backtest(strategy: dict, config: dict, conn, result_id: int) -> dict:
    """简单模式回测：基于规则树评估"""
    stocks = strategy.get("stocks", [])
    entry_rules = strategy.get("entry", {}).get("rules", [])
    entry_logic = strategy.get("entry", {}).get("logic", "all")
    exit_rules = strategy.get("exit", {}).get("rules", [])
    pos_method = strategy.get("positionSizing", {}).get("method", "equal_weight")
    pos_pct = float(strategy.get("positionSizing", {}).get("value", 10))

    start_date = config["startDate"]
    end_date = config["endDate"]
    initial_capital = float(config.get("initialCapital", 100000))
    commission_pct = float(config.get("commissionPct", 0.008))
    slippage_pct = float(config.get("slippagePct", 0.001))
    min_commission = float(config.get("minCommission", 5))

    # Load all stock data
    stock_data = {}
    for sym in stocks:
        data = load_ohlcv(conn, sym, start_date, end_date)
        if data is not None:
            stock_data[sym] = data
            print(f"  [信息] 加载 {sym}: {len(data['dates'])} 个交易日", flush=True)

    if not stock_data:
        print("[ERROR] 没有可用的股票数据", flush=True)
        return None

    # Build unified trading calendar
    all_dates = set()
    for sd in stock_data.values():
        all_dates.update(sd["dates"])
    trading_dates = sorted(all_dates)
    if len(trading_dates) < 2:
        print("[ERROR] 交易日期不足", flush=True)
        return None

    # Simulation state
    cash = initial_capital
    positions = {}  # symbol -> { shares, avg_cost, highest_close }
    equity_curve = []
    trade_log = []
    total_days = len(trading_dates)

    for di, date in enumerate(trading_dates):
        # Mark to market
        total_equity = cash
        for sym, pos in positions.items():
            sd = stock_data.get(sym)
            if sd and date in sd["dates"]:
                idx = sd["dates"].index(date)
                price = float(sd["close"][idx])
                total_equity += pos["shares"] * price

        equity_curve.append({
            "date": date,
            "equity": round(total_equity, 2),
            "cash": round(cash, 2),
        })

        if (di + 1) % 50 == 0:
            pct = (di + 1) / total_days * 100
            print(f"  [{di + 1}/{total_days} {pct:.1f}%] 回测 {date} — 权益 {total_equity:,.0f}", flush=True)

        # Evaluate each stock
        for sym in list(stock_data.keys()):
            sd = stock_data[sym]
            if date not in sd["dates"]:
                continue
            idx = sd["dates"].index(date)
            price = float(sd["close"][idx])
            has_position = sym in positions

            # Check exit conditions
            if has_position:
                pos = positions[sym]
                entry_cost = pos["avg_cost"]
                current_pnl_pct = (price / entry_cost - 1) * 100
                pos["highest_close"] = max(pos["highest_close"], price)

                should_sell = False
                reason = ""

                # Evaluate exit rules one by one
                for rule in exit_rules:
                    indicator = rule.get("indicator", "")
                    params = rule.get("params", {})

                    # Position-aware indicators
                    if indicator == "stop_loss":
                        pct = float(params.get("pct", 8))
                        if current_pnl_pct <= -pct:
                            should_sell = True
                            reason = f"止损 ({current_pnl_pct:.1f}%)"
                            break

                    elif indicator == "take_profit":
                        pct = float(params.get("pct", 20))
                        if current_pnl_pct >= pct:
                            should_sell = True
                            reason = f"止盈 ({current_pnl_pct:.1f}%)"
                            break

                    elif indicator == "trailing_stop":
                        pct = float(params.get("pct", 5))
                        trail_price = pos["highest_close"] * (1 - pct / 100)
                        if price < trail_price:
                            should_sell = True
                            reason = f"移动止损 ({current_pnl_pct:.1f}%)"
                            break

                    # Regular indicator rules
                    else:
                        val = eval_indicator(indicator, sd, params, idx)
                        if eval_condition(val, rule):
                            should_sell = True
                            reason = f"离场规则: {indicator}"
                            break

                if should_sell:
                    proceeds = pos["shares"] * price
                    pnl = proceeds - pos["shares"] * pos["avg_cost"]
                    pnl_pct = (price / pos["avg_cost"] - 1) * 100
                    cash += proceeds
                    trade_log.append({
                        "date": date, "symbol": sym, "action": "SELL",
                        "quantity": round(pos["shares"]),
                        "price": round(price, 2),
                        "pnl": round(float(pnl), 2),
                        "pnlPct": round(float(pnl_pct), 2),
                        "reason": reason,
                    })
                    del positions[sym]

            # Check entry conditions (only if no position)
            if not has_position and entry_rules:
                results = []
                for rule in entry_rules:
                    val = eval_indicator(rule["indicator"], sd, rule.get("params", {}), idx)
                    results.append(eval_condition(val, rule))
                should_buy = all(results) if entry_logic == "all" else any(results)

                if should_buy:
                    # Position sizing
                    if pos_method == "equal_weight":
                        n_stocks = len(stocks)
                        alloc = cash * pos_pct / 100
                    else:
                        alloc = cash * pos_pct / 100

                    if alloc < price * 10:
                        continue

                    exec_price = price * (1 + slippage_pct / 100)
                    shares = math.floor(alloc / exec_price)
                    cost = shares * exec_price
                    comm = max(cost * commission_pct, min_commission)
                    total_cost = cost + comm

                    if total_cost > cash:
                        shares = math.floor((cash - min_commission) / (exec_price * (1 + commission_pct)))
                        if shares <= 0:
                            continue
                        cost = shares * exec_price
                        comm = max(cost * commission_pct, min_commission)
                        total_cost = cost + comm

                    cash -= total_cost
                    positions[sym] = {
                        "shares": shares,
                        "avg_cost": exec_price + comm / shares,
                        "highest_close": price,
                    }
                    trade_log.append({
                        "date": date, "symbol": sym, "action": "BUY",
                        "quantity": shares,
                        "price": round(exec_price, 2),
                        "pnl": None, "pnlPct": None,
                        "reason": "入场规则触发",
                    })

    # Close remaining positions at last price
    if trading_dates:
        last_date = trading_dates[-1]
        for sym, pos in list(positions.items()):
            sd = stock_data.get(sym)
            if sd and last_date in sd["dates"]:
                idx = sd["dates"].index(last_date)
                price = float(sd["close"][idx])
                proceeds = pos["shares"] * price
                pnl = proceeds - pos["shares"] * pos["avg_cost"]
                pnl_pct = (price / pos["avg_cost"] - 1) * 100
                cash += proceeds
                trade_log.append({
                    "date": last_date, "symbol": sym, "action": "SELL",
                    "quantity": round(pos["shares"]),
                    "price": round(price, 2),
                    "pnl": round(float(pnl), 2),
                    "pnlPct": round(float(pnl_pct), 2),
                    "reason": "回测结束平仓",
                })
                del positions[sym]

        final_equity = cash
        equity_curve.append({
            "date": last_date,
            "equity": round(final_equity, 2),
            "cash": round(cash, 2),
        })

    metrics = compute_metrics(equity_curve, trade_log)
    return {"equityCurve": equity_curve, "metrics": metrics, "tradeLog": trade_log}


def run_advanced_backtest(strategy: dict, config: dict, conn, result_id: int) -> dict:
    """高级模式回测：执行用户自定义 Python 代码"""
    user_code = strategy.get("code", "")
    if not user_code.strip():
        print("[ERROR] 高级模式需要提供 Python 策略代码", flush=True)
        return None

    start_date = config["startDate"]
    end_date = config["endDate"]
    initial_capital = float(config.get("initialCapital", 100000))
    # Apply the same trading costs the simple path uses — otherwise custom-code
    # (advanced) backtests, which is what Guanlan generates, would show unrealistic
    # cost-free P&L and ignore the configured commission entirely.
    commission_pct = float(config.get("commissionPct", 0.008))
    slippage_pct = float(config.get("slippagePct", 0.001))
    min_commission = float(config.get("minCommission", 5))
    stocks = strategy.get("stocks", [])

    # Load data for stocks specified
    stock_data = {}
    for sym in stocks:
        data = load_ohlcv(conn, sym, start_date, end_date)
        if data is not None:
            stock_data[sym] = data
    if not stock_data:
        print("[ERROR] 没有可用的股票数据", flush=True)
        return None

    # Build trading calendar
    all_dates = set()
    for sd in stock_data.values():
        all_dates.update(sd["dates"])
    trading_dates = sorted(all_dates)
    total_days = len(trading_dates)

    # Sandbox: block dangerous builtins and escape vectors.
    # __import__ is required for the `import` statement itself to work inside exec().
    _SAFE_BUILTINS = {
        "abs": abs, "all": all, "any": any, "bool": bool, "dict": dict,
        "enumerate": enumerate, "filter": filter, "float": float, "int": int,
        "len": len, "list": list, "map": map, "max": max, "min": min,
        "range": range, "round": round, "sorted": sorted, "sum": sum,
        "tuple": tuple, "zip": zip, "print": print,
        "True": True, "False": False, "None": None,
        "isinstance": isinstance, "str": str, "type": type,
        "__import__": __import__,
    }
    imports = {"math": math, "np": np}
    globals_ns = {"__builtins__": _SAFE_BUILTINS, **imports}

    # Pre-validate: reject code containing escape patterns.
    # Check is case-insensitive to catch obfuscated payloads.
    _FORBIDDEN = [
        "os.", "subprocess", "import os", "import sys", "import subprocess",
        "from os", "from sys", "__import__", "eval(", "exec(", "compile(",
        "open(", "__class__", "__bases__", "__subclasses__", "__globals__",
        "__code__", "__dict__", "sys.", "shutil", "socket", "importlib",
    ]
    code_lower = user_code.lower()
    for pattern in _FORBIDDEN:
        if pattern in code_lower:
            print(f"[ERROR] 策略代码包含禁止的模式: {pattern}", flush=True)
            return None

    # Find and compile the decide function
    try:
        local_ns = {}
        exec(user_code, globals_ns, local_ns)
        decide_fn = local_ns.get("decide")
        if not callable(decide_fn):
            print("[ERROR] 策略代码必须定义 decide(ctx) 函数", flush=True)
            return None
    except Exception as e:
        print(f"[ERROR] 策略代码编译失败: {e}", flush=True)
        return None

    # Timeout: signal.alarm exists only on Unix. On Windows it's absent, so wrap
    # every alarm call — the loop and finally below call it unconditionally and
    # would otherwise raise AttributeError and crash the whole advanced backtest.
    _has_alarm = hasattr(signal, 'alarm')
    def _alarm(secs):
        if _has_alarm:
            signal.alarm(secs)
    if not _has_alarm:
        print("[WARN] 当前平台不支持 signal.alarm，策略执行无超时保护", flush=True)
    _alarm(60)

    try:
        cash = initial_capital
        positions = {}
        equity_curve = []
        trade_log = []

        for di, date in enumerate(trading_dates):
            if (di + 1) % 30 == 0:
                pct = (di + 1) / total_days * 100
                print(f"  [{di + 1}/{total_days} {pct:.1f}%] 回测 {date}", flush=True)

            for sym, sd in stock_data.items():
                if date not in sd["dates"]:
                    continue
                idx = sd["dates"].index(date)

                # Provide full history up to current date so AI-generated
                # strategies can compute MAs, std, etc. on ctx.close etc.
                prices = {
                    "open":  sd["open"][:idx+1],
                    "high":  sd["high"][:idx+1],
                    "low":   sd["low"][:idx+1],
                    "close": sd["close"][:idx+1],
                    "volume":sd["volume"][:idx+1],
                }
                ctx_dict = {
                    "symbol": sym,
                    "date": date,
                    **prices,
                    "has_position": sym in positions,
                    "shares": positions.get(sym, {}).get("shares", 0),
                    "avg_cost": positions.get(sym, {}).get("avg_cost", 0),
                    "cash": cash,
                    "total_equity": cash + sum(
                        positions.get(s, {}).get("shares", 0) * float(
                            stock_data[s]["close"][stock_data[s]["dates"].index(date)]
                        ) if date in stock_data[s]["dates"] else 0
                        for s in positions
                    ),
                }
                # Wrap in SimpleNamespace so both ctx["close"] (dict key) and
                # ctx.close (attribute) work — the AI agent generates the latter.
                ctx = SimpleNamespace(**ctx_dict, __getitem__=ctx_dict.__getitem__)

                try:
                    _alarm(5)  # 5 seconds per decision
                    result = decide_fn(ctx)
                    _alarm(60)  # reset to global timeout

                    if result is None:
                        continue

                    # Accept int returns from AI-generated strategies:
                    #   1 → BUY (use all cash), -1 → SELL (all shares), 0 → HOLD
                    if isinstance(result, (int, float)):
                        val = int(result)
                        if val > 0:
                            result = {"action": "BUY", "quantity": max(1, int(cash / (float(sd["close"][idx]) * 1.01)))}
                        elif val < 0:
                            pos = positions.get(sym)
                            result = {"action": "SELL", "quantity": pos["shares"] if pos else 0}
                        else:
                            continue
                    elif not isinstance(result, dict):
                        continue

                    action = result.get("action", "HOLD")
                    qty = result.get("quantity", 0)

                    if action == "BUY" and not positions.get(sym):
                        price = float(sd["close"][idx])
                        exec_price = price * (1 + slippage_pct / 100)
                        cost = qty * exec_price
                        comm = max(cost * commission_pct, min_commission)
                        total_cost = cost + comm
                        if total_cost <= cash and qty > 0:
                            cash -= total_cost
                            # Fold buy commission into avg_cost so P&L nets the fee.
                            positions[sym] = {"shares": qty, "avg_cost": exec_price + comm / qty}
                            trade_log.append({
                                "date": date, "symbol": sym, "action": "BUY",
                                "quantity": qty, "price": round(exec_price, 2),
                                "pnl": None, "pnlPct": None, "reason": "自定义策略",
                            })
                    elif action == "SELL" and sym in positions:
                        price = float(sd["close"][idx])
                        exec_price = price * (1 - slippage_pct / 100)
                        pos = positions[sym]
                        sell_qty = min(qty, pos["shares"]) if qty > 0 else pos["shares"]
                        proceeds = sell_qty * exec_price
                        comm = max(proceeds * commission_pct, min_commission)
                        proceeds -= comm
                        pnl = proceeds - sell_qty * pos["avg_cost"]
                        pnl_pct = (exec_price / pos["avg_cost"] - 1) * 100
                        cash += proceeds
                        trade_log.append({
                            "date": date, "symbol": sym, "action": "SELL",
                            "quantity": sell_qty, "price": round(exec_price, 2),
                            "pnl": round(float(pnl), 2), "pnlPct": round(float(pnl_pct), 2),
                            "reason": "自定义策略",
                        })
                        if sell_qty >= pos["shares"]:
                            del positions[sym]
                        else:
                            positions[sym]["shares"] -= sell_qty
                except Exception as e:
                    print(f"  [警告] {sym} @ {date} 策略执行错误: {e}", flush=True)

            # Record equity
            total_equity = cash
            for sym, pos in positions.items():
                sd = stock_data.get(sym)
                if sd and date in sd["dates"]:
                    idx = sd["dates"].index(date)
                    total_equity += pos["shares"] * float(sd["close"][idx])

            equity_curve.append({
                "date": date, "equity": round(total_equity, 2), "cash": round(cash, 2),
            })

        _alarm(0)  # cancel timeout
        metrics = compute_metrics(equity_curve, trade_log)
        return {"equityCurve": equity_curve, "metrics": metrics, "tradeLog": trade_log}

    except Exception as e:
        print(f"[ERROR] 高级回测异常: {e}", flush=True)
        traceback.print_exc()
        return None
    finally:
        _alarm(0)


# ── Walk-Forward 回测 ────────────────────────────────────────────────────

def run_walk_forward(strategy: dict, config: dict, conn, result_id: int):
    """
    Walk-forward backtest: rolling IS/OOS windows to assess strategy stability.

    Config options (in config dict):
        windowMonths: int = 24   — training window size in months
        stepMonths:   int = 6    — step forward each iteration
        oosMonths:    int = 6    — out-of-sample test period after each window
    """
    from datetime import datetime as dt

    start_date = config.get("startDate")
    end_date = config.get("endDate")
    initial_capital = config.get("initialCapital", 100000)
    window_months = config.get("windowMonths", 24)
    step_months = config.get("stepMonths", 6)
    oos_months = config.get("oosMonths", 6)

    if not start_date or not end_date:
        print("[ERROR] Walk-forward requires startDate and endDate", flush=True)
        return None

    # Generate rolling windows
    from dateutil.relativedelta import relativedelta as rd  # type: ignore
    ws = dt.strptime(start_date, "%Y-%m-%d")
    we = dt.strptime(end_date, "%Y-%m-%d")
    windows = []
    cursor = ws
    while True:
        train_start = cursor
        train_end = min(train_start + rd(months=window_months), we)
        oos_start = train_end + rd(days=1)
        oos_end = min(oos_start + rd(months=oos_months) - rd(days=1), we)
        if oos_start >= we:
            break
        windows.append({
            "trainStart": train_start.strftime("%Y-%m-%d"),
            "trainEnd": train_end.strftime("%Y-%m-%d"),
            "oosStart": oos_start.strftime("%Y-%m-%d"),
            "oosEnd": oos_end.strftime("%Y-%m-%d"),
        })
        cursor += rd(months=step_months)
        if cursor >= we:
            break

    if len(windows) < 2:
        print("[ERROR] 日期范围不足以生成 Walk-Forward 窗口", flush=True)
        return None

    print(f"=== Walk-Forward: {len(windows)} 个窗口 "
          f"({window_months}M训练 + {oos_months}M测试, 步长{step_months}M) ===", flush=True)

    # ── Optimize over parameter grid (if provided) or just run single strategy
    param_grid = config.get("paramGrid", {})
    all_trades = []
    oos_equity_pieces = []
    window_results = []

    for wi, w in enumerate(windows):
        print(f"\n--- 窗口 {wi+1}/{len(windows)}: "
              f"训练 {w['trainStart']}~{w['trainEnd']}, "
              f"测试 {w['oosStart']}~{w['oosEnd']} ---", flush=True)

        try:
            # In-sample: run (optionally parameter-sweep) on training period
            is_config = {**config, "startDate": w["trainStart"], "endDate": w["trainEnd"]}
            if param_grid:
                best = optimize_window(strategy, is_config, param_grid, conn, result_id)
            else:
                is_out = run_simple_backtest(strategy, is_config, conn, result_id)
                best = is_out

            if best is None:
                print(f"  窗口 {wi+1} IS 失败，跳过", flush=True)
                window_results.append({"window": wi+1, "status": "error"})
                continue

            is_metrics = best.get("metrics", {})

            # Out-of-sample: test on OOS period
            oos_config = {**config, "startDate": w["oosStart"], "endDate": w["oosEnd"],
                           "initialCapital": initial_capital}
            oos_out = run_simple_backtest(strategy, oos_config, conn, result_id)

            if oos_out is None:
                print(f"  窗口 {wi+1} OOS 失败，跳过", flush=True)
                window_results.append({"window": wi+1, "status": "error"})
                continue

            oos_metrics = oos_out.get("metrics", {})
            oos_curve = oos_out.get("equityCurve", [])
            oos_trades = oos_out.get("tradeLog", [])

            # Tag with window info
            for tr in oos_trades:
                tr["window"] = wi + 1
            all_trades.extend(oos_trades)
            oos_equity_pieces.extend(oos_curve)

            # Stability score: OOS Sharpe / IS Sharpe (closer to 1 = stable)
            is_sharpe = is_metrics.get("sharpeRatio", 0) or 0
            oos_sharpe = oos_metrics.get("sharpeRatio", 0) or 0
            stability = round(oos_sharpe / is_sharpe, 3) if is_sharpe > 0 else None

            wr = {
                "window": wi + 1,
                "status": "ok",
                "trainStart": w["trainStart"], "trainEnd": w["trainEnd"],
                "oosStart": w["oosStart"], "oosEnd": w["oosEnd"],
                "isMetrics": is_metrics,
                "oosMetrics": oos_metrics,
                "stability": stability,
            }
            window_results.append(wr)
            print(f"  窗口 {wi+1}: IS Sharpe={is_sharpe}, OOS Sharpe={oos_sharpe}, "
                  f"Stability={stability}", flush=True)

        except Exception as e:
            print(f"  窗口 {wi+1} 异常: {e}", flush=True)
            traceback.print_exc()
            window_results.append({"window": wi+1, "status": "error"})
            continue

    # ── Aggregate: build continuous OOS equity curve
    # Remove duplicate dates (last date of each window overlaps next window's first)
    seen_dates = set()
    equity_curve = []
    running_cash = initial_capital
    for pt in oos_equity_pieces:
        if pt["date"] not in seen_dates:
            equity_curve.append(pt)
            seen_dates.add(pt["date"])
            running_cash = pt["cash"]

    equity_curve.sort(key=lambda p: p["date"])

    # Aggregate metrics across all OOS windows
    metrics = compute_metrics(equity_curve, all_trades)

    # Additional walk-forward specific metrics
    oos_sharpes = [w["oosMetrics"].get("sharpeRatio", 0) or 0 for w in window_results if w["status"] == "ok"]
    stabilities = [w.get("stability") for w in window_results if w.get("stability") is not None]
    metrics["wfWindows"] = len(window_results)
    metrics["wfStability"] = round(sum(stabilities) / len(stabilities), 3) if stabilities else None
    metrics["wfOosSharpeAvg"] = round(sum(oos_sharpes) / len(oos_sharpes), 3) if oos_sharpes else None
    ok_windows = [w for w in window_results if w["status"] == "ok"]
    metrics["wfOosReturnAvg"] = round(
        sum(w["oosMetrics"].get("totalReturnPct", 0) or 0 for w in ok_windows)
        / len(ok_windows), 2) if ok_windows else None

    output = {
        "equityCurve": equity_curve,
        "metrics": metrics,
        "tradeLog": all_trades,
        "walkForward": {
            "windows": window_results,
            "paramGrid": param_grid,
            "wfSummary": {
                "stability": metrics["wfStability"],
                "oosSharpeAvg": metrics["wfOosSharpeAvg"],
                "oosReturnAvg": metrics["wfOosReturnAvg"],
            },
        },
    }
    return output


def run_optimize(strategy: dict, config: dict, conn, result_id: int):
    """Grid search over parameter combinations on full period. Returns best result + heatmap."""
    param_grid = config.get("paramGrid", {})
    if not param_grid:
        print("[ERROR] optimize mode requires paramGrid in config", flush=True)
        return None

    from itertools import product

    param_names = list(param_grid.keys())
    param_values = list(param_grid.values())
    combos = list(product(*param_values))
    total = len(combos)
    print(f"=== 参数优化: {total} 个组合 ===", flush=True)

    best_result, best_sharpe, best_combo = None, -999, None
    all_results = []

    for ci, combo in enumerate(combos):
        params = dict(zip(param_names, combo))
        print(f"[{ci+1}/{total} {(ci+1)/total*100:.1f}%] {params}", flush=True)

        trial = _apply_params(strategy, params)
        result = run_simple_backtest(trial, config, conn, result_id)
        if result:
            sharpe = result["metrics"].get("sharpeRatio", -999) or -999
            all_results.append({"params": params, "sharpe": round(sharpe, 3),
                                "returnPct": result["metrics"].get("totalReturnPct"),
                                "maxdd": result["metrics"].get("maxDrawdownPct")})
            if sharpe > best_sharpe:
                best_sharpe = sharpe
                best_result = result
                best_combo = params

    if best_result is None:
        return None

    best_result["optimizeHeatmap"] = all_results
    best_result["metrics"]["optimizeBestParams"] = best_combo
    best_result["metrics"]["optimizeTotalCombos"] = total
    return best_result


def _apply_params(strategy: dict, params: dict):
    """Apply parameter overrides to matching rules in entry/exit."""
    import copy
    s = copy.deepcopy(strategy)
    for rule_set_key in ["entry", "exit"]:
        rules = s.get(rule_set_key, {}).get("rules", [])
        for rule in rules:
            for pname, pval in params.items():
                # e.g. "sma_period" → match indicator "sma", set param "period"
                parts = pname.split("_", 1)
                if len(parts) == 2 and rule.get("indicator") == parts[0]:
                    rule["params"][parts[1]] = pval
    return s


def optimize_window(strategy: dict, config: dict, param_grid: dict, conn, result_id: int):
    """Grid search over parameter combinations, return best result by Sharpe."""
    from itertools import product
    import copy

    param_names = list(param_grid.keys())
    param_values = list(param_grid.values())
    best_result = None
    best_sharpe = -999

    for combo in product(*param_values):
        trial_strategy = copy.deepcopy(strategy)
        params = dict(zip(param_names, combo))

        # Apply params to matching rules
        for rule_set_key in ["entry", "exit"]:
            rules = trial_strategy.get(rule_set_key, {}).get("rules", [])
            for rule in rules:
                for pname, pval in params.items():
                    iname = rule.get("indicator", "")
                    if iname in pname:  # e.g. "sma_period" → SMA rule
                        rule["params"][pname.split("_", 1)[1]] = pval
                    elif pname.startswith(iname):
                        rule["params"][pname[len(iname)+1:]] = pval

        result = run_simple_backtest(trial_strategy, config, conn, result_id)
        if result:
            sharpe = result["metrics"].get("sharpeRatio", -999) or -999
            if sharpe > best_sharpe:
                best_sharpe = sharpe
                best_result = result

    return best_result


# ── 入口 ─────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description="Investory 量化回测引擎")
    p.add_argument("--input", required=True, help="输入 JSON 配置文件路径")
    return p.parse_args()


def main():
    args = parse_args()
    cfg = load_config()

    with open(args.input, "r", encoding="utf-8") as f:
        input_data = json.load(f)

    strategy_type = input_data.get("strategy_type", "simple")
    strategy = input_data.get("strategy", {})
    config = input_data.get("config", {})
    result_id = input_data.get("result_id", 0)

    print(f"=== 启动回测引擎 | 模式: {strategy_type} | ID: {result_id} ===", flush=True)

    conn = get_conn(cfg)
    try:
        if strategy_type == "walk_forward":
            output = run_walk_forward(strategy, config, conn, result_id)
        elif strategy_type == "optimize":
            output = run_optimize(strategy, config, conn, result_id)
        elif strategy_type == "advanced":
            output = run_advanced_backtest(strategy, config, conn, result_id)
        else:
            output = run_simple_backtest(strategy, config, conn, result_id)

        if output is None:
            print("[ERROR] 回测失败：无可用的股票数据或日期范围不足", flush=True)
            sys.exit(1)

        out_path = Path(f"backtest_output_{result_id}.json")
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(output, f, ensure_ascii=False, default=str)

        metrics = output["metrics"]
        trades = len(output["tradeLog"])
        print(f"=== 回测完成 | 总收益: {metrics['totalReturnPct']}% | "
              f"年化: {metrics['annualReturnPct']}% | Sharpe: {metrics['sharpeRatio']} | "
              f"最大回撤: {metrics['maxDrawdownPct']}% | 交易: {trades} 笔 ===", flush=True)

    except Exception as e:
        print(f"[ERROR] 回测引擎异常: {e}", flush=True)
        traceback.print_exc()
        # Write structured error so Java can surface it to the UI
        err_path = Path(f"backtest_error_{result_id}.json")
        with open(err_path, "w", encoding="utf-8") as f:
            json.dump({"error": str(e), "type": type(e).__name__,
                "traceback": traceback.format_exc()}, f, ensure_ascii=False)
        sys.exit(1)
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[ERROR] 用户中断", flush=True)
        sys.exit(1)
    except ImportError as e:
        msg = f"缺少依赖包: {e}"
        print(f"[ERROR] {msg}", flush=True)
        traceback.print_exc()
        sys.exit(1)
    except MemoryError:
        print("[ERROR] 内存不足，请减少测试股票数量或日期范围", flush=True)
        sys.exit(1)
    except Exception:
        print(f"[ERROR] 未预期的引擎错误", flush=True)
        traceback.print_exc()
        sys.exit(1)
