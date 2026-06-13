"""Unified internal message representation for 观澜's provider adapters.

Both the OpenAI-compatible and Anthropic code paths converge on this structure
and then each emits its own wire format. This is the single place where role,
content, reasoning, tool calls and tool results are modelled, so the two
providers can't drift apart — e.g. one keeping reasoning while the other drops
it, or (a real bug this fixes) the Anthropic adapter overwriting earlier system
messages instead of keeping them like the OpenAI adapter does.

The Hermes-style idea: different API modes ultimately converge to one internal
message format; adapters are pure (AgentMessage -> wire dict) functions.

Note on reasoning continuity:
- OpenAI-compatible chat APIs have no dedicated reasoning *input* field, so on
  the way back in we fold any preserved reasoning into the assistant content.
- Anthropic extended-thinking requires the original thinking blocks *with their
  signatures* for verbatim continuity. Those signed blocks are reconstructed by
  the streaming loop from the live response (they can't be rebuilt from a plain
  reasoning string), so historical AgentMessages carry reasoning as best-effort
  context only. The live-turn signed blocks stay in the provider loop.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Literal, Optional

Role = Literal["system", "user", "assistant", "tool"]


@dataclass
class ToolCall:
    """A single tool/function call. ``arguments`` is the JSON-encoded argument
    string (OpenAI's native form); Anthropic's ``input`` dict is derived from it."""
    id: str
    name: str
    arguments: str = ""

    def arguments_dict(self) -> dict:
        if isinstance(self.arguments, dict):
            return self.arguments
        try:
            parsed = json.loads(self.arguments) if self.arguments else {}
        except Exception:
            return {}
        return parsed if isinstance(parsed, dict) else {}


@dataclass
class AgentMessage:
    """Provider-neutral message. ``tool_result_for`` is the id of the tool call
    a ``role="tool"`` message answers."""
    role: Role
    content: Optional[str] = None
    reasoning: Optional[str] = None
    tool_calls: list = field(default_factory=list)  # list[ToolCall]
    tool_result_for: Optional[str] = None

    @classmethod
    def from_wire(cls, m: dict) -> "AgentMessage":
        """Build from the loose dict form used in conversation history / the
        Java-supplied input (OpenAI-ish: role/content/tool_calls/tool_call_id)."""
        role = m.get("role", "user")
        if role not in ("system", "user", "assistant", "tool"):
            role = "user"
        tcs = []
        for tc in (m.get("tool_calls") or []):
            if not isinstance(tc, dict):
                continue
            fn = tc.get("function", {}) or {}
            tcs.append(ToolCall(id=tc.get("id", ""), name=fn.get("name", ""),
                                arguments=fn.get("arguments", "")))
        return cls(
            role=role,
            content=m.get("content", ""),
            reasoning=m.get("reasoning"),
            tool_calls=tcs,
            tool_result_for=m.get("tool_call_id"),
        )


def _merge_reasoning_into_content(content: Optional[str], reasoning: Optional[str]):
    """Fold reasoning into content for OpenAI-compatible replay (no reasoning
    input field exists). Returns None when both are empty so the wire stays clean."""
    c = content.strip() if isinstance(content, str) else ""
    r = reasoning.strip() if isinstance(reasoning, str) else ""
    if r and c:
        return f"{c}\n\n【工具调用前的推理上下文（用于延续分析，不要复述给用户）】\n{r}"
    if r:
        return f"【工具调用前的推理上下文（用于延续分析，不要复述给用户）】\n{r}"
    return c or None


def split_system(messages) -> tuple:
    """Concatenate every system message into one block; return (system_text, rest).
    This is what stops the Anthropic adapter from losing all but the last system
    message — every adapter now sees the same merged system text."""
    sys_parts = [m.content for m in messages if m.role == "system" and m.content]
    rest = [m for m in messages if m.role != "system"]
    return ("\n\n".join(sys_parts), rest)


# ── OpenAI-compatible wire format ────────────────────────────────────────────

def to_openai_wire(m: AgentMessage, *, cache_system: bool = False) -> dict:
    if m.role == "system":
        if cache_system and isinstance(m.content, str):
            # DashScope/Anthropic-style prompt caching on the system block.
            return {"role": "system", "content": [
                {"type": "text", "text": m.content, "cache_control": {"type": "ephemeral"}}]}
        return {"role": "system", "content": m.content or ""}
    if m.role == "tool":
        return {"role": "tool", "tool_call_id": m.tool_result_for or "", "content": m.content or ""}
    if m.role == "assistant" and m.tool_calls:
        return {
            "role": "assistant",
            "content": _merge_reasoning_into_content(m.content, m.reasoning),
            "tool_calls": [
                {"id": t.id, "type": "function",
                 "function": {"name": t.name, "arguments": t.arguments}}
                for t in m.tool_calls
            ],
        }
    return {"role": m.role, "content": m.content or ""}


def to_openai_messages(messages, *, cache_system: bool = False) -> list:
    return [to_openai_wire(m, cache_system=cache_system) for m in messages]


# ── Anthropic wire format ────────────────────────────────────────────────────

def to_anthropic_messages(messages) -> tuple:
    """Return (system_text, anthropic_messages). System messages are merged (not
    overwritten); tool results become user tool_result blocks; assistant tool
    calls become text + tool_use blocks."""
    system_text, rest = split_system(messages)
    out = []
    for m in rest:
        if m.role == "tool":
            out.append({"role": "user", "content": [{
                "type": "tool_result",
                "tool_use_id": m.tool_result_for or "",
                "content": m.content or "",
            }]})
            continue
        if m.role == "assistant" and m.tool_calls:
            blocks = []
            # Reasoning from generic history has no signature, so it can't be a
            # verbatim thinking block; carry it as plain text context instead.
            merged = _merge_reasoning_into_content(m.content, m.reasoning)
            if merged:
                blocks.append({"type": "text", "text": merged})
            for t in m.tool_calls:
                blocks.append({"type": "tool_use", "id": t.id, "name": t.name,
                               "input": t.arguments_dict()})
            out.append({"role": "assistant", "content": blocks})
            continue
        role = m.role if m.role in ("user", "assistant") else "user"
        out.append({"role": role, "content": m.content or ""})
    return system_text, out
