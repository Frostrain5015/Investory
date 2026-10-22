"""Explicit, billed read-only probe; never collected by default unittest discovery."""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ai_agent


def main():
    records = []
    for query in ("贵州茅台 三季报 site:sse.com.cn", "site:sec.gov NVIDIA annual report"):
        began = time.monotonic()
        result = ai_agent.tool_web_search(query, 3)
        records.append({"query": query, "code": result.get("code"), "provider": result.get("provider"),
                        "count": len(result.get("results", [])), "elapsedMs": round((time.monotonic() - began) * 1000),
                        "sources": [{"title": row["title"], "url": row["url"], "date": row.get("date")}
                                    for row in result.get("results", [])]})
    print(json.dumps(records, ensure_ascii=False))
    return 0 if all(row["provider"] == "deepseek_search" and row["count"] > 0 and not row["code"] for row in records) else 1


if __name__ == "__main__":
    raise SystemExit(main())
