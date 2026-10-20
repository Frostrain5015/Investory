import json
import logging
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fetch_stocks
from crawl_protocol import a_share_readiness, hk_stock_code, yahoo_exclusive_end


class ProviderRows:
    def __init__(self, rows, error_code="0"):
        self.rows = iter(rows)
        self.error_code = error_code
        self.error_msg = "provider failed"

    def next(self):
        self.row = next(self.rows, None)
        return self.row is not None

    def get_row_data(self):
        return self.row


class MarketCrawlTest(unittest.TestCase):
    def test_yahoo_end_includes_the_selected_last_day(self):
        self.assertEqual(yahoo_exclusive_end("2026-10-20"), "2026-10-21")
        self.assertEqual(yahoo_exclusive_end("2026-12-31"), "2027-01-01")

    def test_hk_equities_and_indices_are_not_the_same_code_type(self):
        for symbol in ["0700.HK", "00700.HK", "116.00700", "700"]:
            self.assertEqual(hk_stock_code(symbol), "00700")
        for symbol in ["HSI.HK", "HSCE.HK", "HSTECH.HK", "00000.HK", "invalid"]:
            self.assertIsNone(hk_stock_code(symbol), symbol)

    def provider(self, prices, trading=True):
        return types.SimpleNamespace(
            query_trade_dates=Mock(return_value=ProviderRows([["2026-10-20", "1" if trading else "0"]])),
            query_history_k_data_plus=Mock(return_value=ProviderRows(prices)))

    def test_unpublished_close_returns_pending_before_full_universe_download(self):
        provider = self.provider([["2026-10-19", "3000"]])
        self.assertEqual(a_share_readiness(provider, "2026-10-20"), ("pending", "2026-10-19"))

    def test_ready_date_and_nontrading_day_are_distinct(self):
        provider = self.provider([["2026-10-20", "3000"]])
        self.assertEqual(a_share_readiness(provider, "2026-10-20"), ("ready", "2026-10-20"))
        provider = self.provider([], trading=False)
        self.assertEqual(a_share_readiness(provider, "2026-10-20"), ("no_change", None))
        provider.query_history_k_data_plus.assert_not_called()

    def test_provider_error_cannot_be_misreported_as_unpublished_data(self):
        provider = self.provider([])
        provider.query_trade_dates.return_value = ProviderRows([], error_code="error")
        with self.assertRaises(RuntimeError):
            a_share_readiness(provider, "2026-10-20")

    def test_hk_index_rows_do_not_abort_valid_stock_writes(self):
        connection = Mock()
        connection.cursor.return_value.fetchall.return_value = [
            (1, "HSI.HK", "Hang Seng"), (2, "0700.HK", "Tencent"), (3, "HSCE.HK", "HSCE")]
        logger = Mock(spec=logging.Logger)
        cfg = {"proxy_url": "", "delay_hk": 0}
        providers = [("mock", Mock(return_value=[("2026-10-20", 1, 2, 3, 1, 10)]))]
        with patch.dict(sys.modules, {"yfinance": types.SimpleNamespace(Ticker=Mock())}), \
                patch.object(fetch_stocks, "get_conn", return_value=connection), \
                patch.object(fetch_stocks, "build_skip_set", return_value=set()), \
                patch.object(fetch_stocks, "load_checkpoint", return_value=None), \
                patch.object(fetch_stocks, "save_checkpoint"), patch.object(fetch_stocks, "clear_checkpoint"), \
                patch.object(fetch_stocks, "_hk_providers", return_value=providers), \
                patch.object(fetch_stocks, "_kline_multi", return_value=(providers[0][1](), "mock")), \
                patch.object(fetch_stocks, "upsert_prices", return_value=1) as upsert, \
                patch.object(fetch_stocks.time, "sleep"):
            fetch_stocks.fetch_hk_stocks(cfg, "2026-10-17", "2026-10-20", False, False, logger)
            upsert.assert_called_once()
            self.assertEqual(upsert.call_args.args[1][0][0], 2)
            result = next(call.args[0][8:] for call in logger.info.call_args_list
                          if str(call.args[0]).startswith("RESULT: "))
            summary = json.loads(result)
            self.assertEqual(summary["stocks_excluded"], 2)
            self.assertEqual(summary["latest_data_date"], "2026-10-20")
            self.assertEqual(summary["rows_written"], 1)


if __name__ == "__main__":
    unittest.main()
