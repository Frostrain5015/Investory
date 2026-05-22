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
    stop_loss_pct = float(strategy.get("exit", {}).get("stopLossPct", 0) or 0)
    take_profit_pct = float(strategy.get("exit", {}).get("takeProfitPct", 0) or 0)
    trailing_stop_pct = float(strategy.get("exit", {}).get("trailingStopPct", 0) or 0)
    pos_method = strategy.get("positionSizing", {}).get("method", "equal_weight")
    pos_pct = float(strategy.get("positionSizing", {}).get("value", 10))

    start_date = config["startDate"]
    end_date = config["endDate"]
    initial_capital = float(config.get("initialCapital", 100000))
    commission_pct = float(config.get("commissionPct", 0.0003))
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

            # Check exit conditions first
            if has_position:
                pos = positions[sym]
                entry_cost = pos["avg_cost"]
                current_pnl_pct = (price / entry_cost - 1) * 100

                # Update trailing stop
                pos["highest_close"] = max(pos["highest_close"], price)
                trailing_price = pos["highest_close"] * (1 - trailing_stop_pct / 100)

                should_sell = False
                reason = ""

                # Stop loss
                if stop_loss_pct and current_pnl_pct <= -stop_loss_pct:
                    should_sell = True
                    reason = f"止损 ({current_pnl_pct:.1f}%)"

                # Take profit
                elif take_profit_pct and current_pnl_pct >= take_profit_pct:
                    should_sell = True
                    reason = f"止盈 ({current_pnl_pct:.1f}%)"

                # Trailing stop
                elif trailing_stop_pct and price < trailing_price:
                    should_sell = True
                    reason = f"移动止损 ({current_pnl_pct:.1f}%)"

                # Rule-based exit
                if not should_sell and exit_rules:
                    results = []
                    for rule in exit_rules:
                        val = eval_indicator(rule["indicator"], sd, rule.get("params", {}), idx)
                        results.append(eval_condition(val, rule))
                    should_sell = all(results) if entry_logic == "all" else any(results)
                    if should_sell:
                        reason = "离场规则触发"

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

    # Restricted execution environment
    restricted_builtins = {
        "abs": abs, "all": all, "any": any, "bool": bool, "dict": dict,
        "enumerate": enumerate, "filter": filter, "float": float, "int": int,
        "len": len, "list": list, "map": map, "max": max, "min": min,
        "range": range, "round": round, "sorted": sorted, "sum": sum,
        "tuple": tuple, "zip": zip, "print": print,
        "True": True, "False": False, "None": None,
        "math": math, "np": np,
    }

    # Find and compile the decide function
    try:
        local_ns = {}
        exec(user_code, restricted_builtins, local_ns)
        decide_fn = local_ns.get("decide")
        if not callable(decide_fn):
            print("[ERROR] 策略代码必须定义 decide(ctx) 函数", flush=True)
            return None
    except Exception as e:
        print(f"[ERROR] 策略代码编译失败: {e}", flush=True)
        return None

    # Set timeout
    signal.alarm(60)

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

                ctx = {
                    "symbol": sym,
                    "date": date,
                    "open": float(sd["open"][idx]),
                    "high": float(sd["high"][idx]),
                    "low": float(sd["low"][idx]),
                    "close": float(sd["close"][idx]),
                    "volume": float(sd["volume"][idx]),
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

                try:
                    signal.alarm(5)  # 5 seconds per decision
                    result = decide_fn(ctx)
                    signal.alarm(60)  # reset to global timeout

                    if result is None:
                        continue

                    action = result.get("action", "HOLD")
                    qty = result.get("quantity", 0)

                    if action == "BUY" and not positions.get(sym):
                        price = float(sd["close"][idx])
                        cost = qty * price
                        if cost <= cash and qty > 0:
                            cash -= cost
                            positions[sym] = {"shares": qty, "avg_cost": price}
                            trade_log.append({
                                "date": date, "symbol": sym, "action": "BUY",
                                "quantity": qty, "price": round(price, 2),
                                "pnl": None, "pnlPct": None, "reason": "自定义策略",
                            })
                    elif action == "SELL" and sym in positions:
                        price = float(sd["close"][idx])
                        pos = positions[sym]
                        sell_qty = min(qty, pos["shares"]) if qty > 0 else pos["shares"]
                        proceeds = sell_qty * price
                        pnl = proceeds - sell_qty * pos["avg_cost"]
                        pnl_pct = (price / pos["avg_cost"] - 1) * 100
                        cash += proceeds
                        trade_log.append({
                            "date": date, "symbol": sym, "action": "SELL",
                            "quantity": sell_qty, "price": round(price, 2),
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

        signal.alarm(0)  # cancel timeout
        metrics = compute_metrics(equity_curve, trade_log)
        return {"equityCurve": equity_curve, "metrics": metrics, "tradeLog": trade_log}

    except Exception as e:
        print(f"[ERROR] 高级回测异常: {e}", flush=True)
        traceback.print_exc()
        return None
    finally:
        signal.alarm(0)


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
        if strategy_type == "advanced":
            output = run_advanced_backtest(strategy, config, conn, result_id)
        else:
            output = run_simple_backtest(strategy, config, conn, result_id)

        if output is None:
            print("[ERROR] 回测失败", flush=True)
            sys.exit(1)

        out_path = Path(f"backtest_output_{result_id}.json")
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(output, f, ensure_ascii=False, default=str)

        metrics = output["metrics"]
        trades = len(output["tradeLog"])
        print(f"=== 回测完成 | 总收益: {metrics['totalReturnPct']}% | "
              f"年化: {metrics['annualReturnPct']}% | Sharpe: {metrics['sharpeRatio']} | "
              f"最大回撤: {metrics['maxDrawdownPct']}% | 交易: {trades} 笔 ===", flush=True)

    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已中断。", flush=True)
        sys.exit(0)
    except Exception:
        traceback.print_exc()
        sys.exit(1)
