"""Bounded local regression for the reported StockSage chat interruption."""
import io
import json
from pathlib import Path
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ai_agent as agent


class EngineTransportTest(unittest.TestCase):
    def test_public_price_history_is_trimmed_without_inventing_volume(self):
        points = [{"date": str(i), "close": i + 1, "high": i + 2, "low": i, "volume": None} for i in range(90)]
        result = agent._trim_result("get_stock_price", {"points": points, "symbol": "1.605589"})
        self.assertTrue(result["trimmed"])
        self.assertEqual(len(result["recentPoints"]), 30)
        self.assertIsNone(result["summary"]["avgVolume"])
        self.assertEqual(result["summary"]["endDate"], "89")

    def test_read_timeout_does_not_launch_the_same_report_in_a_subprocess(self):
        for failure in [TimeoutError("private details"), URLError(TimeoutError("private details")),
                        HTTPError("http://localhost", 503, "private details", {}, io.BytesIO())]:
            with patch("urllib.request.urlopen", side_effect=failure), patch.object(agent, "_engine_subprocess") as fallback:
                result = agent._engine("stocksage_report", {"symbol": "1.605589"}, .01)
            self.assertIn("error", result)
            self.assertNotIn("private details", result["error"])
            fallback.assert_not_called()

    def test_only_connection_refusal_can_use_cli_fallback(self):
        with patch("urllib.request.urlopen", side_effect=URLError(ConnectionRefusedError())), patch.object(agent, "_engine_subprocess", return_value={"ok": True}) as fallback:
            self.assertEqual(agent._engine("stock_report", {}, .1), {"ok": True})
        fallback.assert_called_once()

    def test_real_quiet_http_timeout_finishes_without_second_execution(self):
        completed=threading.Event()
        class QuietHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                completed.wait(.4)
                try:
                    self.send_response(200); self.end_headers(); self.wfile.write(b'{"ok":true}')
                except OSError:
                    pass
            def log_message(self, *args):
                pass
        server=ThreadingHTTPServer(("127.0.0.1",0),QuietHandler)
        thread=threading.Thread(target=server.serve_forever,daemon=True)
        thread.start()
        try:
            start=time.monotonic()
            with patch.object(agent,"ENGINE_BASE",f"http://127.0.0.1:{server.server_port}"), patch.object(agent,"_engine_subprocess") as fallback:
                result=agent._engine("stocksage_report",{},.05)
            self.assertLess(time.monotonic()-start,.3)
            self.assertIn("超时",result["error"])
            fallback.assert_not_called()
        finally:
            completed.set(); server.shutdown(); server.server_close(); thread.join()

    def test_recent_trend_is_lightweight_but_explicit_reports_remain_available(self):
        messages=[{"role":"user","content":"帮我分析一下圣泉集团这只股票最近的走势"}]
        plan=agent._plan_workflow_dag(messages,1,1,False)
        self.assertEqual(plan["workflow"],"stock_trend")
        self.assertEqual([name for name,_ in plan["tasks"]],["get_stock_price"])
        self.assertEqual(plan["tasks"][0][1]["days"],90)
        names={tool["function"]["name"] for tool in agent._select_tools(messages,True)}
        self.assertIn("get_stock_price",names)
        self.assertNotIn("get_stock_report",names)
        self.assertNotIn("get_market_regime",names)
        deep=[{"role":"user","content":"对圣泉集团走势做一份深度分析报告"}]
        names={tool["function"]["name"] for tool in agent._select_tools(deep,False)}
        self.assertIn("get_stock_report",names)

    def test_trend_prefetch_uses_history_without_touching_quant_engine(self):
        messages=[{"role":"user","content":"帮我分析一下圣泉集团这只股票最近的走势"}]
        with patch.object(agent,"execute_tool",return_value=json.dumps({"points":[{"date":"2026-10-22","close":10}]})) as execute, patch.object(agent,"_engine") as engine:
            result=agent._run_workflow_dag(messages,1,1,False)
        self.assertIn("get_stock_price",result["successful_tools"])
        execute.assert_called_once()
        self.assertEqual(execute.call_args.args[1]["days"],90)
        engine.assert_not_called()
