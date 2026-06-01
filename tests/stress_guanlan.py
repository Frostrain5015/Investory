#!/usr/bin/env python3
"""
观澜 AI 压力测试脚本 —— 无需前端，直接 HTTP 调用。

用法:
    python tests/stress_guanlan.py [--host HOST] [--count N] [--verbose]

它:
    1. 登录（test/test123）
    2. 逐个发送预定义测试问题
    3. 监听 SSE 流，记录：工具调用名称、交互卡片类型、响应长度、耗时
    4. 输出汇总报告
"""

from __future__ import annotations

import argparse
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from http.cookiejar import CookieJar
# Fix Unicode output on Windows
if sys.platform == 'win32':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

# ── Config ────────────────────────────────────────────────────────────────────

HOST      = os.getenv("INVESTORY_HOST", "https://116.62.179.231:8443")
CTX_PATH  = "/investory"
USERNAME  = os.getenv("INVESTORY_USER", "test")
PASSWORD  = os.getenv("INVESTORY_PASS", "test123")
PROXY     = os.getenv("HTTPS_PROXY", "socks5h://127.0.0.1:7897")

# Test cases: (label, prompt, expected_tools, expected_cards)
TEST_CASES = [
    ("简单问候", "你好，请用一句话介绍你自己",
     None, None),

    ("市场环境", "现在A股市场整体情况怎么样？大盘是牛市还是熊市？",
     ["get_market_regime"], None),

    ("因子评分", "分析一下茅台600519的因子评分，价值、成长、质量方面表现如何",
     ["get_factor_scores"], None),

    ("持仓诊断", "分析一下我的投资组合，看看有哪些风险和问题",
     ["get_portfolio", "get_portfolio_analysis", "compute_correlation"],
     ["portfolio_analysis"]),

    ("今日选股", "今天有哪些值得关注的股票推荐？",
     ["get_daily_picks"], ["picks"]),

    ("基本面查询", "查询600519的基本面数据",
     ["get_fundamentals"], None),

    ("联网搜索", "最近A股有什么重大新闻",
     ["web_search"], None),

    ("策略生成", "写一个简单的均线交叉策略：当5日线上穿20日线时买入，下穿时卖出",
     ["generate_strategy"], ["strategy"]),

    ("股票搜索", "帮我搜索比亚迪的股票代码",
     ["search_stocks"], None),

    ("价格查询", "600519现在多少钱",
     ["get_stock_price"], None),
]


# ── Helpers ───────────────────────────────────────────────────────────────────

_SSL_CTX = ssl.create_default_context()
_SSL_CTX.check_hostname = False
_SSL_CTX.verify_mode = ssl.CERT_NONE
_COOKIE_JAR = CookieJar()


def _open(path: str, data: bytes = None, headers: dict = None, method: str = "GET", timeout: int = 30):
    """Simple HTTPS request with cookie persistence."""
    req = urllib.request.Request(f"{HOST}{CTX_PATH}{path}", data=data, headers=headers or {}, method=method)
    _COOKIE_JAR.add_cookie_header(req)
    resp = urllib.request.urlopen(req, context=_SSL_CTX, timeout=timeout)
    _COOKIE_JAR.extract_cookies(resp, req)
    return resp


def login() -> bool:
    data = urllib.parse.urlencode({"username": USERNAME, "password": PASSWORD}).encode()
    try:
        r = _open("/api/session/test-login", data=data,
                   headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST")
        resp = json.loads(r.read())
        return resp.get("authenticated", False)
    except Exception as e:
        print(f"  Login failed: {e}")
        return False


def post_json(path: str, body: dict, timeout: int = 30) -> dict:
    data = json.dumps(body).encode()
    try:
        r = _open(path, data=data, headers={"Content-Type": "application/json"}, method="POST", timeout=timeout)
        return json.loads(r.read())
    except Exception as e:
        return {"error": str(e)[:200]}


def stream_sse(path: str, timeout: int = 120) -> dict:
    """Connect to SSE stream, collect all events, return summary."""
    result = {
        "tokens": 0,
        "tool_calls": [],
        "cards": [],
        "response": "",
        "error": None,
        "elapsed": 0,
    }
    t0 = time.time()
    try:
        import http.client
        # Build cookie header from global cookie jar
        cookie_str = "; ".join(f"{c.name}={c.value}" for c in _COOKIE_JAR)
        purl = urllib.parse.urlparse(f"{HOST}{CTX_PATH}{path}")
        conn = http.client.HTTPSConnection(purl.hostname, purl.port,
                                            timeout=timeout, context=_SSL_CTX)
        conn.request("GET", f"{CTX_PATH}{path}", headers={
            "Accept": "text/event-stream",
            "Cookie": cookie_str,
        })
        resp = conn.getresponse()
        if resp.status != 200:
            result["error"] = f"SSE HTTP {resp.status}"
            return result

        buffer = ""
        line_count = 0
        while True:
            chunk = resp.read(4096)
            if not chunk:
                break
            buffer += chunk.decode("utf-8", errors="replace")
            while "\n\n" in buffer:
                block, buffer = buffer.split("\n\n", 1)
                line_count += 1
                event_type = ""
                data_str = ""
                for line in block.split("\n"):
                    if line.startswith("event:"):
                        event_type = line[6:].strip()
                    elif line.startswith("data:"):
                        data_str = line[5:].strip()
                if not data_str:
                    continue

                try:
                    data = json.loads(data_str)
                except json.JSONDecodeError:
                    continue

                if event_type == "token":
                    result["tokens"] += 1
                    result["response"] += data.get("msg", "")
                elif event_type == "tool":
                    result["tool_calls"].append(data.get("name", "unknown"))
                elif event_type in ("strategy", "portfolio_card", "picks_card"):
                    result["cards"].append(event_type)
                elif event_type == "error":
                    result["error"] = data.get("msg", "SSE error")
                    break
                elif event_type == "done":
                    break
        conn.close()
    except Exception as e:
        if not result["error"]:
            result["error"] = str(e)[:200]

    result["elapsed"] = round(time.time() - t0, 1)
    result["response"] = result["response"].strip()
    return result


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="观澜 AI 压力测试")
    parser.add_argument("--host", default=HOST, help=f"服务器地址 (default: {HOST})")
    parser.add_argument("--count", type=int, default=0, help="运行次数，0=只跑一次全部用例")
    parser.add_argument("--verbose", "-v", action="store_true", help="显示详细SSE输出")
    parser.add_argument("--filter", "-f", help="只运行名称包含此字符串的用例")
    args = parser.parse_args()

    host = args.host
    print(f"╔══════════════════════════════════════════╗")
    print(f"║  观澜 AI 压力测试                       ║")
    print(f"║  {host}  ║")
    print(f"║  {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}                       ║")
    print(f"╚══════════════════════════════════════════╝")
    print()

    # Login
    print("▶ 登录...", end=" ", flush=True)
    if not login():
        print("FAILED — 请检查用户名密码或服务是否运行")
        sys.exit(1)
    print("OK")

    # Filter cases
    cases = TEST_CASES
    if args.filter:
        cases = [c for c in cases if args.filter.lower() in c[0].lower()]
        if not cases:
            print(f"  没有匹配 '{args.filter}' 的用例"); sys.exit(0)

    total_ok = 0
    total_fail = 0
    total_time = 0.0
    all_results = []

    runs = max(args.count, 1)
    for run in range(runs):
        if runs > 1:
            print(f"\n─── 第 {run+1}/{runs} 轮 ───")

        for label, prompt, exp_tools, exp_cards in cases:
            print(f"\n▶ [{label}] {prompt[:60]}...")
            t0 = time.time()

            # 1. POST /chat
            chat_resp = post_json("/api/ai/chat", {
                "messages": [{"role": "user", "content": prompt}],
                "deepThink": False,
            })
            if "error" in chat_resp:
                print(f"  ✗ Chat init failed: {chat_resp['error'][:100]}")
                total_fail += 1
                continue

            # 2. SSE /stream
            sse = stream_sse("/api/ai/stream", timeout=120)
            elapsed = round(time.time() - t0, 1)
            total_time += elapsed

            # 3. Check results
            issues = []
            if sse["error"]:
                issues.append(f"SSE Error: {sse['error'][:80]}")
            if exp_tools:
                missing = [t for t in exp_tools if t not in sse["tool_calls"]]
                if missing:
                    issues.append(f"Missing tools: {missing}")
            if exp_cards:
                missing_c = [c for c in exp_cards if c not in sse["cards"]]
                if missing_c:
                    issues.append(f"Missing cards: {missing_c}")

            ok = len(issues) == 0 and sse["response"] and not sse["error"]
            status = "✓" if ok else "✗"

            print(f"  {status} {elapsed}s | tokens:{sse['tokens']} | tools:{sse['tool_calls']} | cards:{sse['cards']} | resp:{len(sse['response'])}chars")
            if issues:
                for i in issues:
                    print(f"    ⚠ {i}")
            if args.verbose:
                print(f"    Response: {sse['response'][:200]}...")
                if sse["error"]:
                    print(f"    Error: {sse['error']}")

            all_results.append({"label": label, "ok": ok, "sse": sse, "elapsed": elapsed})
            if ok:
                total_ok += 1
            else:
                total_fail += 1

    # ── Summary ──
    print(f"\n{'═' * 50}")
    print(f"  总计: {total_ok + total_fail} 用例  |  ✓ {total_ok}  |  ✗ {total_fail}")
    print(f"  总耗时: {total_time:.1f}s  |  均耗时: {total_time/max(total_ok+total_fail,1):.1f}s")
    if all_results:
        ok_rate = total_ok / len(all_results) * 100
        print(f"  通过率: {ok_rate:.0f}%")
        if ok_rate < 100:
            failed = [r for r in all_results if not r["ok"]]
            print(f"  失败用例: {', '.join(r['label'] for r in failed)}")

    if total_fail > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
