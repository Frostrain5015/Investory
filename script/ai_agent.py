#!/usr/bin/env python3
"""
Investory 「观澜」AI Agent

用法:
    python3 ai_agent.py --provider openai --model gpt-4o-mini --api-key sk-xxx --input /tmp/ai_input.json

从 stdin/input 文件读取对话 JSON，调用 OpenAI/Anthropic streaming API，
逐 token 输出到 stdout（供 Java AiApiController 解析为 SSE 事件）。

stdout 格式:
    <token_chunk>          → SSE "token" 事件
    [DONE]                 → SSE "done" 事件
    [ERROR] <message>       → SSE "error" 事件
"""

import argparse
import json
import os
import sys
import traceback
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
KB_FILE = SCRIPT_DIR / "ai_knowledge_base.json"


def load_knowledge_base() -> dict:
    if KB_FILE.exists():
        try:
            return json.loads(KB_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {}


def build_system_prompt(kb: dict) -> str:
    """将知识库注入 system prompt"""
    principles_text = "\n".join(
        f"- **{p['name']}**: {p['description']}（应用：{p['application']}）"
        for p in kb.get("core_principles", [])
    )
    metrics_text = "\n".join(
        f"- **{k}**: {v}" for k, v in kb.get("key_metrics_guide", {}).items()
    )

    return f"""你是「观澜」（Horizon），Investory 投资组合管理系统的 AI 助理。

你的投资哲学根植于价值投资传统——本杰明·格雷厄姆的安全边际、沃伦·巴菲特的能力圈和护城河、查理·芒格的多元思维模型。

## 核心投资原则

{principles_text}

## 关键指标解读

{metrics_text}

## 风格要求

- 回答简洁务实，不追求华丽辞藻，不编造数据
- 涉及投资建议时，始终提醒"这仅是基于数据的分析，不构成投资建议"
- 引用具体的价值投资原则来解释你的判断
- 当用户询问投机性问题时，温和地引导回价值投资框架
- 你无法获取实时行情，所有数据均来自系统的历史数据库
- 不确定时明确说"我不确定"或"我需要更多信息来判断"
- 用中文回复，专业术语保留英文（如 ROE、DCF、Sharpe Ratio）
- 系统名称为 Investory（盈亏鉴），你属于该系统的一部分"""


def call_openai_stream(api_key: str, model: str, messages: list):
    """OpenAI streaming chat completion"""
    from openai import OpenAI
    client = OpenAI(api_key=api_key)

    # Convert messages to ensure assistant role (not 'assistant')
    formatted = []
    for m in messages:
        role = m.get("role", "user")
        if role not in ("system", "user", "assistant"):
            role = "user"
        formatted.append({"role": role, "content": m.get("content", "")})

    stream = client.chat.completions.create(
        model=model,
        messages=formatted,
        stream=True,
        temperature=0.7,
        max_tokens=2048,
    )
    for chunk in stream:
        delta = chunk.choices[0].delta
        if delta.content:
            sys.stdout.write(delta.content)
            sys.stdout.flush()
    print("\n[DONE]", flush=True)


def call_anthropic_stream(api_key: str, model: str, messages: list):
    """Anthropic streaming message"""
    import anthropic
    client = anthropic.Anthropic(api_key=api_key)

    # Anthropic uses system as a separate parameter
    system_prompt = None
    formatted = []
    for m in messages:
        role = m.get("role", "user")
        if role == "system":
            system_prompt = m.get("content", "")
            continue
        if role not in ("user", "assistant"):
            role = "user"
        formatted.append({"role": role, "content": m.get("content", "")})

    kwargs = {"model": model, "messages": formatted, "max_tokens": 2048, "stream": True}
    if system_prompt:
        kwargs["system"] = system_prompt

    with client.messages.stream(**kwargs) as stream:
        for text in stream.text_stream:
            sys.stdout.write(text)
            sys.stdout.flush()
    print("\n[DONE]", flush=True)


def main():
    parser = argparse.ArgumentParser(description="Investory 观澜 AI Agent")
    parser.add_argument("--provider", default="openai", choices=["openai", "anthropic"])
    parser.add_argument("--model", default="gpt-4o-mini")
    parser.add_argument("--api-key", required=True)
    parser.add_argument("--input", required=True, help="Path to input JSON file with messages")
    args = parser.parse_args()

    # Load input
    with open(args.input, "r", encoding="utf-8") as f:
        input_data = json.load(f)

    messages = input_data.get("messages", [])
    if not messages:
        print("[ERROR] 对话消息为空", flush=True)
        sys.exit(1)

    # Load knowledge base
    kb = load_knowledge_base()

    # Prepend system prompt
    system_prompt = build_system_prompt(kb)
    full_messages = [{"role": "system", "content": system_prompt}] + messages

    try:
        if args.provider == "anthropic":
            call_anthropic_stream(args.api_key, args.model, full_messages)
        else:
            call_openai_stream(args.api_key, args.model, full_messages)
    except Exception as e:
        print(f"[ERROR] {e}", flush=True)
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
