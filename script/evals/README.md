# 观澜 Agent Evals

Turns the workflow contracts (agent skills + DAG) into a verifiable quality gate.
Absorbs the AutoGPT-benchmark + ECC-quality-gate idea: every release runs a smoke
profile that asserts the agent *plans* the right deterministic forensics before a
single token is generated.

## Run

```bash
# offline pre-deploy gate — no API key / DB / engine needed
python script/evals/run_evals.py --profile smoke

# additionally run the real agent and grade the streamed answer
EVAL_API_KEY=... EVAL_MODEL=qwen-plus \
EVAL_API_BASE=https://dashscope.aliyuncs.com/compatible-mode/v1 \
python script/evals/run_evals.py --profile full
```

Exit code is non-zero if any check fails, so `deploy.py` / CI can block on it.

## Layout

```
evals/
├── cases/          # one JSON per scenario; declares input + expectations
├── validators/     # one module per concern; validate(case, trace) -> [results]
└── run_evals.py    # builds the trace and runs every validator over every case
```

### Trace modes

- **plan** (smoke): introspects `ai_agent._plan_workflow_dag`, `_detect_agent_skills`
  and `_select_tools` — what the agent *would* do. Fully deterministic, offline.
- **live** (full): runs `ai_agent.py` as a subprocess and parses its
  `[TOOL]/[REASONING]/[DONE]` protocol into `called_tools` + `output`.

Checks that need a live answer (grounding, ≤N sentences, no memory-guessing,
portfolio-not-dominant) `SKIP` under smoke and run under full.

## What each validator checks

| validator | checklist items |
|---|---|
| `tool_sequence` | 正确工具 · 并行调用 · 工具暴露/withhold |
| `no_search_when_symbol_clear` | 不为解析符号误调 `search_stocks` |
| `no_kb_ritual` | 不把 `consult_kb` 当仪式化前置 |
| `answer_grounding` | 依据工具结果作答 · 不超过 N 句 · 不凭记忆猜 · 持仓画像不喧宾夺主 |

## Adding a case

Drop a JSON in `cases/`. `expect` keys are all optional — only what you set is
checked:

```jsonc
{
  "id": "my_case",
  "profiles": ["smoke", "full"],
  "input": "用户问题原文",
  "portfolio_id": 1, "user_id": 1, "web_search": false,
  "expect": {
    "dag_workflow": "stock_diagnosis",      // or null for write/model workflows
    "first_round_tools": ["get_stock_price"],
    "parallel": true,
    "symbol_clear": true,                    // forbids search_stocks in prefetch
    "available_includes": ["web_search"],
    "available_excludes": ["generate_strategy"],
    "kb_allowed": false,                     // true only for strategy workflow
    "grounded": true,
    "max_sentences": 6,
    "portfolio_must_not_dominate": true
  }
}
```
