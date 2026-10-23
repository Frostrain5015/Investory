"""Offline provider contracts and Guanlan routing; never requires a real key."""
import importlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from contextlib import ExitStack
from datetime import date, datetime, timedelta
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "script"))
sys.path.insert(0, str(ROOT / "backend/src/main/python/stocksage_alpha/src"))
import hithink_finance as hf
import pandas as pd


def stamp(day):
    return int(datetime.fromisoformat(day).replace(tzinfo=hf.SHANGHAI).timestamp() * 1000)


def bar(day, close=10):
    return {"date_ms": stamp(day), "open_price": 10, "high_price": 12,
            "low_price": 8, "close_price": close, "volume": 12345, "turnover": 130000}


class TransportTest(unittest.TestCase):
    def client(self):
        return hf.Client(hf.Settings(True, "private-test-key"))

    def response(self, payload):
        response = Mock()
        response.__enter__ = Mock(return_value=io.BytesIO(json.dumps(payload).encode()))
        response.__exit__ = Mock(return_value=False)
        return response

    def test_header_auth_and_shanghai_dates_and_explicit_adjustment(self):
        client = self.client()
        client._opener = Mock()
        client._opener.open.return_value = self.response({"code": 0, "data": {"timestamp": stamp("2026-10-22"), "item": [bar("2026-10-22"), bar("2026-10-21", 9)]}})
        result = client.history("1.600519", date(2026, 10, 21), date(2026, 10, 22))
        request = client._opener.open.call_args.args[0]
        query = parse_qs(urlsplit(request.full_url).query)
        self.assertEqual(query["thscode"], ["600519.SH"])
        self.assertEqual(query["adjust"], ["forward"])
        self.assertEqual(int(query["start"][0]), stamp("2026-10-21"))
        self.assertEqual(request.get_header("X-api-key"), "private-test-key")
        self.assertNotIn("private-test-key", request.full_url)
        self.assertEqual(client._opener.open.call_args.kwargs["timeout"], 5)
        self.assertEqual([row["date"] for row in result["points"]], ["2026-10-21", "2026-10-22"])
        self.assertEqual(result["points"][0]["volume"], 12345)
        self.assertEqual(result["points"][0]["amount"], 130000)
        self.assertNotIn("turnover", result["points"][0])

    def test_business_error_is_redacted_and_opens_breaker_without_retry(self):
        client = self.client()
        client._opener = Mock()
        client._opener.open.return_value = self.response({"code": 4001, "message": "private-test-key upstream secret"})
        for _ in range(2):
            with self.assertRaises(hf.HithinkError) as error:
                client.calendar()
            self.assertNotIn("private-test-key", str(error.exception))
        client._opener.open.assert_called_once()

    def test_network_and_redirect_errors_never_expose_headers_or_body(self):
        for failure in [URLError("private-test-key"), HTTPError("https://example.com", 302, "private-test-key", {}, None), TimeoutError("private-test-key")]:
            client = self.client()
            client._opener = Mock()
            client._opener.open.side_effect = failure
            with self.assertRaises(hf.HithinkError) as error:
                client.calendar()
            self.assertNotIn("private-test-key", str(error.exception))
            client._opener.open.assert_called_once()
        self.assertIsNone(hf._NoRedirect().redirect_request(None, None, 302, "", {}, "https://example.org"))

    def test_invalid_success_shape_is_rejected(self):
        for payload in [{"code": False, "data": {"item": []}}, {"code": 0}, {"code": 0, "data": {"item": [None]}}, {"code": 0, "data": {"item": {}}}]:
            client = self.client()
            client._opener = Mock()
            client._opener.open.return_value = self.response(payload)
            with self.assertRaises(hf.HithinkError):
                client.calendar()

    def test_empty_history_is_empty_and_bad_bars_are_not_filled_with_zero(self):
        client = self.client()
        with patch.object(client, "get", return_value={"item": []}):
            self.assertEqual(client.history("600519.SH", date(2026, 10, 21), date(2026, 10, 22))["points"], [])
        for rows in [[bar("2026-10-21"), bar("2026-10-21")], [bar("2026-10-20")], [{**bar("2026-10-21"), "close_price": None}], [bar("2026-10-21", 20)]]:
            with patch.object(client, "get", return_value={"item": rows}), self.assertRaises(hf.HithinkError):
                client.history("600519.SH", date(2026, 10, 21), date(2026, 10, 22))

    def test_valuation_snapshot_keeps_negative_and_null_values(self):
        client = self.client()
        with patch.object(client, "get", return_value={"timestamp": None, "item": [{"thscode": "600519.SH", "pe_ttm": -2.5, "pb_mrq": None, "ps_ttm": 1.234567, "pcf_ttm": 0}]}):
            result = client.valuation("600519")
        self.assertEqual(result["scope"], "snapshot")
        self.assertEqual(result["pe_ttm"], -2.5)
        self.assertIsNone(result["pb"])
        self.assertEqual(result["ps_ttm"], 1.234567)
        for rows in [[], [{"thscode": "000001.SZ"}]]:
            with patch.object(client, "get", return_value={"item": rows}), self.assertRaises(hf.HithinkError):
                client.valuation("600519")

    def test_symbol_identity_is_preserved_and_invalid_assets_rejected(self):
        for value, expected in [("600519.SH", "600519.SH"), ("SH.600519", "600519.SH"), ("sh600519", "600519.SH"), ("1.600519", "600519.SH"), ("000001", "000001.SZ"), ("0.920002", "920002.BJ"), ("430047.BJ", "430047.BJ")]:
            self.assertEqual(hf.normalize_symbol(value), expected)
        for value in ["AAPL", "00700.HK", "1.600519,0.000001", "", "abc600519"]:
            with self.assertRaises(hf.HithinkError):
                hf.normalize_symbol(value)

    def test_configuration_shares_private_properties_and_env_wins(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "backend").mkdir()
            (root / "backend/hithink-data.local.properties").write_text("investory.hithink.enabled=true\ninvestory.hithink.api-key=file-secret\n", encoding="utf-8")
            with patch.object(hf, "ROOT", root), patch.dict(os.environ, {}, clear=True):
                config = hf.load_settings()
                self.assertTrue(config.enabled)
                self.assertEqual(config.api_key, "file-secret")
                self.assertNotIn("file-secret", repr(config))
            with patch.object(hf, "ROOT", root), patch.dict(os.environ, {"HITHINK_FINANCE_ENABLED": "false", "HITHINK_FINANCE_API_KEY": "env-secret"}, clear=True):
                config = hf.load_settings()
                self.assertFalse(config.enabled)
                self.assertEqual(config.api_key, "env-secret")

    def test_no_configuration_no_network_and_insecure_urls_rejected(self):
        client = hf.Client(hf.Settings(False, ""))
        with patch.object(client._opener, "open") as send, self.assertRaises(hf.HithinkError):
            client.calendar()
        send.assert_not_called()
        for url in ["http://example.com", "https://user:pass@example.com", "https://example.com/path", "https://example.com?key=x"]:
            with patch.dict(os.environ, {"HITHINK_FINANCE_BASE_URL": url}), self.assertRaises(hf.HithinkError):
                hf.load_settings()


class RoutingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Other stock data adapters are not involved in these offline tests.
        import common
        previous = sys.modules.get("akshare")
        sys.modules["akshare"] = types.ModuleType("akshare")
        try:
            with patch.object(common, "setup_proxy", return_value=False):
                cls.fetcher = importlib.import_module("fetcher")
        finally:
            if previous is None:
                del sys.modules["akshare"]
            else:
                sys.modules["akshare"] = previous

    def test_factor_history_reads_only_hithink_qfq_and_new_cache(self):
        import pandas as pd
        provider = Mock()
        provider.history.return_value = {"points": [{"date": "2026-10-21", "open": 10, "high": 11, "low": 9, "close": 10, "volume": 100, "amount": 1000}, {"date": "2026-10-22", "open": 10, "high": 12, "low": 9, "close": 11, "volume": 200, "amount": 2100}]}
        with patch("market_history.history", side_effect=provider.history), patch.object(self.fetcher.cache, "get_df", return_value=None) as cached, patch.object(self.fetcher.cache, "set_df"):
            result = self.fetcher.get_price_history("600519.SH", 1)
        self.assertEqual(len(result), 1)
        self.assertAlmostEqual(result["change_pct"].iloc[0], 10)
        self.assertEqual(result.attrs["adjust"], "forward")
        self.assertTrue(cached.call_args.args[0].startswith("price_hithink_forward_v1_"))
        self.assertNotIn("turnover", result.columns)
        self.assertEqual(provider.history.call_args.args[-1], "forward")
        self.assertTrue(pd.api.types.is_datetime64_any_dtype(result["date"]))
        self.assertFalse(hasattr(self.fetcher, "_get_baostock"))

    def test_failed_history_does_not_fall_back_to_baostock_or_old_cache(self):
        provider = Mock()
        provider.history.side_effect = hf.HithinkError("unavailable")
        with patch("market_history.history", side_effect=provider.history), patch.object(self.fetcher.cache, "get_df", return_value=None) as cached:
            self.assertIsNone(self.fetcher.get_price_history("600519"))
        cached.assert_called_once()
        provider.history.assert_called_once()

    def test_snapshot_cannot_generate_historical_percentiles(self):
        provider = Mock()
        provider.valuation.return_value = {"pe_ttm": 12, "pb": 1.2, "ps_ttm": None, "pcf_ttm": -2, "timestamp": None}
        with patch.object(self.fetcher, "_hithink", return_value=provider), patch.object(self.fetcher.cache, "get", return_value=None), patch.object(self.fetcher.cache, "set"):
            frame = self.fetcher.get_valuation_snapshot("600519")
        from factors.scoring import score_value
        result = score_value(12, 1.2, frame)
        self.assertEqual(result["details"]["pe_score_source"], "absolute")
        self.assertIsNone(result["details"].get("pe_percentile"))
        self.assertIsNone(result["details"].get("pb_percentile"))
        self.assertEqual(len(frame), 1)
        self.assertEqual(frame.attrs["scope"], "snapshot")
        self.assertFalse(frame.attrs["historical_valuation_available"])

    def test_calendar_is_daily_and_unknown_future_is_not_a_holiday(self):
        provider = Mock()
        provider.calendar.return_value = ["2026-10-21", "2026-10-22"]
        with patch.object(self.fetcher, "_hithink", return_value=provider), patch.object(self.fetcher.cache, "get", return_value=None) as cached, patch.object(self.fetcher.cache, "set"):
            self.assertEqual(self.fetcher.get_trade_calendar(), ["2026-10-21", "2026-10-22"])
        self.assertTrue(cached.call_args.args[0].startswith("trade_calendar_hithink_v1_"))
        with self.assertRaises(hf.HithinkError):
            self.fetcher.is_trading_day(datetime.now(hf.SHANGHAI).date() + timedelta(days=1))
        with patch.object(self.fetcher, "get_trade_calendar", return_value=[]), self.assertRaises(hf.HithinkError):
            self.fetcher.is_trading_day()

    def test_guanlan_history_bypasses_price_sql_and_keeps_raw_prices(self):
        agent = importlib.import_module("ai_agent")
        connection = Mock()
        provider = Mock()
        provider.history.return_value = {"points": [{"date": "2026-10-22", "open": 10, "close": 11}], "source": hf.SOURCE, "timestamp": None}
        with patch.object(agent, "get_db_conn", return_value=connection), patch.object(agent, "resolve_symbol", return_value="1.600519"), patch("market_history.history", return_value={"points": [{"date": "2026-10-22", "open": 10, "close": 11}], "source": hf.SOURCE, "adjust": "none", "timestamp": None}) as gateway:
            result = agent.tool_get_stock_price_history("贵州茅台")
        self.assertEqual(result["adjust"], "none")
        self.assertEqual(result["source"], hf.SOURCE)
        self.assertEqual(result["count"], 1)
        self.assertEqual(gateway.call_args.args[-1], "none")
        connection.cursor.assert_not_called()
        connection.close.assert_called_once()

    def test_research_uses_snapshot_for_display_and_does_not_claim_history(self):
        research = importlib.import_module("research")
        price = pd.DataFrame({"date": pd.date_range("2025-01-01", periods=300),
                              "open": [10.0] * 300, "high": [11.0] * 300,
                              "low": [9.0] * 300, "close": [10.0] * 300,
                              "volume": [1000.0] * 300, "amount": [10000.0] * 300,
                              "change_pct": [0.0] * 300, "change_amt": [0.0] * 300})
        snapshot = pd.DataFrame([{"pe_ttm": -2.5, "pb": None, "ps_ttm": 1, "pcf_ttm": 2}])
        responses = {"get_realtime_quote": {"name": "test", "price": 10, "pe_ttm": 20, "pb": 3},
                     "get_stock_info": {}, "get_price_history": price,
                     "get_valuation_snapshot": snapshot, "get_concept_momentum": []}
        with ExitStack() as stack:
            for name in research.__dict__:
                if name.startswith("get_") or name == "_get_spot_df":
                    stack.enter_context(patch.object(research, name, return_value=responses.get(name)))
            result = research.research("600519.SH")
        self.assertNotIn("error", result)
        self.assertEqual(result["valuation"]["pe_ttm"], -2.5)
        self.assertIsNone(result["valuation"]["pb"])
        self.assertEqual(result["valuation"]["scope"], "snapshot")
        self.assertNotIn("pe_3y_median", result["valuation"])
        self.assertIsNone(result["valuation"]["pe_percentile"])
        self.assertTrue(result["data_limitations"])

    def test_report_surfaces_data_limitations(self):
        import importlib.util
        path = ROOT / "backend/src/main/python/stocksage_alpha/bridge.py"
        spec = importlib.util.spec_from_file_location("hithink_test_bridge", path)
        bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bridge)
        research_result = {"code": "600519", "name": "test", "factors": {}, "data_limitations": ["历史估值分位不可用"]}
        with patch.object(bridge, "_research_cached", return_value=research_result):
            report = bridge._build_stock_report("600519.SH")
        self.assertIn("历史估值分位不可用", report["data_quality"]["missing"])
        self.assertNotIn("valuation_history", report["data_sources"])


class LatestQuoteTest(unittest.TestCase):
    def client(self):
        return hf.Client(hf.Settings(True, "test-key"))

    def test_snapshot_batches_fifty_deduplicates_aliases_and_expires_at_25s(self):
        client = self.client()
        symbols = [f"600{index:03}.SH" for index in range(51)] + ["1.600000"]
        def response(path, params):
            self.assertEqual(path, "/api/a-share/prices/snapshot")
            return {"timestamp": stamp("2026-10-22"), "item": [
                {"thscode": code, "last_price": 9, "prev_price": 10, "price_change_ratio_pct": -10}
                for code in params["thscodes"].split(",")]}
        with patch.object(client, "get", side_effect=response) as get, patch.object(hf.clock, "monotonic", return_value=100):
            result = client.snapshot(symbols)
            self.assertEqual(get.call_count, 2)
            self.assertEqual(len(result), 52)
            self.assertEqual(result["1.600000"], result["600000.SH"])
            self.assertEqual(result["1.600000"]["change"], -1)
            self.assertEqual(result["1.600000"]["changePct"], -10)
            self.assertFalse(result["1.600000"]["live"])
        with patch.object(client, "get", side_effect=response) as get, patch.object(hf.clock, "monotonic", return_value=124):
            client.snapshot(["1.600000"])
            get.assert_not_called()
        with patch.object(client, "get", side_effect=response) as get, patch.object(hf.clock, "monotonic", return_value=125):
            client.snapshot(["1.600000"])
            get.assert_called_once()

    def test_partial_snapshot_keeps_missing_and_null_changes_unknown(self):
        client = self.client()
        with patch.object(client, "get", return_value={"timestamp": None, "item": [
                {"thscode": "600519.SH", "last_price": 100, "prev_price": None},
                {"thscode": "000001.SZ", "last_price": None}]}):
            result = client.snapshot(["1.600519", "0.000001"])
        self.assertEqual(set(result), {"1.600519"})
        self.assertIsNone(result["1.600519"]["change"])
        self.assertIsNone(result["1.600519"]["changePct"])
        with patch.object(client, "get") as get:
            self.assertEqual(client.snapshot(["0.000001"]), {})
            get.assert_not_called()

    def test_quote_cache_does_not_expose_mutable_internal_records(self):
        client = self.client()
        with patch.object(client, "get", return_value={"item": [{"thscode": "600519.SH", "last_price": 100}]}):
            result = client.snapshot(["1.600519"])
        result["1.600519"]["price"] = 0
        self.assertEqual(client.snapshot(["1.600519"])["1.600519"]["price"], 100)

    def test_guanlan_latest_price_does_not_query_historical_price_table(self):
        agent = importlib.import_module("ai_agent")
        connection = Mock()
        provider = Mock()
        provider.snapshot.return_value = {"1.600519": {"price": 100, "change": -1, "source": hf.SOURCE, "timestamp": None}}
        with patch.object(agent, "get_db_conn", return_value=connection), patch.object(agent, "resolve_symbol", return_value="1.600519"), patch.object(hf, "get_client", return_value=provider):
            result = agent.tool_get_stock_price("贵州茅台")
        self.assertEqual(result["price"], 100)
        self.assertEqual(result["change"], -1)
        self.assertEqual(result["symbol"], "贵州茅台")
        connection.cursor.assert_not_called()
        provider.snapshot.assert_called_once_with(["1.600519"])

    def portfolio(self, returned_quotes):
        agent = importlib.import_module("ai_agent")
        connection = Mock()
        cursor = connection.cursor.return_value
        cursor.fetchall.return_value = [("1.600519", "one", "SH", 10, 90, 900, "CNY"),
                                         ("0.000001", "two", "SZ", 2, 100, 200, "CNY")]
        with patch.object(agent, "get_db_conn", return_value=connection), patch.object(hf, "get_client") as factory:
            factory.return_value.snapshot.return_value = returned_quotes
            result = agent.tool_get_portfolio(1)
            factory.return_value.snapshot.assert_called_once_with(["1.600519", "0.000001"])
        sql = cursor.execute.call_args.args[0]
        self.assertNotIn("stock_prices", sql)
        cursor.execute.assert_called_once()
        connection.close.assert_called_once()
        return result

    def test_portfolio_weights_use_current_market_value(self):
        result = self.portfolio({"1.600519": {"price": 100}, "0.000001": {"price": 50}})
        self.assertEqual(result["totalValue"], 1100)
        self.assertEqual(result["holdings"][0]["weight"], 90.91)
        self.assertEqual(result["holdings"][1]["weight"], 9.09)
        self.assertTrue(result["valuationComplete"])

    def test_missing_price_cannot_be_replaced_by_cost_or_create_total(self):
        result = self.portfolio({"1.600519": {"price": 100}})
        self.assertIsNone(result["totalValue"])
        self.assertEqual(result["unpricedSymbols"], ["0.000001"])
        self.assertIsNone(result["holdings"][1]["price"])
        self.assertIsNone(result["holdings"][1]["mv"])
        self.assertTrue(all(row["weight"] is None for row in result["holdings"]))

    def test_watchlist_fetches_batch_without_per_stock_sql(self):
        agent = importlib.import_module("ai_agent")
        connection = Mock()
        cursor = connection.cursor.return_value
        cursor.fetchall.return_value = [(1, 1, "1.600519", "one", "SH", "CNY"), (2, 2, "0.000001", "two", "SZ", "CNY")]
        with patch.object(agent, "get_db_conn", return_value=connection), patch.object(hf, "get_client") as factory:
            factory.return_value.snapshot.return_value = {"1.600519": {"price": 100}}
            result = agent.tool_get_watchlist(7)
        self.assertIsNone(result["items"][1]["price"])
        cursor.execute.assert_called_once()
        self.assertNotIn("stock_prices", cursor.execute.call_args.args[0])
        connection.cursor.assert_called_once()

    def test_non_a_share_uses_external_history_without_sql(self):
        agent = importlib.import_module("ai_agent")
        with patch.object(agent, "get_db_conn") as database, patch.object(hf, "get_client") as factory, patch("market_history.latest", return_value={"116.00700": {"source": "yahoo", "price": 100, "change": -1}}):
            factory.return_value.snapshot.return_value = {"1.600519": {"price": 100}}
            result = agent._latest_quotes(["1.600519", "116.00700"])
        self.assertEqual(result["116.00700"]["source"], "yahoo")
        self.assertEqual(result["116.00700"]["change"], -1)
        database.assert_not_called()
        factory.return_value.snapshot.assert_called_once_with(["1.600519"])

    def test_provider_failure_never_reopens_a_share_price_sql(self):
        agent = importlib.import_module("ai_agent")
        with patch.object(agent, "get_db_conn") as connect, patch.object(hf, "get_client") as factory:
            factory.return_value.snapshot.side_effect = hf.HithinkError("unavailable")
            result = agent._latest_quotes(["1.600519"])
        connect.assert_not_called()
        self.assertIsNone(result["1.600519"]["price"])
        self.assertEqual(result["1.600519"]["note"], "unavailable")


if __name__ == "__main__":
    unittest.main()
