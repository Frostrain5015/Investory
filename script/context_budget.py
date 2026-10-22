"""Conservative request budgeting without assuming a provider's tokenizer.

UTF-8 bytes overestimate token counts for the text tokenizers we support. Wire
overhead and a safety reserve are included; this is not a GPU memory estimator.
Never split an assistant tool call from its results, or discard the active turn.
"""
import json


class ContextBudgetError(ValueError):
    pass


def output_budget(context_window: int) -> int:
    if not isinstance(context_window, int) or not 1024 <= context_window <= 1048576:
        raise ContextBudgetError("请在设置中填写有效的上下文窗口（1024 至 1048576）")
    return min(4096, max(256, context_window // 4))


def estimated_input(messages: list, tools: list, system=None) -> int:
    wire = {"messages": messages, "tools": tools}
    if system:
        wire["system"] = system
    return len(json.dumps(wire, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) + 256 * (len(messages) + len(tools))


def active_user(messages: list):
    for message in reversed(messages):
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, list) and content and all(block.get("type") == "tool_result" for block in content):
            continue
        return message
    return None


def fit_messages(messages: list, tools: list, context_window: int, max_output: int, *, system=None, protected_message=None) -> list:
    """Drop complete old turns; refuse an oversized current turn before I/O."""
    output_budget(context_window)
    budget = context_window - max_output - 512
    fixed = [message for message in messages if message.get("role") == "system"]
    conversation = [message for message in messages if message.get("role") != "system"]
    protected = protected_message if protected_message is not None else active_user(conversation)
    while estimated_input(fixed + conversation, tools, system) > budget:
        anchor = next((index for index, message in enumerate(conversation) if message is protected), 0)
        # A whole prefix ending before a user message is safe to remove. All
        # tool calls/results within a kept turn remain together.
        boundary = next((index for index in range(1, anchor + 1)
                         if conversation[index].get("role") == "user"
                         and active_user([conversation[index]]) is not None), None)
        if boundary is None:
            raise ContextBudgetError("当前提示词、工具定义或本轮数据超过上下文预算。请缩短输入、拆分任务，或在模型和硬件允许时增大上下文窗口。")
        conversation = conversation[boundary:]
    return fixed + conversation
