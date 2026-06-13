#!/usr/bin/env python3
"""
Investory 量化分析脚本

计算持仓股票的量化指标并缓存到数据库：
  - 历史分位数 (percentile_5y)：当前价格在5年历史中的百分位
  - Beta (beta_1y)：相对基准指数的1年Beta
  - 波动率 (volatility_1y)：1年年化历史波动率 %
  - 最大回撤 (max_drawdown_1y)：1年最大回撤 %

同时计算组合层面的历史危机压测和风险汇总。

用法:
    python analyze_quant.py --mode metrics              # 计算所有持仓股票的股票级指标
    python analyze_quant.py --mode scenario --portfolio-id N
    python analyze_quant.py --mode all --portfolio-id N  # 全量（Java SSE 调用此模式）
    python analyze_quant.py --dry-run -v                # 测试模式

配置: 与 fetch_stocks.py 相同（config.ini + 环境变量）
"""

import argparse
import configparser
import json
import logging
import os
import sys
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pymysql

# ─── 路径配置（跨平台，与 fetch_stocks.py 完全相同）────────────────────────────

SCRIPT_DIR  = Path(__file__).parent
CONFIG_FILE = SCRIPT_DIR / "config.ini"

# ─── 基准指数（全部在库中，直接查 stock_prices）──────────────────────────────

BENCHMARK_SYMBOL = {
    'SH': '000001.SH',
    'SZ': '000001.SH',
    'HK': 'HSI.HK',
    'US': 'GSPC.US',
}

# ─── 历史危机区间 ──────────────────────────────────────────────────────────────

SCENARIOS = [
    ('crisis_2008', '2008金融危机',   '2008-09-01', '2009-03-31'),
    ('crisis_2015', '2015A股崩盘',    '2015-06-12', '2015-09-15'),
    ('crisis_2020', '2020疫情暴跌',   '2020-02-20', '2020-03-23'),
    ('crisis_2022', '2022美联储加息', '2022-01-03', '2022-12-31'),
]


# ─── 配置加载（复用 fetch_stocks.py 逻辑）──────────────────────────────────────

def load_config() -> dict:
    cfg = configparser.ConfigParser()
    if CONFIG_FILE.exists():
        cfg.read(CONFIG_FILE, encoding='utf-8')

    def get(section, key, default=''):
        try:
            return cfg.get(section, key).strip()
        except (configparser.NoSectionError, configparser.NoOptionError):
            return default

    return {
        'db_host':     os.getenv('DB_HOST',     get('database', 'host',     'localhost')),
        'db_port':     int(os.getenv('DB_PORT', get('database', 'port',     '3306'))),
        'db_name':     os.getenv('DB_NAME',     get('database', 'name',     'investory')),
        'db_user':     os.getenv('DB_USER',     get('database', 'user',     'root')),
        'db_password': os.getenv('DB_PASSWORD', get('database', 'password', '')),
    }


def get_conn(cfg: dict):
    return pymysql.connect(
        host=cfg['db_host'], port=cfg['db_port'],
        db=cfg['db_name'], user=cfg['db_user'], password=cfg['db_password'],
        charset='utf8mb4', autocommit=False,
    )


def setup_logging(verbose: bool):
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format='%(asctime)s %(levelname)s %(message)s',
        datefmt='%H:%M:%S',
        stream=sys.stdout,
    )


# ─── 基准指数 stock_id 查找 ────────────────────────────────────────────────────

_benchmark_cache: dict = {}

def get_benchmark_id(conn, market: str):
    if market in _benchmark_cache:
        return _benchmark_cache[market]
    sym = BENCHMARK_SYMBOL.get(market)
    if not sym:
        _benchmark_cache[market] = None
        return None
    cur = conn.cursor()
    cur.execute('SELECT id FROM stocks WHERE symbol = %s', (sym,))
    row = cur.fetchone()
    cur.close()
    bid = row[0] if row else None
    _benchmark_cache[market] = bid
    return bid


# ─── 算法函数 ──────────────────────────────────────────────────────────────────

def calc_percentile_5y(conn, stock_id: int, current_close: float):
    """5年价格历史分位数，样本 < 30 返回 None。"""
    cutoff = (datetime.today() - timedelta(days=5 * 365)).strftime('%Y-%m-%d')
    cur = conn.cursor()
    cur.execute(
        'SELECT close FROM stock_prices WHERE stock_id=%s AND trade_date >= %s AND close > 0',
        (stock_id, cutoff),
    )
    closes = np.array([float(r[0]) for r in cur.fetchall()], dtype=np.float64)
    cur.close()
    if len(closes) < 30:
        return None
    return float(np.sum(closes <= current_close) / len(closes) * 100)


def calc_beta_and_vol(conn, stock_id: int, benchmark_id: int):
    """
    1年 Beta 和年化波动率。联表查询同日收盘价，避免多次 round-trip。
    返回 (beta, vol_pct) 或 (None, None)。
    """
    cutoff = (datetime.today() - timedelta(days=365)).strftime('%Y-%m-%d')
    cur = conn.cursor()
    cur.execute(
        '''SELECT s.close, b.close
           FROM stock_prices s
           JOIN stock_prices b ON b.stock_id=%s AND b.trade_date=s.trade_date
           WHERE s.stock_id=%s AND s.trade_date >= %s
             AND s.close > 0 AND b.close > 0
           ORDER BY s.trade_date''',
        (benchmark_id, stock_id, cutoff),
    )
    rows = cur.fetchall()
    cur.close()
    if len(rows) < 30:
        return None, None
    stock_c = np.array([float(r[0]) for r in rows], dtype=np.float64)
    bench_c = np.array([float(r[1]) for r in rows], dtype=np.float64)
    stock_r = np.diff(stock_c) / stock_c[:-1]
    bench_r = np.diff(bench_c) / bench_c[:-1]
    cov = np.cov(stock_r, bench_r)
    bench_var = cov[1, 1]
    beta = float(cov[0, 1] / bench_var) if bench_var > 1e-12 else None
    vol = float(np.std(stock_r, ddof=1) * np.sqrt(252) * 100)
    return beta, vol


def calc_vol_only(conn, stock_id: int):
    """仅计算年化波动率（当 Beta 无法算时的回退）。"""
    cutoff = (datetime.today() - timedelta(days=365)).strftime('%Y-%m-%d')
    cur = conn.cursor()
    cur.execute(
        'SELECT close FROM stock_prices WHERE stock_id=%s AND trade_date >= %s AND close > 0 ORDER BY trade_date',
        (stock_id, cutoff),
    )
    closes = np.array([float(r[0]) for r in cur.fetchall()], dtype=np.float64)
    cur.close()
    if len(closes) < 30:
        return None
    returns = np.diff(closes) / closes[:-1]
    return float(np.std(returns, ddof=1) * np.sqrt(252) * 100)


def calc_max_drawdown(conn, stock_id: int):
    """1年最大回撤（负百分比）。"""
    cutoff = (datetime.today() - timedelta(days=365)).strftime('%Y-%m-%d')
    cur = conn.cursor()
    cur.execute(
        'SELECT close FROM stock_prices WHERE stock_id=%s AND trade_date >= %s AND close > 0 ORDER BY trade_date',
        (stock_id, cutoff),
    )
    closes = np.array([float(r[0]) for r in cur.fetchall()], dtype=np.float64)
    cur.close()
    if len(closes) < 10:
        return None
    peak = np.maximum.accumulate(closes)
    drawdowns = (closes - peak) / peak * 100
    return float(np.min(drawdowns))


def calc_scenario_return(conn, stock_id: int, start_date: str, end_date: str):
    """
    计算区间回报率。取起始日期之后第一个可用交易日 和 终止日期之前最后一个交易日。
    返回百分比，若数据不足返回 None。
    """
    cur = conn.cursor()
    cur.execute(
        'SELECT close FROM stock_prices WHERE stock_id=%s AND trade_date >= %s AND close > 0 ORDER BY trade_date LIMIT 1',
        (stock_id, start_date),
    )
    row_s = cur.fetchone()
    cur.execute(
        'SELECT close FROM stock_prices WHERE stock_id=%s AND trade_date <= %s AND close > 0 ORDER BY trade_date DESC LIMIT 1',
        (stock_id, end_date),
    )
    row_e = cur.fetchone()
    cur.close()
    if not row_s or not row_e:
        return None
    c_s, c_e = float(row_s[0]), float(row_e[0])
    if c_s == 0:
        return None
    return (c_e - c_s) / c_s * 100


# ─── upsert 辅助 ───────────────────────────────────────────────────────────────

def ensure_factor_columns(conn):
    """Add factor columns to stock_metric_cache if they don't exist."""
    cols = [
        ("momentum_12m",     "DOUBLE"),
        ("size_factor",      "DOUBLE"),
        ("value_factor",     "DOUBLE"),
        ("quality_score",    "DOUBLE"),
        ("factor_style",     "VARCHAR(32)"),
    ]
    for col, dtype in cols:
        try:
            cur = conn.cursor()
            cur.execute(f"ALTER TABLE stock_metric_cache ADD COLUMN {col} {dtype}")
            cur.close()
        except Exception:
            pass  # column already exists


def upsert_stock_metric(conn, stock_id, percentile, beta, vol, maxdd, benchmark_symbol,
                         momentum=None, size_factor=None, value_factor=None,
                         quality=None, factor_style=None, dry_run=False):
    if dry_run:
        return
    cur = conn.cursor()
    cur.execute(
        '''INSERT INTO stock_metric_cache
             (stock_id, percentile_5y, beta_1y, volatility_1y, max_drawdown_1y,
              benchmark_symbol, momentum_12m, size_factor, value_factor,
              quality_score, factor_style, computed_at)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW())
           ON DUPLICATE KEY UPDATE
             percentile_5y=VALUES(percentile_5y),
             beta_1y=VALUES(beta_1y),
             volatility_1y=VALUES(volatility_1y),
             max_drawdown_1y=VALUES(max_drawdown_1y),
             benchmark_symbol=VALUES(benchmark_symbol),
             momentum_12m=VALUES(momentum_12m),
             size_factor=VALUES(size_factor),
             value_factor=VALUES(value_factor),
             quality_score=VALUES(quality_score),
             factor_style=VALUES(factor_style),
             computed_at=NOW()''',
        (stock_id, percentile, beta, vol, maxdd, benchmark_symbol,
         momentum, size_factor, value_factor, quality, factor_style),
    )
    conn.commit()
    cur.close()


def upsert_scenario(conn, portfolio_id, key, name, start_date, end_date, total_pnl, detail_json, dry_run):
    if dry_run:
        return
    cur = conn.cursor()
    cur.execute(
        '''INSERT INTO portfolio_scenario_cache
             (portfolio_id, scenario_key, scenario_name, start_date, end_date, total_pnl_pct, detail_json, computed_at)
           VALUES (%s, %s, %s, %s, %s, %s, %s, NOW())
           ON DUPLICATE KEY UPDATE
             scenario_name=VALUES(scenario_name),
             start_date=VALUES(start_date),
             end_date=VALUES(end_date),
             total_pnl_pct=VALUES(total_pnl_pct),
             detail_json=VALUES(detail_json),
             computed_at=NOW()''',
        (portfolio_id, key, name, start_date, end_date, total_pnl, detail_json),
    )
    conn.commit()
    cur.close()


def upsert_risk(conn, portfolio_id, weighted_beta, var_95, maxdd, dry_run):
    if dry_run:
        return
    cur = conn.cursor()
    cur.execute(
        '''INSERT INTO portfolio_risk_cache
             (portfolio_id, weighted_beta, var_95_pct, portfolio_maxdd, computed_at)
           VALUES (%s, %s, %s, %s, NOW())
           ON DUPLICATE KEY UPDATE
             weighted_beta=VALUES(weighted_beta),
             var_95_pct=VALUES(var_95_pct),
             portfolio_maxdd=VALUES(portfolio_maxdd),
             computed_at=NOW()''',
        (portfolio_id, weighted_beta, var_95, maxdd),
    )
    conn.commit()
    cur.close()


# ─── 主计算模块 ────────────────────────────────────────────────────────────────

def calc_momentum(conn, stock_id: int):
    """12-1个月动量：最近12个月收益率 减 最近1个月收益率（避免反转效应）。"""
    cur = conn.cursor()
    cur.execute(
        'SELECT close, trade_date FROM stock_prices WHERE stock_id=%s AND close > 0 '
        'AND trade_date >= DATE_SUB(CURDATE(), INTERVAL 13 MONTH) ORDER BY trade_date',
        (stock_id,),
    )
    rows = cur.fetchall()
    cur.close()
    if len(rows) < 120:  # need ~1 year of data
        return None
    closes = np.array([float(r[0]) for r in rows], dtype=np.float64)
    # 12-month return (all rows) minus 1-month return (last ~22 rows)
    ret_12m = (closes[-1] - closes[0]) / closes[0] if closes[0] > 0 else 0
    m1_idx = max(0, len(closes) - 22)
    ret_1m = (closes[-1] - closes[m1_idx]) / closes[m1_idx] if closes[m1_idx] > 0 else 0
    return float((ret_12m - ret_1m) * 100)


def calc_factor_exposures(conn, stock_id: int):
    """从 stock_fundamentals 读取 size/value 因子暴露。"""
    try:
        cur = conn.cursor()
        cur.execute(
            'SELECT market_cap, pb FROM stock_fundamentals WHERE stock_id=%s', (stock_id,),
        )
        row = cur.fetchone()
        cur.close()
        if not row:
            return None, None
        # Size factor: ln(market_cap), larger = bigger company
        market_cap = float(row[0]) if row[0] else None
        size = np.log(market_cap) if market_cap and market_cap > 0 else None
        # Value factor: 1/PB (book-to-market proxy), higher = more value
        pb = float(row[1]) if row[1] else None
        value = 1.0 / pb if pb and pb > 0 else None
        return size, value
    except Exception:
        return None, None


def classify_factor_style(size_factor, value_factor):
    """Classify into 2×2 style grid based on factor exposures."""
    if size_factor is None or value_factor is None:
        return None
    # Neutral threshold: relative to all holdings' median (computed outside)
    # Here we just return raw classification — caller normalizes
    return None  # populated after cross-sectional ranking


def compute_metrics(conn, dry_run: bool):
    """计算所有有持仓股票的 stock_metric_cache。进度行格式匹配 Java PROGRESS_RE。"""
    log = logging.getLogger('metrics')
    ensure_factor_columns(conn)

    cur = conn.cursor()
    cur.execute(
        '''SELECT DISTINCT h.stock_id, s.symbol, s.name, s.market
           FROM holdings h
           JOIN stocks s ON s.id = h.stock_id
           WHERE h.total_shares > 0
           ORDER BY s.market, s.symbol''',
    )
    stocks = cur.fetchall()
    cur.close()

    total = len(stocks)
    if total == 0:
        log.info('没有持仓股票，跳过 metrics 计算')
        return

    log.info(f'=== 开始计算 metrics + 因子暴露，共 {total} 只持仓股票 ===')
    ok = 0

    # First pass: compute individual stock metrics
    raw_results = []  # (stock_id, symbol, name, market, beta, vol, size, value, momentum, quality)
    for seq, (stock_id, symbol, name, market) in enumerate(stocks, 1):
        pct_done = seq / total * 100
        log.info(f'  [{seq}/{total} {pct_done:.1f}%] {name}({symbol})')
        try:
            cur2 = conn.cursor()
            cur2.execute(
                'SELECT close FROM stock_prices WHERE stock_id=%s AND close > 0 ORDER BY trade_date DESC LIMIT 1',
                (stock_id,),
            )
            row = cur2.fetchone()
            cur2.close()
            if not row:
                log.debug(f'    {symbol} 无收盘价，跳过')
                continue
            current_close = float(row[0])

            percentile = calc_percentile_5y(conn, stock_id, current_close)
            bench_id = get_benchmark_id(conn, market)
            bench_sym = BENCHMARK_SYMBOL.get(market)
            if bench_id:
                beta, vol = calc_beta_and_vol(conn, stock_id, bench_id)
            else:
                beta = None
                vol = calc_vol_only(conn, stock_id)
            maxdd = calc_max_drawdown(conn, stock_id)
            momentum = calc_momentum(conn, stock_id)
            size_f, value_f = calc_factor_exposures(conn, stock_id)
            quality = float(1.0 / vol * 10) if vol and vol > 0 else None  # inverse vol proxy

            raw_results.append((stock_id, symbol, name, market, beta, vol, maxdd, percentile,
                                bench_sym, momentum, size_f, value_f, quality))
        except Exception as e:
            log.warning(f'    {symbol} 计算失败: {e}')

    # Second pass: cross-sectional ranking for size/value → style classification
    size_vals = [r[10] for r in raw_results if r[10] is not None]
    val_vals  = [r[11] for r in raw_results if r[11] is not None]
    med_size = float(np.median(size_vals)) if size_vals else None
    med_val  = float(np.median(val_vals))  if val_vals  else None

    for (stock_id, symbol, name, market, beta, vol, maxdd, percentile,
         bench_sym, momentum, size_f, value_f, quality) in raw_results:
        # Classify style based on cross-sectional median
        style = None
        if size_f is not None and value_f is not None and med_size and med_val:
            is_large = size_f >= med_size
            is_value = value_f >= med_val
            if is_large and is_value:    style = "大盘价值"
            elif is_large and not is_value: style = "大盘成长"
            elif not is_large and is_value: style = "小盘价值"
            else:                        style = "小盘成长"

        upsert_stock_metric(conn, stock_id, percentile, beta, vol, maxdd, bench_sym,
                            momentum=momentum, size_factor=size_f, value_factor=value_f,
                            quality=quality, factor_style=style, dry_run=dry_run)
        ok += 1

    log.info(f'=== metrics 完成: 写入 {ok} 行，无数据(停牌/错误) {total - ok} 只 ===')


def compute_scenarios(conn, portfolio_id: int, dry_run: bool):
    """计算组合的历史危机压测和风险汇总。"""
    log = logging.getLogger('scenario')

    # 获取持仓及市值权重
    cur = conn.cursor()
    cur.execute(
        '''SELECT h.stock_id, s.symbol, s.name, s.market,
                  h.total_shares,
                  (SELECT sp.close FROM stock_prices sp
                   WHERE sp.stock_id = h.stock_id AND sp.close > 0
                   ORDER BY sp.trade_date DESC LIMIT 1) AS last_close
           FROM holdings h
           JOIN stocks s ON s.id = h.stock_id
           WHERE h.portfolio_id = %s AND h.total_shares > 0''',
        (portfolio_id,),
    )
    holdings = cur.fetchall()
    cur.close()

    if not holdings:
        log.info(f'portfolio_id={portfolio_id} 无持仓，跳过压测')
        return

    # 计算各持仓市值权重
    weights = []
    for stock_id, symbol, name, market, shares, last_close in holdings:
        if last_close is None:
            continue
        mv = float(shares) * float(last_close)
        weights.append((stock_id, symbol, name, market, mv))

    total_mv = sum(w[4] for w in weights)
    if total_mv == 0:
        log.info('总市值为0，跳过压测')
        return

    total_scenarios = len(SCENARIOS)
    log.info(f'=== 开始压测，组合 {portfolio_id}，共 {len(weights)} 只持仓 × {total_scenarios} 个情景 ===')

    for s_idx, (key, name, start_date, end_date) in enumerate(SCENARIOS, 1):
        pct_done = s_idx / total_scenarios * 100
        log.info(f'  [{s_idx}/{total_scenarios} {pct_done:.1f}%] {name}({start_date}~{end_date})')
        details = []
        weighted_sum = 0.0
        for stock_id, symbol, sname, market, mv in weights:
            w = mv / total_mv
            ret = calc_scenario_return(conn, stock_id, start_date, end_date)
            if ret is not None:
                weighted_sum += w * ret
            details.append({
                'stockId':    stock_id,
                'stockName':  sname,
                'holdingPct': round(w * 100, 2),
                'returnPct':  round(ret, 2) if ret is not None else None,
            })

        upsert_scenario(conn, portfolio_id, key, name, start_date, end_date,
                        round(weighted_sum, 4), json.dumps(details, ensure_ascii=False), dry_run)

    # ── 风险汇总：加权 Beta + VaR + 组合最大回撤 ──────────────────────────────
    weighted_beta = 0.0
    beta_weight_sum = 0.0
    for stock_id, symbol, sname, market, mv in weights:
        bench_id = get_benchmark_id(conn, market)
        if not bench_id:
            continue
        w = mv / total_mv
        beta, _ = calc_beta_and_vol(conn, stock_id, bench_id)
        if beta is not None:
            weighted_beta += w * beta
            beta_weight_sum += w

    wb = round(weighted_beta / beta_weight_sum, 4) if beta_weight_sum > 0 else None

    # VaR：优先用 daily_portfolio_value，样本不足则用等权模拟
    var_95 = None
    maxdd_portfolio = None
    cur3 = conn.cursor()
    cur3.execute(
        '''SELECT total_value FROM daily_portfolio_value
           WHERE portfolio_id=%s AND snapshot_date >= DATE_SUB(CURDATE(), INTERVAL 1 YEAR)
           ORDER BY snapshot_date''',
        (portfolio_id,),
    )
    dpv_rows = cur3.fetchall()
    cur3.close()

    if len(dpv_rows) >= 60:
        vals = np.array([float(r[0]) for r in dpv_rows], dtype=np.float64)
        daily_ret = np.diff(vals) / vals[:-1] * 100
        var_95 = round(float(np.percentile(daily_ret, 5)), 4)
        peak = np.maximum.accumulate(vals)
        maxdd_portfolio = round(float(np.min((vals - peak) / peak * 100)), 4)
    else:
        # 等权模拟：取各持仓1年日收益率等权平均
        all_rets = []
        cutoff = (datetime.now() - timedelta(days=365)).strftime('%Y-%m-%d')
        for stock_id, symbol, sname, market, mv in weights:
            cur4 = conn.cursor()
            cur4.execute(
                'SELECT close FROM stock_prices WHERE stock_id=%s AND trade_date >= %s AND close > 0 ORDER BY trade_date',
                (stock_id, cutoff),
            )
            closes_raw = [float(r[0]) for r in cur4.fetchall()]
            cur4.close()
            if len(closes_raw) > 30:
                c = np.array(closes_raw, dtype=np.float64)
                all_rets.append(np.diff(c) / c[:-1] * 100)
        if all_rets:
            min_len = min(len(r) for r in all_rets)
            stacked = np.stack([r[-min_len:] for r in all_rets])
            port_ret = np.mean(stacked, axis=0)
            var_95 = round(float(np.percentile(port_ret, 5)), 4)

    upsert_risk(conn, portfolio_id, wb, var_95, maxdd_portfolio, dry_run)
    log.info(f'=== 压测完成: 加权Beta={wb}, VaR95={var_95}%, MaxDD={maxdd_portfolio}% ===')


# ─── 入口 ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description='Investory 量化分析')
    parser.add_argument('--mode', choices=['metrics', 'scenario', 'all'], default='metrics')
    parser.add_argument('--portfolio-id', type=int, default=None)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('-v', '--verbose', action='store_true')
    args = parser.parse_args()

    setup_logging(args.verbose)
    log = logging.getLogger('main')

    if args.mode in ('scenario', 'all') and args.portfolio_id is None:
        log.error('--mode scenario/all 需要提供 --portfolio-id')
        sys.exit(1)

    cfg = load_config()
    conn = get_conn(cfg)
    log.info(f'已连接数据库 {cfg["db_host"]}:{cfg["db_port"]}/{cfg["db_name"]}')
    if args.dry_run:
        log.info('[dry-run] 不写入数据库')

    try:
        if args.mode in ('metrics', 'all'):
            compute_metrics(conn, args.dry_run)
        if args.mode in ('scenario', 'all'):
            compute_scenarios(conn, args.portfolio_id, args.dry_run)
    finally:
        conn.close()

    log.info('analyze_quant.py 全部完成')


if __name__ == '__main__':
    main()
