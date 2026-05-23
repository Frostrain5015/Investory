#!/usr/bin/env python3
"""Investory「观澜」AI Agent — 支持 Function Calling"""

import argparse
import json
import os
import sys
import traceback
import concurrent.futures
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
KB_FILE = SCRIPT_DIR / "ai_knowledge_base.json"

# DashScope model routing: fast model for simple queries, configured model for deep analysis
DASHSCOPE_FAST_MODEL = "qwen-turbo-latest"

# Keywords that signal the user needs deep analysis — always use the full model
_COMPLEX_SIGNALS = [
    "分析", "研究", "报告", "评估", "评价", "优化", "建议", "推荐",
    "详细", "全面", "深入", "综合", "系统性",
    "帮我写", "生成策略", "写一个策略", "回测",
    "比较", "对比", "风险", "夏普", "回撤", "收益率",
    "组合", "配置", "调仓", "再平衡", "仓位",
    "基本面", "估值", "财务", "行业", "赛道",
]

def _is_complex_query(messages: list) -> bool:
    """Return True if the latest user message warrants the full model."""
    last_user = next((m for m in reversed(messages) if m.get("role") == "user"), None)
    if not last_user:
        return True
    text = str(last_user.get("content", ""))
    if any(k in text for k in _COMPLEX_SIGNALS):
        return True
    # Long messages almost always need thorough reasoning
    return len(text) > 60


def load_knowledge_base() -> dict:
    if KB_FILE.exists():
        try: return json.loads(KB_FILE.read_text(encoding="utf-8"))
        except: pass
    return {}


def build_system_prompt(kb: dict) -> str:
    principles_text = "\n".join(
        f"- **{p['name']}**: {p['description']}（应用：{p['application']}）"
        for p in kb.get("core_principles", [])
    )
    metrics_text = "\n".join(f"- **{k}**: {v}" for k, v in kb.get("key_metrics_guide", {}).items())
    return f"""你是「观澜」（Horizon），Investory 内置的分析助手。风格：冷静、专业、简洁。不寒暄，不恭维，不废话，不主动给建议。用数据说话。

你遵循价值投资框架。参考原则：
{principles_text}

指标解读：
{metrics_text}

工具调用规则：
- 用户提到某个策略时，必须先用 list_strategies 查找，再调用 get_strategy 获取完整规则
- 用户问"评价策略"时：list_strategies → get_strategy → 从规则合理性、逻辑一致性、潜在缺陷三个维度评价
- 用户问组合问题时：先调 get_portfolio 拿持仓，需要量化指标时再调 get_stock_metrics
- 连续调用上限：5 次。超出则基于已有数据分析，告知用户还缺什么

回复规则：
- 每次不超过 3 句。但生成代码/策略/表格时不受此限
- 用户说"你好"只需回"你好"
- 如果有部分数据但不够完整，先分享已有的，再说"以上信息不完整"
- 没有数据时直说"没有相关数据"，不说"不确定"这种模糊词
- 不带表情，不带感叹号
- 绝对禁止在对话中输出Python代码。代码通过generate_strategy工具的code字段传递，用户不可见"""


# ── Symbol resolution ────────────────────────────────────────────────────

def resolve_symbol(conn, symbol: str):
    """将用户输入的 symbol 转换为 DB 格式"""
    cur = conn.cursor()
    cur.execute("SELECT symbol FROM stocks WHERE symbol=%s", (symbol,))
    row = cur.fetchone()
    if row: cur.close(); return row[0]
    if '.' in symbol:
        parts = symbol.rsplit('.', 1)
        code, market = parts[0], parts[1].upper()
        if market in ('SH','SZ'):
            prefix = '1' if market == 'SH' else '0'
            db_sym = f"{prefix}.{code}"
            cur.execute("SELECT symbol FROM stocks WHERE symbol=%s", (db_sym,))
            row = cur.fetchone()
            if row: cur.close(); return row[0]
    cur.execute("SELECT symbol FROM stocks WHERE symbol LIKE %s", (f"%{symbol}%",))
    row = cur.fetchone()
    cur.close()
    return row[0] if row else None


# ── Database tools ──────────────────────────────────────────────────────

def get_db_conn():
    from db import load_config, get_conn as db_get_conn
    cfg = load_config()
    return db_get_conn(cfg)


def tool_get_portfolio(portfolio_id: int) -> dict:
    """获取持仓组合概要"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("""
        SELECT s.symbol, s.name, s.market, h.total_shares, h.avg_cost, h.total_invested,
               (SELECT close FROM stock_prices WHERE stock_id=s.id ORDER BY trade_date DESC LIMIT 1) AS price
        FROM holdings h JOIN stocks s ON h.stock_id=s.id
        WHERE h.portfolio_id=%s AND h.total_shares>0 ORDER BY h.total_invested DESC
    """, (portfolio_id,))
    rows = cur.fetchall()
    holdings = []
    total_value = 0
    for r in rows:
        sym, name, mkt, shares, cost, invested, price = r
        price = float(price or 0)
        mv = float(shares) * price if price else float(invested or 0)
        pnl_pct = (mv/float(invested)-1)*100 if invested and float(invested)>0 else 0
        total_value += mv
        holdings.append({"symbol": sym, "name": name, "market": mkt, "shares": int(float(shares)),
                         "invested": round(float(invested or 0),0), "mv": round(mv,0),
                         "pnlPct": round(pnl_pct,1), "price": price})
    cur.close(); conn.close()
    for h in holdings: h["weight"] = round(h["mv"]/total_value*100,1) if total_value>0 else 0
    return {"count": len(holdings), "totalValue": round(total_value,0), "holdings": holdings}


def tool_get_stock_metrics(symbol: str) -> dict:
    """获取单只股票的量化指标"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("SELECT s.id FROM stocks s WHERE s.symbol=%s OR s.symbol LIKE %s", (symbol, f"%{symbol}"))
    row = cur.fetchone()
    if not row: cur.close(); conn.close(); return {"error": f"未找到股票 {symbol}"}
    sid = row[0]
    cur.execute("SELECT percentile_5y, beta_1y, volatility_1y, max_drawdown_1y FROM stock_metric_cache WHERE stock_id=%s", (sid,))
    m = cur.fetchone()
    cur.close(); conn.close()
    if not m: return {"symbol": symbol, "metrics": None, "note": "暂无缓存数据"}
    return {"symbol": symbol, "percentile_5y": round(float(m[0]),1) if m[0] else None,
            "beta": round(float(m[1]),2) if m[1] else None,
            "volatility": round(float(m[2]),1) if m[2] else None,
            "maxDrawdown": round(float(m[3]),1) if m[3] else None}


MAX_ROWS = 5000
MAX_MULTI_ROWS = 1000
MAX_PNL_ROWS = 500
MAX_CORR_STOCKS = 10
MAX_TOOL_CALLS = 5

def tool_get_stock_price(symbol: str) -> dict:
    """获取个股最新行情"""
    conn = get_db_conn()
    cur = conn.cursor()
    db_sym = resolve_symbol(conn, symbol)
    if not db_sym: cur.close(); conn.close(); return {"error": f"未找到 {symbol}"}
    cur.execute("SELECT id FROM stocks WHERE symbol=%s", (db_sym,))
    sid = cur.fetchone()[0]
    cur.execute("SELECT close, trade_date FROM stock_prices WHERE stock_id=%s ORDER BY trade_date DESC LIMIT 2", (sid,))
    rows = cur.fetchall()
    cur.close(); conn.close()
    if len(rows) < 1: return {"symbol": symbol, "price": None, "note": "无价格数据"}
    price = float(rows[0][0])
    prev = float(rows[1][0]) if len(rows) > 1 else price
    chg = price - prev
    chg_pct = (chg / prev * 100) if prev else 0
    return {"symbol": symbol, "price": round(price,2), "change": round(chg,2), "changePct": round(chg_pct,2), "date": str(rows[0][1])}

def tool_get_pnl_history(portfolio_id: int, days: int = 90) -> dict:
    """获取组合盈亏历史"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("SELECT snapshot_date, total_value, daily_pnl FROM daily_portfolio_value WHERE portfolio_id=%s AND snapshot_date >= DATE_SUB(CURDATE(), INTERVAL %s DAY) ORDER BY snapshot_date LIMIT %s", (portfolio_id, min(days,365), MAX_PNL_ROWS))
    rows = cur.fetchall()
    cur.close(); conn.close()
    if not rows: return {"error": "暂无组合净值数据"}
    points = [{"date": str(r[0]), "value": round(float(r[1] or 0),2), "pnl": round(float(r[2] or 0),2)} for r in rows]
    return {"count": len(points), "points": points[:MAX_PNL_ROWS]}

def tool_get_transactions(portfolio_id: int, limit: int = 20) -> list:
    """获取近期交易记录"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("SELECT t.trade_date, s.symbol, s.name, t.type, t.shares, t.price, t.fee FROM transactions t JOIN stocks s ON t.stock_id=s.id WHERE t.portfolio_id=%s ORDER BY t.trade_date DESC, t.id DESC LIMIT %s", (portfolio_id, min(limit, 50)))
    rows = cur.fetchall()
    cur.close(); conn.close()
    return [{"date": str(r[0]), "symbol": r[1], "name": r[2], "type": r[3], "shares": int(float(r[4])), "price": round(float(r[5]),2), "fee": round(float(r[6] or 0),2)} for r in rows]

def tool_get_stock_price_history(symbol: str, days: int = 60) -> dict:
    """获取个股历史K线"""
    conn = get_db_conn()
    db_sym = resolve_symbol(conn, symbol)
    if not db_sym:
        conn.close()
        return {"error": f"未找到 {symbol}"}
    cur = conn.cursor()
    cur.execute("SELECT s.id FROM stocks s WHERE s.symbol=%s", (db_sym,))
    sid = cur.fetchone()[0]
    limit = min(days * 2, MAX_ROWS)
    cur.execute("SELECT trade_date, open, close, high, low, volume FROM stock_prices WHERE stock_id=%s ORDER BY trade_date DESC LIMIT %s", (sid, limit))
    rows = cur.fetchall()
    cur.close(); conn.close()
    points = [{"date": str(r[0]), "open": round(float(r[1] or 0),2), "close": round(float(r[2] or 0),2), "high": round(float(r[3] or 0),2), "low": round(float(r[4] or 0),2), "volume": int(float(r[5] or 0))} for r in rows]
    truncated = len(points) >= limit
    return {"symbol": symbol, "count": len(points), "points": list(reversed(points)), "truncated": truncated, "note": "数据已截断" if truncated else ""}

# B: Computational tools
def tool_compute_correlation(portfolio_id: int, symbols: list = None) -> dict:
    """计算持仓相关性矩阵"""
    import numpy as np
    conn = get_db_conn(); cur = conn.cursor()
    if not symbols:
        cur.execute("SELECT s.symbol FROM holdings h JOIN stocks s ON h.stock_id=s.id WHERE h.portfolio_id=%s AND h.total_shares>0", (portfolio_id,))
        symbols = [r[0] for r in cur.fetchall()]
    symbols = symbols[:MAX_CORR_STOCKS]
    if len(symbols) < 2: cur.close(); conn.close(); return {"error": "至少需要2只股票"}
    closes = {}
    for sym in symbols:
        db_sym = resolve_symbol(conn, sym)
        if not db_sym: continue
        cur.execute("SELECT s.id FROM stocks WHERE symbol=%s", (db_sym,))
        sid = cur.fetchone()[0]
        cur.execute("SELECT trade_date, close FROM stock_prices WHERE stock_id=%s ORDER BY trade_date DESC LIMIT %s", (sid, MAX_MULTI_ROWS))
        for d, c in cur.fetchall(): closes.setdefault(str(d), {})[sym] = float(c)
    cur.close(); conn.close()
    dates = sorted(closes.keys())
    pairs = []
    for i in range(len(symbols)):
        for j in range(i+1, len(symbols)):
            s1, s2 = symbols[i], symbols[j]
            vals1, vals2 = [], []
            for d in dates:
                dd = closes[d]
                if s1 in dd and s2 in dd: vals1.append(dd[s1]); vals2.append(dd[s2])
            if len(vals1) > 30:
                r = float(np.corrcoef(vals1, vals2)[0,1])
                pairs.append({"s1": s1, "s2": s2, "correlation": round(r, 3), "overlap": len(vals1)})
    return {"pairs": sorted(pairs, key=lambda x: -abs(x["correlation"])), "note": "r>0.7高度正相关，分散化效果弱"}

def tool_compute_sector_breakdown(portfolio_id: int) -> dict:
    """行业/市场分布"""
    sys.path.insert(0, str(SCRIPT_DIR))
    from portfolio_style_analyzer import classify_style
    conn = get_db_conn(); cur = conn.cursor()
    cur.execute("""
        SELECT h.stock_id, s.symbol, s.name, s.market, h.total_shares,
               (SELECT close FROM stock_prices WHERE stock_id=s.id ORDER BY trade_date DESC LIMIT 1) AS price
        FROM holdings h JOIN stocks s ON h.stock_id=s.id
        WHERE h.portfolio_id=%s AND h.total_shares>0
    """, (portfolio_id,))
    rows = cur.fetchall()
    if not rows:
        cur.close(); conn.close()
        return {"error": "无持仓"}
    id_list = ",".join(str(r[0]) for r in rows)
    cur.execute(f"SELECT stock_id, beta_1y, volatility_1y FROM stock_metric_cache WHERE stock_id IN ({id_list})")
    metric_map = {r[0]: (r[1], r[2]) for r in cur.fetchall()}
    cur.close(); conn.close()
    by_style, by_market, total = {}, {}, 0
    for r in rows:
        sid, sym, name, mkt, shares, price = r
        mv = float(shares) * float(price or 0)
        total += mv
        m = metric_map.get(sid, (None, None))
        beta = float(m[0]) if m[0] is not None else None
        vol = float(m[1]) if m[1] is not None else None
        style = classify_style(name or sym, mkt, beta, vol)
        by_style[style] = by_style.get(style, 0) + mv
        by_market[mkt] = by_market.get(mkt, 0) + mv
    for k in by_style: by_style[k] = round(by_style[k]/total*100, 1) if total else 0
    for k in by_market: by_market[k] = round(by_market[k]/total*100, 1) if total else 0
    top_style = max(by_style, key=by_style.get) if by_style else ""
    return {"byStyle": by_style, "byMarket": by_market, "concentrationRisk": f"{top_style}占比{by_style.get(top_style,0)}%，{'集中度偏高' if by_style.get(top_style,0)>50 else '分布合理'}"}

def tool_benchmark_compare(portfolio_id: int, benchmark: str = "000001.SH", days: int = 252) -> dict:
    """组合 vs 基准对比"""
    import numpy as np
    conn = get_db_conn(); cur = conn.cursor()
    # Portfolio daily values
    cur.execute("SELECT snapshot_date, total_value FROM daily_portfolio_value WHERE portfolio_id=%s AND snapshot_date>=DATE_SUB(CURDATE(),INTERVAL %s DAY) ORDER BY snapshot_date", (portfolio_id, min(days,500)))
    pv_rows = cur.fetchall()
    # Benchmark prices
    db_bm = resolve_symbol(conn, benchmark) or benchmark
    cur.execute("SELECT id FROM stocks WHERE symbol=%s", (db_bm,))
    bm = cur.fetchone()
    bm_close = {}
    if bm:
        cur.execute("SELECT trade_date, close FROM stock_prices WHERE stock_id=%s AND trade_date>=DATE_SUB(CURDATE(),INTERVAL %s DAY) ORDER BY trade_date", (bm[0], min(days,500)))
        bm_close = {str(d): float(c) for d, c in cur.fetchall()}
    cur.close(); conn.close()
    if len(pv_rows) < 30: return {"error": "组合净值数据不足（需至少30天）"}
    p_vals, b_vals = [], []
    for d, v in pv_rows:
        d = str(d)
        if d in bm_close: p_vals.append(float(v)); b_vals.append(bm_close[d])
    if len(p_vals) < 30: return {"error": "基准数据对齐不足"}
    p_vals, b_vals = np.array(p_vals), np.array(b_vals)
    p_ret = (p_vals[-1]/p_vals[0]-1)*100
    b_ret = (b_vals[-1]/b_vals[0]-1)*100
    excess = p_ret - b_ret
    p_daily = np.diff(p_vals)/p_vals[:-1]
    b_daily = np.diff(b_vals)/b_vals[:-1]
    corr = float(np.corrcoef(p_daily, b_daily)[0,1]) if len(p_daily)>30 else None
    te = float(np.std(p_daily - b_daily, ddof=1)*np.sqrt(252)*100) if len(p_daily)>30 else None
    return {"portfolioReturn": round(p_ret,2), "benchmarkReturn": round(b_ret,2), "excessReturn": round(excess,2), "correlation": round(corr,3) if corr else None, "trackingError": round(te,2) if te else None, "periodDays": len(p_vals), "note": "超额收益>0表示跑赢基准"}

def tool_analyze_backtest(backtest_id: int = None) -> dict:
    """获取最新回测结果的完整分析数据"""
    conn = get_db_conn(); cur = conn.cursor()
    if backtest_id:
        cur.execute("SELECT id, name, metrics_json, trade_log_json, equity_curve_json, start_date, end_date FROM backtest_results WHERE id=%s", (backtest_id,))
    else:
        cur.execute("SELECT id, name, metrics_json, trade_log_json, equity_curve_json, start_date, end_date FROM backtest_results ORDER BY id DESC LIMIT 1")
    row = cur.fetchone()
    if not row: cur.close(); conn.close(); return {"error": "无回测记录"}
    try: metrics = json.loads(row[2])
    except: metrics = {}
    try: trades = json.loads(row[3])
    except: trades = []
    try: curve = json.loads(row[4])
    except: curve = []
    cur.close(); conn.close()
    # Compute additional stats
    wins = [t for t in trades if t.get("pnl") and t["pnl"] > 0]
    losses = [t for t in trades if t.get("pnl") and t["pnl"] < 0]
    avg_win = round(sum(t["pnl"] for t in wins)/len(wins),2) if wins else 0
    avg_loss = round(sum(t["pnl"] for t in losses)/len(losses),2) if losses else 0
    avg_hold_days = None
    if len(trades) >= 2:
        buy_dates = {t["symbol"]: t["date"] for t in trades if t["action"] == "BUY"}
        hold_periods = []
        for t in trades:
            if t["action"] == "SELL" and t["symbol"] in buy_dates:
                from datetime import datetime
                try:
                    bd = datetime.strptime(buy_dates[t["symbol"]], "%Y-%m-%d")
                    sd = datetime.strptime(t["date"], "%Y-%m-%d")
                    hold_periods.append((sd-bd).days)
                except: pass
        if hold_periods: avg_hold_days = round(sum(hold_periods)/len(hold_periods), 1)
    return {
        "id": row[0], "name": row[1], "period": f"{row[5]} ~ {row[6]}",
        "metrics": metrics,
        "tradeStats": {"totalTrades": len(wins)+len(losses), "wins": len(wins), "losses": len(losses),
                        "avgWin": avg_win, "avgLoss": avg_loss, "avgHoldDays": avg_hold_days,
                        "totalReturn": metrics.get("totalReturnPct"), "sharpe": metrics.get("sharpeRatio"),
                        "maxDrawdown": metrics.get("maxDrawdownPct"), "winRate": metrics.get("winRatePct")},
        "equityPoints": len(curve),
    }

# ── Memory / Knowledge Base ──────────────────────────────────────────────

def tool_remember(user_id: int, fact: str) -> str:
    """用户主动要求记住的信息，持久化到知识库"""
    conn = get_db_conn(); cur = conn.cursor()
    cur.execute("INSERT INTO ai_chat_history (user_id, role, content) VALUES (%s, 'memory', %s)",
                 (user_id, fact[:2000]))
    conn.commit()
    # Keep max 50 memories per user
    cur.execute("DELETE FROM ai_chat_history WHERE user_id=%s AND role='memory' AND id NOT IN (SELECT id FROM (SELECT id FROM ai_chat_history WHERE user_id=%s AND role='memory' ORDER BY id DESC LIMIT 50) AS t)", (user_id, user_id))
    conn.commit()
    cur.close(); conn.close()
    return "已记住"

def load_memories(user_id: int) -> str:
    """加载用户主动保存的记忆，总量上限 3000 字符"""
    conn = get_db_conn(); cur = conn.cursor()
    cur.execute("SELECT content FROM ai_chat_history WHERE user_id=%s AND role='memory' ORDER BY id DESC LIMIT 50", (user_id,))
    rows = cur.fetchall()
    cur.close(); conn.close()
    if not rows: return ""
    lines, budget = [], 3000
    for (content,) in rows:
        line = f"- {content}"
        if sum(len(l) for l in lines) + len(line) > budget:
            break
        lines.append(line)
    return "用户保存的记忆：\n" + "\n".join(lines)

def tool_forget(user_id: int, keyword: str) -> str:
    """删除包含关键词的记忆"""
    conn = get_db_conn(); cur = conn.cursor()
    cur.execute("DELETE FROM ai_chat_history WHERE user_id=%s AND role='memory' AND content LIKE %s", (user_id, f"%{keyword}%"))
    deleted = cur.rowcount
    conn.commit(); cur.close(); conn.close()
    return f"已删除 {deleted} 条相关记忆"

def tool_web_search(query: str, count: int = 5) -> dict:
    """联网搜索（DuckDuckGo，免费无API key）"""
    try:
        try:
            from ddgs import DDGS
        except ImportError:
            from duckduckgo_search import DDGS
        results = []
        with DDGS() as ddgs:
            for r in ddgs.text(query, max_results=min(count, 8)):
                results.append({"title": r.get("title",""), "snippet": r.get("body","")[:300], "url": r.get("href","")})
        if not results:
            return {"query": query, "results": [], "note": "未找到相关结果"}
        return {"query": query, "results": results, "note": f"共{len(results)}条结果"}
    except ImportError:
        return {"error": "搜索模块未安装", "note": "pip3 install --break-system-packages ddgs"}
    except Exception as e:
        return {"error": f"搜索失败: {str(e)[:100]}"}

def tool_get_backtests(limit: int = 5) -> list:
    """获取最近的回测结果"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("SELECT id, name, strategy_type, start_date, end_date, metrics_json FROM backtest_results ORDER BY id DESC LIMIT %s", (limit,))
    rows = cur.fetchall()
    results = []
    for r in rows:
        try: metrics = json.loads(r[5]); total_ret = metrics.get("totalReturnPct"); sharpe = metrics.get("sharpeRatio")
        except: total_ret = None; sharpe = None
        results.append({"id": r[0], "name": r[1], "type": r[2], "start": str(r[3]), "end": str(r[4]),
                        "totalReturn": total_ret, "sharpe": sharpe})
    cur.close(); conn.close()
    return results


def tool_get_style_analysis(portfolio_id: int) -> dict:
    """运行组合风格诊断"""
    sys.path.insert(0, str(SCRIPT_DIR))
    try:
        from portfolio_style_analyzer import analyze_portfolio, load_config, get_conn
        cfg = load_config()
        conn = get_conn(cfg)
        try:
            return analyze_portfolio(conn, portfolio_id)
        finally:
            conn.close()
    except Exception as e:
        return {"error": f"风格分析失败: {str(e)[:200]}"}


def tool_list_strategies() -> list:
    """获取用户保存的策略列表"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("SELECT id, name, strategy_type, created_at FROM backtest_strategies ORDER BY id DESC")
    rows = cur.fetchall()
    cur.close(); conn.close()
    return [{"id": r[0], "name": r[1], "type": r[2], "created": str(r[3])} for r in rows]


def tool_get_strategy(strategy_id: int) -> dict:
    """获取单个策略的详情（含入场/离场规则、指标参数、逻辑组合）"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("SELECT id, name, strategy_type, strategy_json FROM backtest_strategies WHERE id=%s", (strategy_id,))
    r = cur.fetchone()
    cur.close(); conn.close()
    if not r: return {"error": "策略未找到"}

    result = {"id": r[0], "name": r[1], "type": r[2]}

    try:
        strat = json.loads(r[3])
        if r[2] == "advanced":
            result["mode"] = "advanced"
            code = strat.get("code", "")
            result["code"] = code[:1200]
        else:
            result["mode"] = "rule-based"
            entry = strat.get("entry", {})
            exit_r = strat.get("exit", {})

            # Format entry rules
            entry_logic = entry.get("logic", "all")
            entry_rules = entry.get("rules", [])
            result["entry_logic"] = "全部满足" if entry_logic == "all" else "任一满足"
            result["entry_rules"] = [_format_rule(rl) for rl in entry_rules]

            # Format exit rules
            exit_rules = exit_r.get("rules", [])
            result["exit_rules"] = [_format_rule(rl) for rl in exit_rules]
    except Exception:
        result["raw"] = r[3][:500]

    return result


def _format_rule(rule: dict) -> str:
    """将单条规则转为人类可读描述，例如 'SMA(20) 上穿，阈值 0'"""
    indicator = rule.get("indicator", "")
    params = rule.get("params", {})
    condition = rule.get("condition", "")
    threshold = rule.get("threshold", 0)

    # Indicator label mapping
    labels = {
        "sma": "SMA", "ema": "EMA", "rsi": "RSI", "macd_histogram": "MACD柱",
        "bollinger_lower": "布林下轨", "volume_ma": "成交量MA", "kdj_k": "KDJ-K",
        "stop_loss": "止损", "take_profit": "止盈", "trailing_stop": "移动止损",
    }
    cond_labels = {
        "above": ">", "below": "<", "oversold": "超卖", "overbought": "超买",
        "triggered": "触发",
    }

    name = labels.get(indicator, indicator)
    cond = cond_labels.get(condition, condition)

    # Format params
    param_strs = []
    for k, v in params.items():
        if k == "period": param_strs.append(f"周期{v}")
        elif k == "fast": param_strs.append(f"快线{v}")
        elif k == "slow": param_strs.append(f"慢线{v}")
        elif k == "pct": param_strs.append(f"{v}%")
        else: param_strs.append(f"{k}={v}")

    base = f"{name}"
    if param_strs: base += f"({', '.join(param_strs)})"
    base += f" {cond}"
    if threshold != 0: base += f" {threshold}"
    return base


# ── Fundamentals tool ────────────────────────────────────────────────────

def tool_get_fundamentals(symbol: str) -> dict:
    """获取单只股票的基本面数据：PE、PB、ROE、EPS、市值、行业。"""
    conn = get_db_conn()
    db_sym = resolve_symbol(conn, symbol)
    if not db_sym:
        conn.close()
        return {"error": "股票未找到", "symbol": symbol}
    cur = conn.cursor()
    cur.execute("SELECT id FROM stocks WHERE symbol=%s", (db_sym,))
    row = cur.fetchone()
    if not row:
        cur.close(); conn.close()
        return {"error": "股票未找到", "symbol": symbol}

    stock_id = row[0]
    cur.execute("""
        SELECT pe_ttm, pb, roe, eps_ttm, rev_growth, earnings_growth,
               debt_ratio, market_cap, sector, industry, div_yield, updated_at
        FROM stock_fundamentals WHERE stock_id=%s
    """, (stock_id,))
    r = cur.fetchone()
    cur.close(); conn.close()

    if not r:
        return {"symbol": symbol, "note": "暂无基本面数据"}

    return {
        "symbol": symbol,
        "pe_ttm": float(r[0]) if r[0] else None,
        "pb": float(r[1]) if r[1] else None,
        "roe": float(r[2]) if r[2] else None,
        "eps_ttm": float(r[3]) if r[3] else None,
        "rev_growth": float(r[4]) if r[4] else None,
        "earnings_growth": float(r[5]) if r[5] else None,
        "debt_ratio": float(r[6]) if r[6] else None,
        "market_cap": float(r[7]) if r[7] else None,
        "sector": r[8], "industry": r[9],
        "div_yield": float(r[10]) if r[10] else None,
        "updated_at": str(r[11]) if r[11] else None,
    }


# ── Portfolio optimization tool ─────────────────────────────────────────

def tool_optimize_portfolio(portfolio_id: int, max_weight: float = 0.30, mode: str = "sharpe") -> dict:
    """运行 Markowitz 均值-方差优化，返回建议权重与当前持仓对比。mode: sharpe|minvar|riskparity"""
    sys.path.insert(0, str(SCRIPT_DIR))
    try:
        from optimizer import analyze
        return analyze(portfolio_id, max_weight, mode)
    except Exception as e:
        return {"error": str(e)}


# ── Tool definitions (OpenAI format) ────────────────────────────────────

# ── 全球市场工具 ──────────────────────────────────────────────────────────

_GLOBAL_INDICES = [
    ("000001.SH",  "上证指数",   "CN"), ("399001.SZ",  "深证成指",   "CN"),
    ("399006.SZ",  "创业板指",   "CN"),
    ("HSI.HK",     "恒生指数",   "HK"), ("HSCE.HK",    "国企指数",   "HK"),
    ("HSTECH.HK",  "恒生科技",   "HK"),
    ("GSPC.US",    "标普500",    "US"), ("DJI.US",     "道琼斯工业", "US"),
    ("IXIC.US",    "纳斯达克综合","US"),
    ("N225.JP",    "日经225",    "JP"), ("KS11.KR",    "韩国KOSPI",  "KR"),
    ("FTSE.GB",    "富时100",    "GB"), ("GDAXI.DE",   "德国DAX",    "DE"),
    ("FCHI.FR",    "法国CAC40",  "FR"), ("TWII.TW",    "台湾加权",   "TW"),
    ("STI.SG",     "新加坡STI",  "SG"), ("BSESN.IN",   "印度SENSEX", "IN"),
    ("AXJO.AU",    "澳洲ASX200", "AU"), ("GSPTSE.CA",  "加拿大TSX",  "CA"),
    ("BVSP.BR",    "巴西Bovespa","BR"),
    ("DXY.IDX",    "美元指数",   "IDX"),("XAU.CMD",    "黄金/美元",  "CMD"),
    ("BTC.CCY",    "比特币/美元","CCY"),("CL.CMD",     "WTI原油",    "CMD"),
]

def tool_get_global_indices() -> dict:
    conn = get_db_conn()
    cur = conn.cursor()
    results = []
    for symbol, name, country in _GLOBAL_INDICES:
        cur.execute("""
            SELECT sp.close, sp.trade_date
            FROM stock_prices sp JOIN stocks s ON s.id = sp.stock_id
            WHERE s.symbol = %s ORDER BY sp.trade_date DESC LIMIT 2
        """, (symbol,))
        rows = cur.fetchall()
        if len(rows) >= 2:
            price = float(rows[0][0]); prev = float(rows[1][0])
            chg = price - prev; chg_pct = (chg / prev) * 100 if prev else 0
            results.append({"name": name, "country": country,
                "price": round(price, 2), "change": round(chg, 2),
                "changePct": round(chg_pct, 2), "date": str(rows[0][1])})
    cur.close(); conn.close()
    return {"indices": results, "note": f"共{len(results)}个指数"}

def tool_get_world_news(limit: int = 10) -> dict:
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("""
        SELECT title, source, category, summary, url, country_code
        FROM world_news WHERE fetched_date = CURDATE()
        ORDER BY score DESC LIMIT %s
    """, (min(limit, 20),))
    rows = cur.fetchall()
    news = [{"title": r[0], "source": r[1], "category": r[2],
        "summary": r[3], "url": r[4], "countryCode": r[5]} for r in rows]
    cur.close(); conn.close()
    return {"news": news, "count": len(news),
        "note": f"今日共{len(news)}条要闻" if news else "今日暂无新闻"}


TOOLS = [
    {"type": "function", "function": {
        "name": "get_portfolio", "description": "获取当前持仓组合的完整数据：每只标的的名称、代码、市值、盈亏比例、权重。用户问持仓相关问题时必须先调用此工具。",
        "parameters": {"type": "object", "properties": {}, "required": []}
    }},
    {"type": "function", "function": {
        "name": "get_stock_metrics", "description": "获取某只股票的量化指标：5年分位数、Beta、年化波动率、最大回撤",
        "parameters": {"type": "object", "properties": {
            "symbol": {"type": "string", "description": "股票代码，如 600519.SH"}
        }, "required": ["symbol"]}
    }},
    {"type": "function", "function": {
        "name": "get_style_analysis", "description": "运行完整的组合风格诊断，返回风格配置、行业偏好、风险特征和优化建议",
        "parameters": {"type": "object", "properties": {}, "required": []}
    }},
    {"type": "function", "function": {
        "name": "get_backtests", "description": "获取最近的历史回测记录和结果指标",
        "parameters": {"type": "object", "properties": {
            "limit": {"type": "integer", "description": "返回条数，默认5"}
        }, "required": []}
    }},
    {"type": "function", "function": {
        "name": "list_strategies", "description": "获取用户已保存的策略列表",
        "parameters": {"type": "object", "properties": {}, "required": []}
    }},
    {"type": "function", "function": {
        "name": "get_strategy", "description": "获取策略的完整规则详情（入场条件、离场条件、指标参数、逻辑组合、仓位管理）。用户要求评价或分析某个策略时必须调用此工具。",
        "parameters": {"type": "object", "properties": {
            "id": {"type": "integer", "description": "策略ID，从 list_strategies 获取"}
        }, "required": ["id"]}
    }},
    {"type": "function", "function": {
        "name": "generate_strategy", "description": "生成策略。description用自然语言总结（不含代码）。code必须严格遵守：只包含def decide(ctx):一个函数，接收ctx字典(键:symbol date open high low close volume has_position shares avg_cost cash total_equity)，返回{'action':'BUY'|'SELL'|'HOLD','quantity':int}。只能import numpy和math。不超过60行。禁止pandas/DataFrame/Series/context变量/get_all_securities/聚宽/米筐/jqdata。禁止在消息正文输出代码",
        "parameters": {"type": "object", "properties": {
            "name": {"type": "string"}, "description": {"type": "string"}, "code": {"type": "string"}
        }, "required": ["name", "description", "code"]}
    }},
    # A: Data tools
    {"type": "function", "function": {
        "name": "get_stock_price", "description": "查询某只股票的当前价格和今日涨跌",
        "parameters": {"type": "object", "properties": {"symbol": {"type": "string"}}, "required": ["symbol"]}
    }},
    {"type": "function", "function": {
        "name": "get_pnl_history", "description": "获取组合每日净值走势和盈亏",
        "parameters": {"type": "object", "properties": {"days": {"type": "integer", "description": "天数，默认90"}}, "required": []}
    }},
    {"type": "function", "function": {
        "name": "get_transactions", "description": "获取近期交易记录",
        "parameters": {"type": "object", "properties": {"limit": {"type": "integer"}}, "required": []}
    }},
    {"type": "function", "function": {
        "name": "get_stock_price_history", "description": "获取个股历史K线（OHLCV）",
        "parameters": {"type": "object", "properties": {"symbol": {"type": "string"}, "days": {"type": "integer", "description": "天数，默认60"}}, "required": ["symbol"]}
    }},
    # B: Compute tools
    {"type": "function", "function": {
        "name": "compute_correlation", "description": "计算持仓股票间的价格相关性矩阵",
        "parameters": {"type": "object", "properties": {"symbols": {"type": "array", "items": {"type": "string"}}}, "required": []}
    }},
    {"type": "function", "function": {
        "name": "compute_sector_breakdown", "description": "分析持仓的行业/市场分布和集中度",
        "parameters": {"type": "object", "properties": {}, "required": []}
    }},
    {"type": "function", "function": {
        "name": "benchmark_compare", "description": "对比组合与基准指数的表现差异",
        "parameters": {"type": "object", "properties": {"benchmark": {"type": "string", "description": "基准代码，默认000001.SH（上证）"}, "days": {"type": "integer"}}, "required": []}
    }},
    {"type": "function", "function": {
        "name": "analyze_backtest", "description": "获取最新回测结果并给出全面客观的评价，覆盖收益、风险、稳定性、改进方向",
        "parameters": {"type": "object", "properties": {"id": {"type": "integer", "description": "回测结果ID，不传则取最新一次"}}, "required": []}
    }},
    {"type": "function", "function": {
        "name": "ask_user", "description": "需要用户做选择时调用。例如：多个回测结果选哪个、多个股票选哪个、确认是否执行操作。提供2-4个选项让用户选",
        "parameters": {"type": "object", "properties": {
            "question": {"type": "string", "description": "问用户的问题"},
            "options": {"type": "array", "items": {"type": "string"}, "description": "选项列表，2-4个"}
        }, "required": ["question", "options"]}
    }},
    {"type": "function", "function": {
        "name": "remember", "description": "用户要求记住某个信息（偏好、事实、背景等），保存到长期记忆。用户说'记住'、'别忘了'、'帮我记一下'时调用",
        "parameters": {"type": "object", "properties": {"fact": {"type": "string", "description": "要记住的内容"}}, "required": ["fact"]}
    }},
    {"type": "function", "function": {
        "name": "forget", "description": "删除包含关键词的长期记忆。用户说'忘掉'、'删除记忆'、'不要记了'时调用",
        "parameters": {"type": "object", "properties": {"keyword": {"type": "string", "description": "要删除的记忆关键词"}}, "required": ["keyword"]}
    }},
    {"type": "function", "function": {
        "name": "web_search", "description": "联网搜索。凡涉及新闻、时事、最新动态、具体事件日期和细节——你无法从数据库回答的一切——必须先调用此工具再回复，禁止凭记忆编造",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string", "description": "搜索关键词"},
            "count": {"type": "integer", "description": "返回条数，默认5，最多8"}
        }, "required": ["query"]}
    }},
    {"type": "function", "function": {
        "name": "get_fundamentals", "description": "获取单只股票的基本面数据：PE、PB、ROE、EPS(TTM)、营收增速、市值、行业。用户问估值或财务面时必须调用。",
        "parameters": {"type": "object", "properties": {
            "symbol": {"type": "string", "description": "DB格式symbol，例如1.600519"}
        }, "required": ["symbol"]}
    }},
    {"type": "function", "function": {
        "name": "optimize_portfolio", "description": "Markowitz均值-方差组合优化。给出当前持仓的建议权重分配（最大化夏普/最小方差/风险平价），以及与当前权重的对比。用户要求调仓建议时调用。",
        "parameters": {"type": "object", "properties": {
            "portfolio_id": {"type": "integer", "description": "组合ID"},
            "max_weight": {"type": "number", "description": "单票最大权重，默认0.30"},
            "mode": {"type": "string", "description": "优化模式: sharpe(默认), minvar, riskparity"}
        }, "required": ["portfolio_id"]}
    }},
    {"type": "function", "function": {
        "name": "get_global_indices", "description": "获取全球 20 个股市指数 + 4 个商品/汇率指标的最新行情。问全球/世界/大盘走势/市场概况时调用。",
        "parameters": {"type": "object", "properties": {}}
    }},
    {"type": "function", "function": {
        "name": "get_world_news", "description": "获取今日全球财经/地缘要闻。问最新新闻、时事、今日大事时调用。",
        "parameters": {"type": "object", "properties": {
            "limit": {"type": "integer", "description": "返回条数，默认10，最多20"}
        }}
    }},
]

TOOL_LABELS = {
    "get_portfolio": "读取持仓",
    "get_stock_metrics": "查询量化指标",
    "get_backtests": "获取回测记录",
    "get_style_analysis": "分析组合风格",
    "list_strategies": "获取策略列表",
    "get_strategy": "读取策略详情",
    "generate_strategy": "生成策略",
    "get_stock_price": "查询股价",
    "get_pnl_history": "获取组合走势",
    "get_transactions": "获取交易记录",
    "get_stock_price_history": "加载K线数据",
    "compute_correlation": "计算相关性",
    "compute_sector_breakdown": "分析行业分布",
    "benchmark_compare": "对比基准",
    "analyze_backtest": "分析回测",
    "web_search": "联网搜索",
    "get_fundamentals": "查询基本面",
    "optimize_portfolio": "组合优化",
    "get_global_indices": "获取全球指数",
    "get_world_news": "获取全球要闻",
    "remember": "保存记忆",
    "forget": "删除记忆",
    "ask_user": "",
}

def _trim_result(name: str, result: object) -> object:
    """Strip data the LLM doesn't need to keep tool results compact."""
    if not isinstance(result, dict):
        return result
    if name == "get_style_analysis":
        # holdings detail is redundant — get_portfolio already covers it
        return {k: v for k, v in result.items() if k != "holdings"}
    if name == "get_pnl_history":
        points = result.get("points", [])
        if len(points) > 30:
            first, last = points[0], points[-1]
            total_ret = round((last["value"] / first["value"] - 1) * 100, 2) if first.get("value") else 0
            return {
                **{k: v for k, v in result.items() if k != "points"},
                "summary": {
                    "firstDate": first["date"], "firstValue": first["value"],
                    "lastDate": last["date"], "lastValue": last["value"],
                    "totalReturn": total_ret,
                    "maxValue": max(p["value"] for p in points),
                    "minValue": min(p["value"] for p in points),
                },
                "recentPoints": points[-30:],
                "trimmed": True,
            }
    if name == "get_stock_price_history":
        points = result.get("points", [])
        if len(points) > 30:
            return {
                **{k: v for k, v in result.items() if k != "points"},
                "summary": {
                    "startDate": points[0]["date"], "startClose": points[0]["close"],
                    "endDate": points[-1]["date"], "endClose": points[-1]["close"],
                    "periodHigh": max(p["high"] for p in points),
                    "periodLow": min(p["low"] for p in points),
                    "totalReturn": round((points[-1]["close"] / points[0]["close"] - 1) * 100, 2) if points[0]["close"] else 0,
                    "avgVolume": int(sum(p["volume"] for p in points) / len(points)),
                },
                "recentPoints": points[-30:],
                "trimmed": True,
            }
    return result


def _run_tool(name: str, args: dict, portfolio_id: int, user_id: int) -> object:
    """Inner dispatcher — returns Python object, raises on failure."""
    if name == "remember":
        return {"status": tool_remember(user_id, args.get("fact", ""))}
    elif name == "forget":
        return {"status": tool_forget(user_id, args.get("keyword", ""))}
    elif name == "ask_user":
        q = {"question": args.get("question", ""), "options": args.get("options", [])}
        print(f"[ASK] {json.dumps(q, ensure_ascii=False)}", flush=True)
        return {"answered": "已向用户展示选项，等待选择"}
    elif name == "get_portfolio":
        return tool_get_portfolio(portfolio_id)
    elif name == "get_stock_metrics":
        return tool_get_stock_metrics(args.get("symbol", ""))
    elif name == "get_backtests":
        return tool_get_backtests(args.get("limit", 5))
    elif name == "get_style_analysis":
        return tool_get_style_analysis(portfolio_id)
    elif name == "list_strategies":
        return tool_list_strategies()
    elif name == "get_strategy":
        return tool_get_strategy(args.get("id", 0))
    elif name == "generate_strategy":
        code = args.get("code", "")
        if "def decide(ctx)" not in code:
            code = "# 格式错误\ndef decide(ctx):\n    return {'action': 'HOLD', 'quantity': 0}"
        if "pandas" in code or "DataFrame" in code or "get_all_securities" in code:
            code = "# 检测到禁用API\ndef decide(ctx):\n    return {'action': 'HOLD', 'quantity': 0}"
        result = {"name": args.get("name", ""), "description": args.get("description", ""), "code": code}
        print(f"[STRATEGY] {json.dumps(result, ensure_ascii=False)}", flush=True)
        return result
    elif name == "get_stock_price":
        return tool_get_stock_price(args.get("symbol", ""))
    elif name == "get_pnl_history":
        return tool_get_pnl_history(portfolio_id, args.get("days", 90))
    elif name == "get_transactions":
        return tool_get_transactions(portfolio_id, args.get("limit", 20))
    elif name == "get_stock_price_history":
        return tool_get_stock_price_history(args.get("symbol", ""), args.get("days", 60))
    elif name == "compute_correlation":
        return tool_compute_correlation(portfolio_id, args.get("symbols"))
    elif name == "compute_sector_breakdown":
        return tool_compute_sector_breakdown(portfolio_id)
    elif name == "benchmark_compare":
        return tool_benchmark_compare(portfolio_id, args.get("benchmark", "000001.SH"), args.get("days", 252))
    elif name == "analyze_backtest":
        return tool_analyze_backtest(args.get("id"))
    elif name == "web_search":
        return tool_web_search(args.get("query", ""), args.get("count", 5))
    elif name == "get_fundamentals":
        return tool_get_fundamentals(args.get("symbol", ""))
    elif name == "optimize_portfolio":
        return tool_optimize_portfolio(
            args.get("portfolio_id", portfolio_id),
            float(args.get("max_weight", 0.30)),
            args.get("mode", "sharpe"),
        )
    elif name == "get_global_indices":
        return tool_get_global_indices()
    elif name == "get_world_news":
        return tool_get_world_news(args.get("limit", 10))
    return {"error": f"unknown tool: {name}"}


def execute_tool(name: str, args: dict, portfolio_id: int, user_id: int = 0) -> str:
    label = TOOL_LABELS.get(name, f"调用 {name}")
    if label:
        print(f"[TOOL] {label}", flush=True)
    try:
        result = _run_tool(name, args, portfolio_id, user_id)
        result = _trim_result(name, result)
        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        err_msg = f"{name} 失败: {str(e)[:300]}"
        print(f"[TOOL_ERR] {err_msg}", file=sys.stderr, flush=True)
        return json.dumps({"error": err_msg}, ensure_ascii=False)


# ── OpenAI-compatible streaming with function calling ────────────────────

def get_proxy():
    """Load proxy URL from config.ini"""
    import configparser
    cfg = configparser.ConfigParser()
    cfg_file = SCRIPT_DIR / "config.ini"
    if cfg_file.exists(): cfg.read(cfg_file, encoding="utf-8")
    try: return cfg.get("proxy", "url", fallback="").strip()
    except: return ""


def call_openai_with_tools(api_key: str, model: str, messages: list, api_base: str, portfolio_id: int, deep_think: bool = False, user_id: int = 0):
    from openai import OpenAI
    import httpx
    kwargs = {"api_key": api_key}
    if api_base: kwargs["base_url"] = api_base
    # Proxy support for overseas API access
    proxy_url = os.getenv("PROXY_URL", get_proxy())
    if proxy_url:
        kwargs["http_client"] = httpx.Client(proxy=proxy_url)
    client = OpenAI(**kwargs)
    max_tokens = 4096 if deep_think else 1024

    # DashScope / Qwen3: disable thinking mode by default to eliminate hidden
    # chain-of-thought overhead before the first token.  Deep-think mode re-enables
    # it so the model can use its native reasoning chain.
    is_dashscope = bool(api_base and ("dashscope" in api_base or "aliyuncs" in api_base))
    extra_body = {"enable_thinking": True} if (is_dashscope and deep_think) else {}

    # Route simple queries to the fast model to minimise TTFT; complex analysis
    # stays on the configured full model.  Deep-think always uses the full model.
    effective_model = model
    if is_dashscope and not deep_think and not _is_complex_query(messages):
        effective_model = DASHSCOPE_FAST_MODEL

    def _stream(msgs):
        return client.chat.completions.create(
            model=effective_model, messages=msgs, tools=TOOLS,
            stream=True, temperature=0.7, max_tokens=max_tokens,
            **({"extra_body": extra_body} if extra_body else {}),
        )

    formatted = []
    for m in messages:
        role = m.get("role", "user")
        if role not in ("system", "user", "assistant", "tool"): role = "user"
        content = m.get("content", "")
        # DashScope supports cache_control on system blocks (same format as Anthropic).
        # Wrapping the system prompt reduces TTFT on subsequent turns by avoiding
        # re-encoding the ~500-token persona + KB block on every request.
        if role == "system" and is_dashscope and isinstance(content, str):
            entry = {"role": role, "content": [{"type": "text", "text": content, "cache_control": {"type": "ephemeral"}}]}
        else:
            entry = {"role": role, "content": content}
        if "tool_calls" in m: entry["tool_calls"] = m["tool_calls"]
        if "tool_call_id" in m: entry["tool_call_id"] = m["tool_call_id"]
        formatted.append(entry)

    # Always stream first. If tool calls appear mid-stream, collect and handle.
    stream = _stream(formatted)

    tool_calls = {}  # idx -> {id, name, args}
    has_tools = False
    for chunk in stream:
        if not chunk.choices: continue
        delta = chunk.choices[0].delta
        if delta.tool_calls:
            has_tools = True
            for tc in delta.tool_calls:
                idx = tc.index
                if idx not in tool_calls:
                    tool_calls[idx] = {"id": "", "name": "", "args": ""}
                if tc.id: tool_calls[idx]["id"] = tc.id
                if tc.function:
                    if tc.function.name: tool_calls[idx]["name"] += tc.function.name
                    if tc.function.arguments: tool_calls[idx]["args"] += tc.function.arguments
        elif delta.content:
            sys.stdout.write(delta.content + "\n"); sys.stdout.flush()

    total_tool_calls = 0
    while has_tools:
        sorted_tools = [tool_calls[i] for i in sorted(tool_calls)]
        formatted.append({"role": "assistant", "content": None, "tool_calls": [
            {"id": t["id"], "type": "function", "function": {"name": t["name"], "arguments": t["args"]}}
            for t in sorted_tools
        ]})

        # Short-circuit: ask_user requires immediate return before any parallel work
        ask_tool = next((t for t in sorted_tools if t["name"] == "ask_user"), None)
        if ask_tool:
            try: ask_args = json.loads(ask_tool["args"])
            except: ask_args = {}
            execute_tool(ask_tool["name"], ask_args, portfolio_id, user_id)
            formatted.append({"role": "tool", "tool_call_id": ask_tool["id"],
                               "content": json.dumps({"answered": "已向用户展示选项，等待选择"})})
            print("\n[DONE]", flush=True); return

        # Split tools: those within limit run in parallel, excess get error
        runnable, capped = [], []
        for t in sorted_tools:
            if len(runnable) + total_tool_calls < MAX_TOOL_CALLS:
                runnable.append(t)
            else:
                capped.append(t)
        for t in capped:
            formatted.append({"role": "tool", "tool_call_id": t["id"],
                               "content": json.dumps({"error": "已达到本轮对话最大工具调用次数"})})

        # Execute runnable tools in parallel, collect results in original order
        if runnable:
            results_map = {}
            with concurrent.futures.ThreadPoolExecutor(max_workers=len(runnable)) as executor:
                futs = {}
                for t in runnable:
                    try: args = json.loads(t["args"])
                    except: args = {}
                    futs[executor.submit(execute_tool, t["name"], args, portfolio_id, user_id)] = t["id"]
                for fut, tid in futs.items():
                    try:
                        results_map[tid] = fut.result(timeout=25)
                    except concurrent.futures.TimeoutError:
                        results_map[tid] = json.dumps({"error": "工具执行超时"}, ensure_ascii=False)
            for t in runnable:
                formatted.append({"role": "tool", "tool_call_id": t["id"], "content": results_map[t["id"]]})
            total_tool_calls += len(runnable)

        # Call again — may produce more tool calls or final content
        stream = _stream(formatted)
        tool_calls = {}
        has_tools = False
        for chunk in stream:
            if not chunk.choices: continue
            delta = chunk.choices[0].delta
            if delta.tool_calls:
                has_tools = True
                for tc in delta.tool_calls:
                    idx = tc.index
                    if idx not in tool_calls:
                        tool_calls[idx] = {"id": "", "name": "", "args": ""}
                    if tc.id: tool_calls[idx]["id"] = tc.id
                    if tc.function:
                        if tc.function.name: tool_calls[idx]["name"] += tc.function.name
                        if tc.function.arguments: tool_calls[idx]["args"] += tc.function.arguments
            elif delta.content:
                sys.stdout.write(delta.content + "\n"); sys.stdout.flush()

    print("\n[DONE]", flush=True)


def call_anthropic_stream(api_key: str, model: str, messages: list, portfolio_id: int = 0, user_id: int = 0):
    import anthropic, httpx
    client_kwargs = {"api_key": api_key}
    proxy_url = os.getenv("PROXY_URL", get_proxy())
    if proxy_url:
        client_kwargs["http_client"] = httpx.Client(proxy=proxy_url)
    client = anthropic.Anthropic(**client_kwargs)

    system_prompt = None
    formatted = []
    for m in messages:
        role = m.get("role", "user")
        if role == "system":
            system_prompt = m.get("content", "")
            continue
        if role not in ("user", "assistant"):
            role = "user"
        formatted.append({"role": role, "content": m.get("content", "")})

    # Convert OpenAI tool format → Anthropic format
    anthropic_tools = [
        {"name": t["function"]["name"], "description": t["function"]["description"],
         "input_schema": t["function"]["parameters"]}
        for t in TOOLS
    ]

    # Prompt Caching: mark system prompt as ephemeral to cache it across requests
    system_block = ([{"type": "text", "text": system_prompt, "cache_control": {"type": "ephemeral"}}]
                    if system_prompt else None)

    total_tool_calls = 0

    while True:
        stream_kwargs = {"model": model, "messages": formatted, "max_tokens": 1024, "tools": anthropic_tools}
        if system_block:
            stream_kwargs["system"] = system_block

        with client.messages.stream(**stream_kwargs) as stream:
            for text in stream.text_stream:
                sys.stdout.write(text + "\n")
                sys.stdout.flush()
            msg = stream.get_final_message()

        if msg.stop_reason != "tool_use":
            break

        tool_uses = [c for c in msg.content if c.type == "tool_use"]

        # Append full assistant turn (text + tool_use blocks)
        formatted.append({
            "role": "assistant",
            "content": [
                {"type": "text", "text": c.text} if c.type == "text"
                else {"type": "tool_use", "id": c.id, "name": c.name, "input": c.input}
                for c in msg.content
            ],
        })

        # Short-circuit: ask_user returns immediately
        ask_tool = next((tu for tu in tool_uses if tu.name == "ask_user"), None)
        if ask_tool:
            execute_tool(ask_tool.name, ask_tool.input, portfolio_id, user_id)
            formatted.append({"role": "user", "content": [{
                "type": "tool_result", "tool_use_id": ask_tool.id,
                "content": json.dumps({"answered": "已向用户展示选项，等待选择"}),
            }]})
            print("\n[DONE]", flush=True)
            return

        # Split runnable vs capped by MAX_TOOL_CALLS
        runnable, capped = [], []
        for tu in tool_uses:
            if len(runnable) + total_tool_calls < MAX_TOOL_CALLS:
                runnable.append(tu)
            else:
                capped.append(tu)

        results_map = {}
        for tu in capped:
            results_map[tu.id] = json.dumps({"error": "已达到本轮对话最大工具调用次数"})

        if runnable:
            with concurrent.futures.ThreadPoolExecutor(max_workers=len(runnable)) as executor:
                futs = {executor.submit(execute_tool, tu.name, tu.input, portfolio_id, user_id): tu.id
                        for tu in runnable}
                for fut, tid in futs.items():
                    try:
                        results_map[tid] = fut.result(timeout=25)
                    except concurrent.futures.TimeoutError:
                        results_map[tid] = json.dumps({"error": "工具执行超时"}, ensure_ascii=False)
            total_tool_calls += len(runnable)

        # Append tool results in original order
        formatted.append({"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": tu.id, "content": results_map[tu.id]}
            for tu in tool_uses
        ]})

    print("\n[DONE]", flush=True)


# ── Main ─────────────────────────────────────────────────────────────────

def generate_suggestions(api_key: str, model: str, api_base: str):
    """Call LLM once (non-streaming) to return 3 varied investment question suggestions."""
    import re
    from openai import OpenAI
    import httpx
    kwargs = {"api_key": api_key}
    if api_base: kwargs["base_url"] = api_base
    proxy_url = os.getenv("PROXY_URL", get_proxy())
    if proxy_url:
        kwargs["http_client"] = httpx.Client(proxy=proxy_url)
    client = OpenAI(**kwargs)
    resp = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content":
            "生成3条风格各异的中文投资问题，用于引导用户使用AI助手。"
            "分别覆盖：①组合/持仓分析 ②个股深度 ③量化策略。"
            "每条不超过16个字，要简练有吸引力。"
            "只返回JSON数组，格式：[\"问题1\",\"问题2\",\"问题3\"]"}],
        max_tokens=120, temperature=1.1,
    )
    text = resp.choices[0].message.content.strip()
    m = re.search(r'\[.*?\]', text, re.DOTALL)
    if m:
        suggestions = json.loads(m.group())
        print(json.dumps(suggestions[:3], ensure_ascii=False), flush=True)
    else:
        print(json.dumps(["我的组合风险怎么样？", "分析一下我的持仓风格", "帮我写一个均线策略"]), flush=True)


def main():
    parser = argparse.ArgumentParser(description="Investory 观澜 AI Agent")
    parser.add_argument("--mode", default="chat", choices=["chat", "suggestions"])
    parser.add_argument("--provider", default="openai", choices=["openai", "anthropic", "openai_compat"])
    parser.add_argument("--model", default="gpt-4o-mini")
    parser.add_argument("--api-key", default=os.environ.get("AI_API_KEY", ""))
    parser.add_argument("--api-base", default="")
    parser.add_argument("--deep-think", action="store_true")
    parser.add_argument("--portfolio-id", type=int, default=0)
    parser.add_argument("--user-id", type=int, default=0)
    parser.add_argument("--input", default=None)
    args = parser.parse_args()

    if args.mode == "suggestions":
        generate_suggestions(args.api_key, args.model, args.api_base)
        return

    if not args.input:
        print("[ERROR] --input required for chat mode", flush=True); sys.exit(1)
    with open(args.input, "r", encoding="utf-8") as f:
        input_data = json.load(f)
    messages = input_data.get("messages", [])
    if not messages: print("[ERROR] 对话消息为空", flush=True); sys.exit(1)

    kb = load_knowledge_base()
    system_prompt = build_system_prompt(kb)
    # Inject user's saved memories as context
    if args.user_id > 0:
        memories = load_memories(args.user_id)
        if memories:
            system_prompt += "\n\n" + memories
    if args.deep_think:
        system_prompt += "\n\n深度思考模式。把推理过程放在<thinking>...</thinking>标签内（这部分前端会折叠，用户点开才看），最终结论放在标签外面直接显示。结论简洁，3-5句。如果要求写策略代码：Investory格式def decide(ctx)函数，只用numpy，禁止pandas/聚宽/米筐API。"
    full_messages = [{"role": "system", "content": system_prompt}] + messages

    try:
        if args.provider == "anthropic":
            call_anthropic_stream(args.api_key, args.model, full_messages, args.portfolio_id, args.user_id)
        else:
            call_openai_with_tools(args.api_key, args.model, full_messages, args.api_base, args.portfolio_id, args.deep_think, args.user_id)
    except Exception as e:
        msg = str(e)
        if "401" in msg or "Unauthorized" in msg or "Authentication" in msg:
            print(f"[ERROR] API Key 无效或未授权", flush=True)
        elif "timeout" in msg.lower() or "timed out" in msg.lower():
            print(f"[ERROR] 请求超时", flush=True)
        elif "connection" in msg.lower() or "ConnectError" in msg:
            print(f"[ERROR] 无法连接 API 服务", flush=True)
        elif "Rate" in msg or "429" in msg:
            print(f"[ERROR] API 调用频率超限", flush=True)
        elif "Insufficient" in msg or "quota" in msg.lower():
            print(f"[ERROR] API 额度不足", flush=True)
        else:
            print(f"[ERROR] 请求失败: {msg[:200]}", flush=True)
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__": main()
