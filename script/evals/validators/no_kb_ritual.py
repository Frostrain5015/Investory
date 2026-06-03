"""是否无故 consult_kb

The knowledge base is a *judgment standard library*, not a workflow scheduler.
consult_kb must never be a forced first step: it should never appear in the
deterministic prefetch, and (live) must not be the opening move of a data-
analysis workflow. Strategy generation legitimately consults '策略引擎' first,
so cases set ``kb_allowed: true`` to waive the live opening-move check.
"""
from _base import result, PASS, FAIL, SKIP, is_live


def validate(case: dict, trace: dict) -> list:
    expect = case.get("expect", {})
    out = []
    dag_tools = trace.get("dag_tools", [])

    # consult_kb is never part of deterministic forensics, in any workflow.
    out.append(result(
        "kb_not_prefetched",
        PASS if "consult_kb" not in dag_tools else FAIL,
        "kb not ritualised" if "consult_kb" not in dag_tools else "consult_kb wrongly prefetched",
    ))

    if is_live(trace):
        if expect.get("kb_allowed"):
            out.append(result("kb_not_first_live", SKIP, "kb consult is expected for this workflow"))
        else:
            called = trace.get("called_tools", [])
            first = called[0] if called else None
            out.append(result(
                "kb_not_first_live",
                FAIL if first == "consult_kb" else PASS,
                f"first call={first}",
            ))
    elif not expect.get("kb_allowed"):
        out.append(result("kb_not_first_live", SKIP, "needs --profile full"))

    return out
