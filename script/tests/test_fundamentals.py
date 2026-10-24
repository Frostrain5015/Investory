"""Exercise the real tool/provider path, without the retired SQL table."""
from datetime import datetime
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ai_agent as agent
import hithink_finance as hf


def stamp(day):
    return int(datetime.fromisoformat(day).replace(tzinfo=hf.SHANGHAI).timestamp() * 1000)


class FundamentalsTest(unittest.TestCase):
    def client(self):
        return hf.Client(hf.Settings(True, "test-only-secret"))

    def income(self, **overrides):
        return {"item": [{"thscode": "605589.SH", "period_end_ms": stamp("2026-06-30"),
                          "report_date_ms": stamp("2026-08-15"), "basic_eps": 0.53,
                          "net_profit": 100, **overrides}]}

    def test_actual_dispatch_uses_provider_without_any_database_for_code(self):
        client = self.client()
        def response(path, params):
            if path.endswith("valuations/snapshot"):
                return {"item": [{"thscode": "605589.SH", "pe_ttm": -2, "pb_mrq": 0}]}
            self.assertEqual(params, {"thscode": "605589.SH", "period": "quarterly", "limit": 1})
            return self.income()
        with patch.object(agent, "get_db_conn", side_effect=AssertionError("No SQL data path")), \
                patch.object(hf, "get_client", return_value=client), \
                patch.object(client, "get", side_effect=response), \
                patch.object(client, "financial_indicators", return_value={"roe": 5.2, "debt_ratio": None}) as indicators:
            result = agent._run_tool("get_fundamentals", {"symbol": "605589"}, 1, 7)
        indicators.assert_called_once_with("605589.SH", "2026-2")
        self.assertEqual(result["valuation"]["pe_ttm"], -2)
        self.assertEqual(result["valuation"]["pb"], 0)
        self.assertEqual(result["financials"]["basic_eps"], 0.53)
        self.assertIsNone(result["financials"]["debt_ratio"])
        self.assertNotIn("eps_ttm", result["financials"])
        self.assertNotIn("error", result)

    def test_name_resolution_closes_identity_connection(self):
        conn = Mock()
        provider = Mock()
        provider.fundamentals.return_value = {"source": hf.SOURCE}
        with patch.object(agent, "get_db_conn", return_value=conn), \
                patch.object(agent, "resolve_symbol", return_value="1.605589"), \
                patch.object(hf, "get_client", return_value=provider):
            result = agent.tool_get_fundamentals("圣泉集团")
        conn.close.assert_called_once()
        provider.fundamentals.assert_called_once_with("1.605589")
        self.assertEqual(result["requestedSymbol"], "圣泉集团")

    def test_partial_failure_preserves_data_and_reports_limitations(self):
        client = self.client()
        with patch.object(client, "valuation", side_effect=hf.HithinkError("暂不可用")), \
                patch.object(client, "get", return_value=self.income()), \
                patch.object(client, "financial_indicators", side_effect=hf.HithinkError("指标缺失")):
            result = client.fundamentals("605589.SH")
        self.assertEqual(result["financials"]["net_profit"], 100)
        self.assertEqual(len(result["limitations"]), 2)
        self.assertNotIn("error", result)

    def test_total_failure_is_error_and_never_falls_back_to_sql(self):
        client = self.client()
        with patch.object(client, "valuation", side_effect=hf.HithinkError("暂不可用")), \
                patch.object(client, "get", side_effect=hf.HithinkError("暂不可用")):
            self.assertIn("error", client.fundamentals("605589.SH"))

    def test_wrong_security_or_future_disclosure_is_not_accepted(self):
        client = self.client()
        for overrides in [{"thscode": "600519.SH"}, {"report_date_ms": stamp("2099-01-01")}]:
            with patch.object(client, "valuation", return_value={"pe_ttm": 10}), \
                    patch.object(client, "get", return_value=self.income(**overrides)), \
                    patch.object(client, "financial_indicators") as indicators:
                result = client.fundamentals("605589.SH")
            self.assertNotIn("financials", result)
            self.assertIn("limitations", result)
            indicators.assert_not_called()

    def test_indicator_object_contract_and_percent_units(self):
        client = self.client()
        data = {"thscode": "605589.SH", "report": "2026-2", "abilities": [
            {"ability": "profitability", "indicators": [
                {"index_id": "index_weighted_avg_roe", "value": "5.2000"},
                {"index_id": "calculate_operating_income_yoy_growth_ratio", "value": "13.72776700"},
                {"index_id": "calculate_parent_holder_net_profit_yoy_growth_ratio", "value": "-10.68026200"},
                {"index_id": "sale_gross_margin", "value": None}]}]}
        # Test through transport decoding: this response deliberately has no data.item.
        client._opener = Mock()
        stream = Mock()
        stream.__enter__ = Mock(return_value=stream)
        stream.__exit__ = Mock(return_value=False)
        stream.read.return_value = json.dumps({"code": 0, "data": data}).encode()
        client._opener.open.return_value = stream
        result = client.financial_indicators("605589.SH", "2026-2")
        self.assertEqual(result["roe"], 5.2)
        self.assertEqual(result["rev_growth"], 13.727767)
        self.assertEqual(result["parent_earnings_growth"], -10.680262)
        self.assertIsNone(result["earnings_growth"])
        self.assertIsNone(result["gross_margin"])
        for wrong in [{**data, "thscode": "600519.SH"}, {**data, "report": "2026-1"},
                      {**data, "abilities": [None]}]:
            with patch.object(client, "_get_data", return_value=wrong), self.assertRaises(hf.HithinkError):
                client.financial_indicators("605589.SH", "2026-2")


if __name__ == "__main__":
    unittest.main()
