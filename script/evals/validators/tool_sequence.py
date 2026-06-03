"""Validate the planned/actual tool selection: correct tools + parallel fan-out.

Covers two of the checklist items:
  - 是否调用了正确工具  (the workflow's first-round tools are planned)
  - 是否并行调用        (independent read-only tools fan out in one round)
Plus tool-exposure sanity: required tools are available, forbidden ones aren't.
"""
from _base import result, PASS, FAIL, SKIP, is_live


def validate(case: dict, trace: dict) -> list:
    expect = case.get("expect", {})
    out = []
    dag_tools = trace.get("dag_tools", [])
    available = set(trace.get("available_tools", []))

    # Workflow identity.
    exp_wf = expect.get("dag_workflow", "__unset__")
    if exp_wf != "__unset__":
        got = trace.get("dag_workflow")
        out.append(result(
            "workflow_match",
            PASS if got == exp_wf else FAIL,
            f"expected workflow {exp_wf!r}, got {got!r}",
        ))

    # Correct first-round tools planned (subset of the prefetch).
    first = expect.get("first_round_tools")
    if first:
        missing = [t for t in first if t not in dag_tools]
        out.append(result(
            "correct_tools",
            PASS if not missing else FAIL,
            "all first-round tools planned" if not missing else f"missing from plan: {missing}",
        ))

    # No DAG prefetch expected (write / model-authored workflows).
    if expect.get("no_dag_prefetch"):
        out.append(result(
            "no_dag_prefetch",
            PASS if not dag_tools else FAIL,
            "no prefetch (correct)" if not dag_tools else f"unexpected prefetch: {dag_tools}",
        ))

    # Parallel fan-out: >1 independent read tool in the first round.
    if expect.get("parallel") is True:
        out.append(result(
            "parallel_calls",
            PASS if len(dag_tools) >= 2 else FAIL,
            f"{len(dag_tools)} tools fan out" if len(dag_tools) >= 2 else f"only {len(dag_tools)} planned, expected parallel",
        ))
    elif expect.get("parallel") is False:
        # Nothing to fan out; just assert we didn't fabricate a parallel prefetch.
        out.append(result(
            "no_forced_parallel",
            PASS if len(dag_tools) <= 1 else FAIL,
            "no forced parallel" if len(dag_tools) <= 1 else f"unexpected fan-out: {dag_tools}",
        ))

    # Tool exposure: required available, forbidden withheld.
    for t in expect.get("available_includes", []):
        out.append(result(
            f"available[{t}]",
            PASS if t in available else FAIL,
            "exposed" if t in available else "missing from tool set",
        ))
    for t in expect.get("available_excludes", []):
        out.append(result(
            f"withheld[{t}]",
            PASS if t not in available else FAIL,
            "withheld" if t not in available else "wrongly exposed",
        ))

    # Live: confirm the actual first call batch matches the plan's intent.
    if is_live(trace) and first:
        called_first = trace.get("first_round_tools_called", [])
        missing = [t for t in first if t not in called_first]
        out.append(result(
            "correct_tools_live",
            PASS if not missing else FAIL,
            f"first batch={called_first}",
        ))
    elif first:
        out.append(result("correct_tools_live", SKIP, "needs --profile full"))

    return out
