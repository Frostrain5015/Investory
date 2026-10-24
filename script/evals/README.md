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

`full` 不会因缺少密钥、超时、异常退出或缺少终止帧而降级成 smoke；这些情况均判失败。旧版仅凭工具存在和少量文案特征判断的检查不等于逐条事实核验，仍需复核最终正文和证据口径。

## 场景验收

`journeys.json` 提供15个场景（16轮用户输入）：走势、估值、下跌原因、双标的对比、多轮财报追问、组合风险、自选、交易记录、策略草稿、搜索故障、同名跨市场选择、全球市场、已保存策略回测、筛选能力边界及缺失交易参数。

```sh
EVAL_API_KEY=... EVAL_MODEL=... EVAL_API_BASE=... EVAL_CONTEXT_WINDOW=65536 \
python script/evals/run_journeys.py --report-dir /private/path/guanlan-evals
```

云端隔离候选目录可加 `--server-credentials`：在服务器内部读取已配置模型和对应上下文窗口，并复用运行服务的搜索环境。密钥不进入命令行或报告。候选目录须提供代码和私密配置链接，不直接修改正在运行的生产代码。

实际调用模型、公共行情/基本面/搜索，以及生成策略和确认卡片的代码；组合、用户选择和已保存回测采用明确标记的隔离样例。不应用确认卡片的接口，不写真实用户台账，也不执行任何券商交易。该验收不代表真实账户写入或认证浏览器中的全流程实测。

每个场景在独立进程运行，保留实际思考输出、正文、工具事件、上下文摘要及失败记录；即使进程退出也不丢弃部分轨迹。自动检查工具可用性、终止状态、交接产物、禁止联网、缺失参数追问、策略穿越结构等。最终文本的事实与推断、财报/估值口径、证据缺口仍须人工复核。不同提供商的协议分支另有离线回归，不应把单一云端模型验收当成所有模型已实测。

这些完整轨迹仅用于服务器内部验收；产品 SSE 与历史接口不发送思考和工具明细。`[ACTIVITY]` 是独立的公开进度协议，模型通过可选 `_status` 提供短句，运行时根据真实任务兜底。该字段不进入业务参数、不改变相同读请求的缓存身份，也不产生独立模型调用。客户端只保留当前一条进度，完成后移除；正文、错误提示与操作卡片分别保留。

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
