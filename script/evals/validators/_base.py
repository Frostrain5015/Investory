"""Shared helpers for eval validators.

A validator is a module exposing ``validate(case, trace) -> list[Result]``.
Each Result is a dict {"check", "status", "detail"} where status is one of
PASS / FAIL / SKIP. SKIP is used when a check needs a live agent run but the
trace was produced in plan-only (smoke) mode — smoke must stay fully offline.
"""

PASS = "pass"
FAIL = "fail"
SKIP = "skip"


def result(check: str, status: str, detail: str = "") -> dict:
    return {"check": check, "status": status, "detail": detail}


def is_live(trace: dict) -> bool:
    return trace.get("mode") == "live"


def count_sentences(text: str) -> int:
    """Count sentences in mixed CN/EN text by terminal punctuation / newlines."""
    import re
    if not text:
        return 0
    parts = re.split(r"[。！？!?\n]+", text.strip())
    return len([p for p in parts if p.strip()])
