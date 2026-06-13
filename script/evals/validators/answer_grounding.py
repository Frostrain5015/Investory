"""Answer-quality gate. Covers the output-side checklist items:
  - 是否根据工具结果回答      (grounding)
  - 是否把持仓画像喧宾夺主     (portfolio profile must not dominate a single-stock diagnosis)
  - 是否输出超过 N 句         (concision)
  - 是否凭记忆猜新闻/数据      (no fabrication)

Plan mode (smoke) can only check the *structural* precondition for grounding:
a workflow expected to be grounded must prefetch at least one real data tool.
The text-level checks need a live agent run, so they SKIP under --profile smoke.
"""
from _base import result, PASS, FAIL, SKIP, is_live, count_sentences

# Read-only data tools whose results an answer can be grounded in.
_DATA_TOOLS = {
    "get_stock_price", "get_stock_report", "get_market_regime", "get_world_market",
    "get_portfolio", "get_portfolio_report", "compute_sector_breakdown",
    "compute_correlation", "benchmark_compare", "get_pnl_history", "get_transactions",
    "get_watchlist", "web_search", "run_backtest", "analyze_backtest",
}

# Phrases that signal answering from memory instead of tool evidence.
_MEMORY_TELLS = ["据我所知", "我记得", "印象中", "应该是因为", "可能是因为",
                 "众所周知", "通常来说", "一般而言", "我猜"]

# Portfolio-profile terms that should not dominate a single-stock diagnosis.
_PORTFOLIO_TERMS = ["集中度", "持仓权重", "组合占比", "再平衡", "调仓", "仓位过重"]


def validate(case: dict, trace: dict) -> list:
    expect = case.get("expect", {})
    out = []

    # Structural grounding precondition (plan-checkable).
    if expect.get("grounded"):
        dag_tools = trace.get("dag_tools", [])
        has_data = any(t in _DATA_TOOLS for t in dag_tools)
        out.append(result(
            "grounding_precondition",
            PASS if has_data else FAIL,
            "data prefetched for grounding" if has_data else "no data tool to ground the answer",
        ))

    if not is_live(trace):
        for c in ("grounded_in_results", "max_sentences", "no_memory_guess",
                  "portfolio_not_dominant"):
            if _check_applies(c, expect):
                out.append(result(c, SKIP, "needs --profile full"))
        return out

    output = (trace.get("output") or "").strip()
    called = trace.get("called_tools", [])

    if expect.get("grounded"):
        grounded = bool(output) and any(t in _DATA_TOOLS for t in called)
        out.append(result("grounded_in_results", PASS if grounded else FAIL,
                          f"{len(called)} tools called, output {len(output)} chars"))

    max_s = expect.get("max_sentences")
    if max_s is not None:
        n = count_sentences(output)
        out.append(result("max_sentences", PASS if n <= max_s else FAIL,
                          f"{n} sentences (limit {max_s})"))

    if output:
        hit = [w for w in _MEMORY_TELLS if w in output]
        out.append(result("no_memory_guess", PASS if not hit else FAIL,
                          "grounded language" if not hit else f"memory tells: {hit}"))

    if expect.get("portfolio_must_not_dominate"):
        hits = [w for w in _PORTFOLIO_TERMS if w in output]
        # Heuristic: a single-stock diagnosis may *mention* portfolio terms once,
        # but more than two distinct profile terms means it has taken over.
        out.append(result("portfolio_not_dominant", PASS if len(hits) <= 2 else FAIL,
                          f"profile terms: {hits}"))

    return out


def _check_applies(check: str, expect: dict) -> bool:
    if check == "grounded_in_results":
        return bool(expect.get("grounded"))
    if check == "max_sentences":
        return expect.get("max_sentences") is not None
    if check == "no_memory_guess":
        return True
    if check == "portfolio_not_dominant":
        return bool(expect.get("portfolio_must_not_dominate"))
    return False
