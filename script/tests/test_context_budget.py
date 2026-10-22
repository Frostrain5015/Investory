"""Offline checks of request limits, tool integrity and exact model routing."""
import importlib
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import MagicMock, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from context_budget import ContextBudgetError, active_user, estimated_input, fit_messages, output_budget


class ContextBudgetTest(unittest.TestCase):
    def test_removes_complete_old_turns_but_keeps_system_and_current_tool_chain(self):
        system = {"role": "system", "content": "Keep this instruction"}
        current = {"role": "user", "content": "current"}
        chain = [{"role": "assistant", "tool_calls": [{"id": "id", "function": {"name": "lookup"}}]},
                 {"role": "tool", "tool_call_id": "id", "content": "result"}]
        messages = [system, {"role": "user", "content": "old" * 5000}, {"role": "assistant", "content": "old answer"}, current] + chain
        bounded = fit_messages(messages, [], 4096, 1024, protected_message=current)
        self.assertEqual(bounded, [system, current] + chain)
        self.assertLessEqual(estimated_input(bounded, []) + 1024 + 512, 4096)
        self.assertEqual(len(messages), 6)

    def test_large_current_input_tools_and_anthropic_system_stop_before_request(self):
        for messages, tools, system in [
            ([{"role": "user", "content": "汉" * 10000}], [], None),
            ([{"role": "user", "content": "hi"}], [{"description": "x" * 10000}], None),
            ([{"role": "user", "content": "hi"}], [], "x" * 10000),
        ]:
            with self.subTest(system=bool(system)), self.assertRaises(ContextBudgetError):
                fit_messages(messages, tools, 4096, 1024, system=system)

    def test_anthropic_tool_results_are_part_of_active_turn(self):
        current = {"role": "user", "content": "current"}
        result = {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "id", "content": "x" * 10000}]}
        self.assertIs(active_user([current, result]), current)
        with self.assertRaises(ContextBudgetError):
            fit_messages([current, {"role": "assistant", "content": []}, result], [], 4096, 1024)

    def test_small_windows_reserve_output_and_reject_invalid_values(self):
        self.assertEqual(output_budget(1024), 256)
        self.assertEqual(output_budget(8192), 2048)
        for value in [None, 0, 1023, 1048577]:
            with self.assertRaises(ContextBudgetError): output_budget(value)

    def test_openai_local_service_receives_exact_model_and_bounded_output(self):
        agent = importlib.import_module("ai_agent")
        client = Mock()
        client.chat.completions.create.return_value = iter([])
        factory = Mock(return_value=client)
        with patch.dict(sys.modules, {"openai": types.SimpleNamespace(OpenAI=factory)}), \
             patch.object(agent, "get_proxy", return_value=""), patch.object(agent, "_select_tools", return_value=[]):
            agent.call_openai_with_tools("", "my-model:4bit", [{"role": "user", "content": "hi"}],
                                         "http://localhost:11434/v1", 0, context_window=8192)
        self.assertEqual(factory.call_args.kwargs["base_url"], "http://localhost:11434/v1")
        self.assertTrue(factory.call_args.kwargs["api_key"])
        request = client.chat.completions.create.call_args.kwargs
        self.assertEqual(request["model"], "my-model:4bit")
        self.assertEqual(request["max_tokens"], 2048)

    def test_anthropic_local_service_uses_configured_endpoint_without_proxy(self):
        agent = importlib.import_module("ai_agent")
        client = Mock()
        stream = MagicMock()
        stream.__enter__.return_value = stream
        stream.__iter__.return_value = iter([])
        stream.get_final_message.return_value = types.SimpleNamespace(stop_reason="end_turn")
        client.messages.stream.return_value = stream
        factory = Mock(return_value=client)
        with patch.dict(sys.modules, {"anthropic": types.SimpleNamespace(Anthropic=factory)}), \
             patch.object(agent, "get_proxy", return_value="http://unused-proxy"), \
             patch.object(agent, "_select_tools", return_value=[]):
            agent.call_anthropic_stream("", "exact-local-id", [{"role": "user", "content": "hi"}],
                                        api_base="http://localhost:8000/v1", context_window=8192)
        self.assertEqual(factory.call_args.kwargs["base_url"], "http://localhost:8000")
        self.assertNotIn("http_client", factory.call_args.kwargs)
        self.assertEqual(client.messages.stream.call_args.kwargs["model"], "exact-local-id")
        self.assertEqual(client.messages.stream.call_args.kwargs["max_tokens"], 2048)


if __name__ == "__main__": unittest.main()
