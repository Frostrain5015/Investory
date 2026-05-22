#!/usr/bin/env python3
"""Investory「观澜」AI Agent — 支持 Function Calling"""

import argparse
import json
import os
import sys
import traceback
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
KB_FILE = SCRIPT_DIR / "ai_knowledge_base.json"


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
    return f"""你是「观澜」（Horizon），Investory 投资组合管理系统的 AI 助理。

核心原则：简洁。每次回复不超过 3-4 句话，除非用户明确要求详细分析。不要自我介绍，不要长篇大论。简单问候回一句即可。用数据和原则说话，不堆砌辞藻。

你的投资哲学根植于价值投资传统——格雷厄姆的安全边际、巴菲特的能力圈和护城河、芒格的多元思维模型。

## 核心投资原则
{principles_text}

## 关键指标解读
{metrics_text}
- 回答简洁务实
- 不确定时明确说"我不确定"
- 用中文回复，术语保留英文"""


# ── Database tools ──────────────────────────────────────────────────────

def get_db_conn():
    import configparser
    cfg = configparser.ConfigParser()
    cfg_file = SCRIPT_DIR / "config.ini"
    if cfg_file.exists(): cfg.read(cfg_file, encoding="utf-8")
    def g(s, k, d=""):
        try: return cfg.get(s, k).strip()
        except: return d
    import pymysql
    return pymysql.connect(
        host=os.getenv("DB_HOST", g("database","host","localhost")),
        port=int(os.getenv("DB_PORT", g("database","port","3306"))),
        database=os.getenv("DB_NAME", g("database","name","investory")),
        user=os.getenv("DB_USER", g("database","user","root")),
        password=os.getenv("DB_PASSWORD", g("database","password","")),
        charset="utf8mb4", autocommit=True,
    )


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
    if not m: return {"symbol": symbol, "metrics": None, "note": "暂无缓存数据，请在量化页面刷新指标"}
    return {"symbol": symbol, "percentile_5y": round(float(m[0]),1) if m[0] else None,
            "beta": round(float(m[1]),2) if m[1] else None,
            "volatility": round(float(m[2]),1) if m[2] else None,
            "maxDrawdown": round(float(m[3]),1) if m[3] else None}


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
    """运行组合风格诊断（调用现有 portfolio_style_analyzer）"""
    import subprocess
    script = SCRIPT_DIR / "portfolio_style_analyzer.py"
    if not script.exists(): return {"error": "风格分析引擎未找到"}
    r = subprocess.run(["python3", str(script), "--portfolio-id", str(portfolio_id), "--mode", "quick"],
                       capture_output=True, text=True, timeout=30)
    try: return json.loads(r.stdout)
    except: return {"error": "风格分析失败", "raw": r.stdout[:200]}


def tool_list_strategies() -> list:
    """获取用户保存的策略列表"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("SELECT id, name, strategy_type, created_at FROM backtest_strategies ORDER BY id DESC")
    rows = cur.fetchall()
    cur.close(); conn.close()
    return [{"id": r[0], "name": r[1], "type": r[2], "created": str(r[3])} for r in rows]


def tool_get_strategy(strategy_id: int) -> dict:
    """获取单个策略的详情"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("SELECT id, name, strategy_type, strategy_json FROM backtest_strategies WHERE id=%s", (strategy_id,))
    r = cur.fetchone()
    cur.close(); conn.close()
    if not r: return {"error": "策略未找到"}
    return {"id": r[0], "name": r[1], "type": r[2], "config": r[3][:500]}


# ── Tool definitions (OpenAI format) ────────────────────────────────────

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
        "name": "get_strategy", "description": "获取某个策略的详细配置",
        "parameters": {"type": "object", "properties": {
            "id": {"type": "integer", "description": "策略ID"}
        }, "required": ["id"]}
    }},
]

TOOL_LABELS = {
    "get_portfolio": "正在读取持仓数据...",
    "get_stock_metrics": "正在查询量化指标...",
    "get_backtests": "正在获取回测记录...",
    "get_style_analysis": "正在分析组合风格...",
    "list_strategies": "正在获取策略列表...",
    "get_strategy": "正在读取策略详情...",
}

def execute_tool(name: str, args: dict, portfolio_id: int) -> str:
    label = TOOL_LABELS.get(name, f"调用 {name}")
    print(f"[TOOL] {label}", flush=True)
    if name == "get_portfolio":
        return json.dumps(tool_get_portfolio(portfolio_id), ensure_ascii=False)
    elif name == "get_stock_metrics":
        return json.dumps(tool_get_stock_metrics(args.get("symbol","")), ensure_ascii=False)
    elif name == "get_backtests":
        return json.dumps(tool_get_backtests(args.get("limit",5)), ensure_ascii=False)
    elif name == "get_style_analysis":
        result = tool_get_style_analysis(portfolio_id)
        return json.dumps(result, ensure_ascii=False)
    elif name == "list_strategies":
        return json.dumps(tool_list_strategies(), ensure_ascii=False)
    elif name == "get_strategy":
        return json.dumps(tool_get_strategy(args.get("id",0)), ensure_ascii=False)
    return json.dumps({"error": f"unknown tool: {name}"})


# ── OpenAI-compatible streaming with function calling ────────────────────

def get_proxy():
    """Load proxy URL from config.ini"""
    import configparser
    cfg = configparser.ConfigParser()
    cfg_file = SCRIPT_DIR / "config.ini"
    if cfg_file.exists(): cfg.read(cfg_file, encoding="utf-8")
    try: return cfg.get("proxy", "url", fallback="").strip()
    except: return ""


def call_openai_with_tools(api_key: str, model: str, messages: list, api_base: str, portfolio_id: int, deep_think: bool = False):
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

    formatted = []
    for m in messages:
        role = m.get("role", "user")
        if role not in ("system", "user", "assistant", "tool"): role = "user"
        entry = {"role": role, "content": m.get("content", "")}
        if "tool_calls" in m: entry["tool_calls"] = m["tool_calls"]
        if "tool_call_id" in m: entry["tool_call_id"] = m["tool_call_id"]
        formatted.append(entry)

    # First call — non-streaming to check for tool calls
    resp = client.chat.completions.create(model=model, messages=formatted, tools=TOOLS, stream=False, temperature=0.7, max_tokens=max_tokens)
    msg = resp.choices[0].message

    if msg.tool_calls:
        # Execute tools
        formatted.append({"role": "assistant", "content": msg.content, "tool_calls": [
            {"id": tc.id, "type": "function", "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
            for tc in msg.tool_calls
        ]})
        for tc in msg.tool_calls:
            try: args = json.loads(tc.function.arguments)
            except: args = {}
            result = execute_tool(tc.function.name, args, portfolio_id)
            formatted.append({"role": "tool", "tool_call_id": tc.id, "content": result})

        # Second call with tool results — stream
        stream2 = client.chat.completions.create(model=model, messages=formatted, stream=True, temperature=0.7, max_tokens=max_tokens)
        for chunk in stream2:
            delta = chunk.choices[0].delta
            if delta.content:
                sys.stdout.write(delta.content + "\n"); sys.stdout.flush()
    elif msg.content:
        # No tools needed — write the full response at once
        sys.stdout.write(msg.content + "\n"); sys.stdout.flush()

    print("\n[DONE]", flush=True)


def call_anthropic_stream(api_key: str, model: str, messages: list):
    import anthropic, httpx
    kwargs = {"api_key": api_key}
    proxy_url = os.getenv("PROXY_URL", get_proxy())
    if proxy_url:
        kwargs["http_client"] = httpx.Client(proxy=proxy_url)
    client = anthropic.Anthropic(**kwargs)
    system_prompt = None
    formatted = []
    for m in messages:
        role = m.get("role", "user")
        if role == "system": system_prompt = m.get("content", ""); continue
        if role not in ("user", "assistant"): role = "user"
        formatted.append({"role": role, "content": m.get("content", "")})
    kwargs = {"model": model, "messages": formatted, "max_tokens": 1024, "stream": True}
    if system_prompt: kwargs["system"] = system_prompt
    with client.messages.stream(**kwargs) as stream:
        for text in stream.text_stream:
            sys.stdout.write(text + "\n"); sys.stdout.flush()
    print("[DONE]", flush=True)


# ── Main ─────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Investory 观澜 AI Agent")
    parser.add_argument("--provider", default="openai", choices=["openai", "anthropic", "openai_compat"])
    parser.add_argument("--model", default="gpt-4o-mini")
    parser.add_argument("--api-key", required=True)
    parser.add_argument("--api-base", default="")
    parser.add_argument("--deep-think", action="store_true")
    parser.add_argument("--portfolio-id", type=int, default=0)
    parser.add_argument("--input", required=True)
    args = parser.parse_args()

    with open(args.input, "r", encoding="utf-8") as f:
        input_data = json.load(f)
    messages = input_data.get("messages", [])
    if not messages: print("[ERROR] 对话消息为空", flush=True); sys.exit(1)

    kb = load_knowledge_base()
    system_prompt = build_system_prompt(kb)
    if args.deep_think:
        system_prompt += "\n\n用户已启用深度思考模式。请给出更详细的分析：展示推理步骤、引用具体数据、考虑多种情景、明确列出假设和局限。"
    full_messages = [{"role": "system", "content": system_prompt}] + messages

    try:
        if args.provider == "anthropic":
            call_anthropic_stream(args.api_key, args.model, full_messages)
        else:
            call_openai_with_tools(args.api_key, args.model, full_messages, args.api_base, args.portfolio_id, args.deep_think)
    except Exception as e:
        print(f"[ERROR] {e}", flush=True)
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__": main()
