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
import hithink_finance as hf


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
