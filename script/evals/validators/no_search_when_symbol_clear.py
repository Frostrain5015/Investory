"""是否错误调用 search_stocks

When the user names a stock unambiguously (Chinese name, plain code, or DB
symbol), resolve_symbol handles format conversion internally — the agent must
NOT burn a turn on search_stocks just to parse the symbol. We assert search_stocks
never appears in the deterministic prefetch, and (live) is not the first call.
"""
from _base import result, PASS, FAIL, SKIP, is_live


def validate(case: dict, trace: dict) -> list:
    expect = case.get("expect", {})
    out = []
    if not expect.get("symbol_clear"):
        return out  # not applicable to this case

    dag_tools = trace.get("dag_tools", [])
    out.append(result(
        "no_search_in_prefetch",
        PASS if "search_stocks" not in dag_tools else FAIL,
        "symbol passed straight through" if "search_stocks" not in dag_tools
        else "search_stocks wrongly prefetched for a clear symbol",
    ))

    if is_live(trace):
        called = trace.get("called_tools", [])
        first = called[0] if called else None
        bad = first == "search_stocks"
        out.append(result(
            "no_search_first_live",
            FAIL if bad else PASS,
            f"first call={first}",
        ))
    else:
        out.append(result("no_search_first_live", SKIP, "needs --profile full"))

    return out
