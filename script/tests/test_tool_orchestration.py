"""Provider-wire regressions for prefetch, retries, bounded loops and handoffs."""
from contextlib import redirect_stdout
import concurrent.futures
import io
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import MagicMock, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ai_agent as agent


def delta(content=None, calls=None):
    return types.SimpleNamespace(choices=[types.SimpleNamespace(delta=types.SimpleNamespace(
        content=content, tool_calls=calls, reasoning_content=None))])


def tool_delta(name="get_stock_price", arguments='{"symbol":"600519.SH"}'):
    return delta(calls=[types.SimpleNamespace(index=0, id="call", function=types.SimpleNamespace(name=name, arguments=arguments))])


class OrchestrationTest(unittest.TestCase):
    def tools(self, *names):
        return [t for t in agent.TOOLS if t["function"]["name"] in names]

    def test_prefetch_keeps_both_provider_tool_lists_and_emits_context(self):
        seed = [{"name": "get_stock_price", "args": {"symbol": "600519.SH"}, "result": '{"price":123}'}]
        messages = [{"role": "user", "content": "600519.SH为什么跌"}]
        client = Mock()
        client.chat.completions.create.return_value = iter([delta("已有证据。")])
        stream = MagicMock()
        stream.__enter__.return_value = stream
        stream.__iter__.return_value = iter([])
        stream.get_final_message.return_value = types.SimpleNamespace(stop_reason="end_turn")
        client.messages.stream.return_value = stream
        with patch.dict(sys.modules, {"openai": types.SimpleNamespace(OpenAI=Mock(return_value=client)),
                                      "anthropic": types.SimpleNamespace(Anthropic=Mock(return_value=client))}), \
                patch.object(agent, "get_proxy", return_value=""), redirect_stdout(io.StringIO()) as output:
            agent.call_openai_with_tools("test", "exact-id", messages, "https://example.com", 0,
                                         prefetched_tools=seed, context_window=65536)
            agent.call_anthropic_stream("test", "exact-id", messages,
                                        prefetched_tools=seed, api_base="https://example.com", context_window=65536)
        openai_names = {t["function"]["name"] for t in client.chat.completions.create.call_args.kwargs["tools"]}
        anthropic_names = {t["name"] for t in client.messages.stream.call_args.kwargs["tools"]}
        self.assertTrue({"get_stock_price", "get_fundamentals", "web_search"} <= openai_names)
        self.assertEqual(openai_names, anthropic_names)
        contexts = [json.loads(line[10:]) for line in output.getvalue().splitlines() if line.startswith("[CONTEXT] ")]
        self.assertEqual(len(contexts), 2)
        self.assertTrue(all(c["get_stock_price"]["price"] == 123 for c in contexts))

    def test_reuse_is_exact_and_allows_different_symbol_or_days_and_failed_retry(self):
        runner = agent._TurnToolRunner(self.tools("get_stock_price"), [
            {"name": "get_stock_price", "args": {"symbol": "600519.SH", "days": 90}, "result": '{"price":123}'}], 0, 0)
        with patch.object(agent, "execute_tool", side_effect=['{"error":"unavailable"}', '{"price":124}', '{"price":125}']) as execute, redirect_stdout(io.StringIO()):
            self.assertEqual(json.loads(runner.run("get_stock_price", {"days": 90, "symbol": "600519.SH"}, "a"))["price"], 123)
            self.assertIn("error", json.loads(runner.run("get_stock_price", {"symbol": "600519.SH"}, "b")))
            self.assertEqual(json.loads(runner.run("get_stock_price", {"symbol": "600519.SH"}, "c"))["price"], 124)
            runner.run("get_stock_price", {"symbol": "600036.SH"}, "d")
        self.assertEqual(execute.call_count, 3)

    def test_concurrent_identical_reads_share_one_execution(self):
        runner = agent._TurnToolRunner(self.tools("get_stock_price"), [], 0, 0)
        with patch.object(agent, "execute_tool", return_value='{"price":123}') as execute, redirect_stdout(io.StringIO()), \
                concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda cid: runner.run("get_stock_price", {"symbol": "600519.SH"}, cid), ["a", "b"]))
        execute.assert_called_once()
        self.assertEqual(results[0], results[1])

    def test_failed_prefetch_can_retry_but_remains_part_of_request_budget(self):
        runner = agent._TurnToolRunner(self.tools("web_search"), [
            {"name":"web_search","args":{"query":"q"},"result":'{"error":"timeout"}'}], 0, 0)
        with patch.object(agent,"execute_tool",return_value='{"results":[]}') as execute, redirect_stdout(io.StringIO()):
            runner.run("web_search", {"query":"q"}, "retry")
        execute.assert_called_once()
        self.assertEqual(len(runner.completed_calls), 1)

    def test_anthropic_loop_also_ends_with_tools_disabled(self):
        client=Mock()
        def response(**kwargs):
            stream=MagicMock()
            stream.__enter__.return_value=stream
            stream.__iter__.return_value=iter([])
            content=[types.SimpleNamespace(type="tool_use",id="call",name="get_stock_price",input={"symbol":"600519.SH"})] if kwargs['tools'] else []
            stream.get_final_message.return_value=types.SimpleNamespace(stop_reason="tool_use" if content else "end_turn",content=content)
            return stream
        client.messages.stream.side_effect=response
        with patch.dict(sys.modules,{"anthropic":types.SimpleNamespace(Anthropic=Mock(return_value=client))}), \
                patch.object(agent,"get_proxy",return_value=""),patch.object(agent,"MAX_MODEL_ROUNDS",2), \
                patch.object(agent,"execute_tool",return_value='{"price":123}') as execute,redirect_stdout(io.StringIO()) as output:
            agent.call_anthropic_stream("test","id",[{"role":"user","content":"600519.SH走势"}],api_base="https://example.com",context_window=65536)
        self.assertEqual(client.messages.stream.call_count,3)
        self.assertEqual(client.messages.stream.call_args.kwargs['tools'],[])
        execute.assert_called_once()
        self.assertIn('[ERROR]',output.getvalue())

    def test_hidden_tool_and_malformed_arguments_do_not_execute(self):
        runner = agent._TurnToolRunner(self.tools("get_stock_price"), [], 0, 0)
        with patch.object(agent, "execute_tool") as execute, redirect_stdout(io.StringIO()):
            for name, args in [("confirm_delete_transaction", {}), ("get_stock_price", None), ("get_stock_price", []), ("get_stock_price", {})]:
                self.assertIn("error", json.loads(runner.run(name, args, "bad")))
        execute.assert_not_called()

    def test_exhausted_tool_loop_forces_final_request_without_tools(self):
        client = Mock()
        client.chat.completions.create.side_effect = lambda **kwargs: iter([tool_delta()]) if kwargs["tools"] else iter([delta("证据不足，停止继续查询。")])
        with patch.dict(sys.modules, {"openai": types.SimpleNamespace(OpenAI=Mock(return_value=client))}), \
                patch.object(agent, "get_proxy", return_value=""), patch.object(agent, "MAX_MODEL_ROUNDS", 3), \
                patch.object(agent, "execute_tool", return_value='{"price":123}') as execute, redirect_stdout(io.StringIO()) as output:
            agent.call_openai_with_tools("test", "id", [{"role": "user", "content": "600519.SH走势"}],
                                         "https://example.com", 0, context_window=65536)
        self.assertEqual(client.chat.completions.create.call_count, 4)
        self.assertEqual(client.chat.completions.create.call_args.kwargs["tools"], [])
        execute.assert_called_once()
        self.assertIn("[DONE]", output.getvalue())

    def test_strategy_card_handoff_never_backtests_an_unsaved_draft(self):
        with redirect_stdout(io.StringIO()) as output:
            invalid = agent._run_tool("generate_strategy", {"code": "import pandas as pd"}, 0, 0)
            self.assertIn("error", invalid)
            self.assertNotIn("[STRATEGY]", output.getvalue())
            result = agent._run_tool("generate_strategy", {"code": "def decide( ctx ):\n    return 0", "name": "test"}, 0, 0)
        self.assertFalse(result["saved"])
        self.assertTrue(agent._requires_ui_handoff("generate_strategy", json.dumps(result)))
        self.assertNotIn("strategy_id", result)

    def test_missing_trade_amounts_never_become_a_zero_price_confirmation(self):
        with patch.object(agent,"_resolve_stock_id",return_value=379),redirect_stdout(io.StringIO()) as output:
            for values in [{}, {"shares":100}, {"shares":100,"price":0}, {"shares":float('nan'),"price":10}]:
                result=agent._confirm_create({"stockId":379,"stockName":"样例","type":"BUY",**values},0)
                self.assertEqual(result['code'],'MISSING_TRADE_PARAMETERS')
        self.assertNotIn('[CONFIRM]',output.getvalue())

    def test_context_is_valid_bounded_json_including_long_results(self):
        with redirect_stdout(io.StringIO()) as output:
            agent._emit_context({f"tool{i}": json.dumps({"text": "长数据" * 3000}) for i in range(8)})
        blob = output.getvalue().strip()[10:]
        self.assertLessEqual(len(blob), 2500)
        self.assertTrue(json.loads(blob))

    def test_user_can_forbid_network_even_with_toggle_enabled(self):
        messages = [{"role": "user", "content": "不要联网，只分析600519.SH最近走势"}]
        self.assertFalse(agent._should_use_web_search(messages))
        self.assertNotIn("web_search", {t["function"]["name"] for t in agent._select_tools(messages, True)})
        self.assertNotIn("web_search", {name for name, _ in agent._plan_workflow_dag(messages, 1, 1, True)["tasks"]})

    def test_screening_is_not_world_news_and_stock_event_resolves_company(self):
        message=[{"role":"user","content":"不用联网，在全部A股中筛选ROE超过15%的股票，现有工具不能全市场筛选就说做不到。"}]
        self.assertNotIn('get_world_market',{name for name,_ in agent._plan_workflow_dag(message,1,1,False)['tasks']})
        message=[{"role":"user","content":"核实圣泉集团最近下跌是否因为有负面公告"}]
        plan=agent._plan_workflow_dag(message,1,1,False)
        self.assertEqual(plan['symbol'],'圣泉集团')
        self.assertNotIn('get_world_market',{name for name,_ in plan['tasks']})


if __name__ == "__main__":
    unittest.main()
