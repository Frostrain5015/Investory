"""Live evidence cannot silently turn into a passing offline plan."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT))
spec = importlib.util.spec_from_file_location("eval_runner", SCRIPT / "evals/run_evals.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class EvalTraceTest(unittest.TestCase):
    def test_missing_credentials_are_a_live_failure(self):
        with patch.dict("os.environ", {}, clear=True):
            result = runner.build_live_trace({"input":"测试"}, {"mode":"plan"})
        self.assertEqual(result["mode"], "live")
        self.assertTrue(result["runtime_error"])

    def test_trace_keeps_reasoning_errors_handoffs_and_json_without_mixing_into_answer(self):
        result = runner.parse_live_trace('[TOOL] web_search\tanalysis\tc1\n[REASONING]核实证据\\n继续分析\n'
            '[TOOL_FAIL] c1\tweb_search\t网络失败\n[ERROR] 连接中断\n[CONTEXT] {"price":123}\n[来源](https://example.com)\n[DONE]\n')
        self.assertEqual(result["called_tools"], ["web_search"])
        self.assertIn("继续分析", result["reasoning"])
        self.assertEqual(result["contexts"], [{"price":123}])
        self.assertTrue(result["runtime_error"])
        self.assertEqual(result["output"], '[来源](https://example.com)')

    def test_no_done_or_empty_answer_fails_but_ui_handoff_is_valid(self):
        self.assertTrue(runner.parse_live_trace('回答但没有终止帧')["runtime_error"])
        self.assertTrue(runner.parse_live_trace('[DONE]')["runtime_error"])
        self.assertFalse(runner.parse_live_trace('[STRATEGY] {"name":"draft"}\n[DONE]')["runtime_error"])
        self.assertTrue(runner.parse_live_trace('[CONTEXT] {broken\n[DONE]')["runtime_error"])

    def test_suite_includes_personal_fixtures_multi_turn_and_public_data(self):
        cases=json.loads((SCRIPT/'evals/journeys.json').read_text(encoding='utf-8'))
        self.assertGreaterEqual(len(cases), 15)
        self.assertTrue(any(len(case['prompts'])>1 for case in cases))
        self.assertTrue(any(case.get('fixture')=='search_failure' for case in cases))
        self.assertTrue(any(case.get('fixture')=='saved_backtest' for case in cases))


if __name__=='__main__':
    unittest.main()
