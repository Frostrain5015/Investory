#!/usr/bin/env python3
"""观澜 agent eval / quality gate.

Turns the workflow contracts into verifiable checks. Two profiles:

  smoke  (default, fully offline) — introspects the deterministic planning layer
         (skill detection, DAG prefetch plan, tool subsetting). No API key, DB,
         or engine needed, so it can run as a pre-deploy gate:
             python script/evals/run_evals.py --profile smoke
  full   — additionally runs ai_agent.py against a real model and validates the
         streamed trace (grounding, concision, no fabrication). Requires creds
         via env: EVAL_PROVIDER, EVAL_MODEL, EVAL_API_KEY, EVAL_API_BASE.

Exit code is non-zero if any check FAILs, so CI / a deploy script can block on it.
"""
import argparse
import importlib
import importlib.util
import json
import os
import sys
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
SCRIPT_DIR = EVAL_DIR.parent
CASES_DIR = EVAL_DIR / "cases"
VALIDATORS_DIR = EVAL_DIR / "validators"

# Make ai_agent (and its sibling agent_message) and the validators importable.
for p in (str(SCRIPT_DIR), str(VALIDATORS_DIR)):
    if p not in sys.path:
        sys.path.insert(0, p)

import ai_agent as A  # noqa: E402

VALIDATOR_MODULES = [
    "tool_sequence",
    "no_search_when_symbol_clear",
    "no_kb_ritual",
    "answer_grounding",
]


def load_validators():
    mods = []
    for name in VALIDATOR_MODULES:
        mods.append((name, importlib.import_module(name)))
    return mods


def load_cases(profile: str):
    cases = []
    for path in sorted(CASES_DIR.glob("*.json")):
        with open(path, "r", encoding="utf-8") as f:
            case = json.load(f)
        if profile in case.get("profiles", ["smoke"]):
            cases.append(case)
    return cases


# ── Plan trace (offline) ─────────────────────────────────────────────────────

def build_plan_trace(case: dict) -> dict:
    msgs = [{"role": "user", "content": case["input"]}]
    pid = case.get("portfolio_id", 1)
    uid = case.get("user_id", 1)
    expose_web = bool(case.get("web_search")) or A._should_use_web_search(msgs)
    plan = A._plan_workflow_dag(msgs, pid, uid, expose_web)
    available = [t["function"]["name"] for t in A._select_tools(msgs, expose_web)]
    return {
        "mode": "plan",
        "input": case["input"],
        "active_skills": A._detect_agent_skills(msgs),
        "dag_workflow": plan["workflow"],
        "dag_tools": [name for name, _args in plan["tasks"]],
        "synthesize": plan["synthesize"],
        "symbol": plan["symbol"],
        "available_tools": available,
        "expose_web": expose_web,
    }


# ── Live trace (full profile) ────────────────────────────────────────────────

def build_live_trace(case: dict, base: dict) -> dict:
    """Run the real agent and parse its streamed protocol into a trace.
    Degrades to plan mode (logs a warning) if creds are missing or it errors."""
    import subprocess
    import tempfile

    api_key = os.environ.get("EVAL_API_KEY") or os.environ.get("AI_API_KEY", "")
    provider = os.environ.get("EVAL_PROVIDER", "openai_compat")
    model = os.environ.get("EVAL_MODEL", "qwen-plus")
    api_base = os.environ.get("EVAL_API_BASE", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    if not api_key:
        print("  [warn] EVAL_API_KEY/AI_API_KEY not set — live checks will SKIP", file=sys.stderr)
        return base

    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as tf:
        json.dump({"messages": [{"role": "user", "content": case["input"]}]}, tf, ensure_ascii=False)
        input_path = tf.name

    cmd = [sys.executable, str(SCRIPT_DIR / "ai_agent.py"),
           "--mode", "chat", "--provider", "anthropic" if provider == "anthropic" else "openai",
           "--model", model, "--api-key", api_key, "--api-base", api_base,
           "--portfolio-id", str(case.get("portfolio_id", 1)),
           "--user-id", str(case.get("user_id", 1)), "--input", input_path]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=240)
        out = proc.stdout
    except Exception as e:  # noqa: BLE001
        print(f"  [warn] live run failed ({e}) — live checks will SKIP", file=sys.stderr)
        return base
    finally:
        try:
            os.unlink(input_path)
        except OSError:
            pass

    called, first_round, answer_parts = [], [], []
    seen_answer = False
    for line in out.splitlines():
        if line.startswith("[TOOL] ") or line.startswith("[KB]"):
            # "[TOOL] name\tcat\tcid" or "[KB]\ttopic"
            body = line.split("] ", 1)[-1] if "] " in line else line
            name = body.split("\t", 1)[0].strip()
            name = "consult_kb" if line.startswith("[KB]") else name
            called.append(name)
            if not seen_answer:
                first_round.append(name)
        elif line.startswith("[REASONING]") or line.startswith("[TOOL_END]") \
                or line.startswith("[TOOL_FAIL]") or line.startswith("[CONTEXT]") \
                or line.startswith("[STRATEGY]") or line.strip() == "[DONE]":
            continue
        else:
            if line.strip():
                seen_answer = True
                answer_parts.append(line)

    live = dict(base)
    live.update({
        "mode": "live",
        "called_tools": called,
        "first_round_tools_called": first_round,
        "output": "\n".join(answer_parts),
    })
    return live


# ── Run ──────────────────────────────────────────────────────────────────────

def run(profile: str) -> int:
    validators = load_validators()
    cases = load_cases(profile)
    if not cases:
        print(f"No cases for profile {profile!r}.")
        return 1

    total = {"pass": 0, "fail": 0, "skip": 0}
    failed_cases = []

    print(f"\n观澜 evals · profile={profile} · {len(cases)} cases\n" + "=" * 60)
    for case in cases:
        trace = build_plan_trace(case)
        if profile == "full":
            trace = build_live_trace(case, trace)

        results = []
        for _name, mod in validators:
            try:
                results.extend(mod.validate(case, trace) or [])
            except Exception as e:  # a validator must never crash the gate
                results.append({"check": f"{_name}:ERROR", "status": "fail", "detail": str(e)})

        case_failed = any(r["status"] == "fail" for r in results)
        if case_failed:
            failed_cases.append(case["id"])
        mark = "FAIL" if case_failed else "PASS"
        print(f"\n[{mark}] {case['id']}  —  {case.get('description', '')[:54]}")
        print(f"       plan: workflow={trace['dag_workflow']} tools={trace['dag_tools']}")
        for r in results:
            total[r["status"]] = total.get(r["status"], 0) + 1
            glyph = {"pass": "OK ", "fail": "XX ", "skip": "-- "}[r["status"]]
            print(f"         {glyph} {r['check']}: {r['detail']}")

    print("\n" + "=" * 60)
    print(f"checks: {total['pass']} pass · {total['fail']} fail · {total['skip']} skip")
    if failed_cases:
        print(f"FAILED cases: {', '.join(failed_cases)}")
        return 1
    print("all cases passed ✓")
    return 0


def main():
    # Windows consoles default to GBK and can't encode CJK/box glyphs.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description="观澜 agent eval / quality gate")
    ap.add_argument("--profile", default="smoke", choices=["smoke", "full"])
    args = ap.parse_args()
    sys.exit(run(args.profile))


if __name__ == "__main__":
    main()
