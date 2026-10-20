"""Offline regression tests: no database, provider API, or market feed required."""

import ast
import configparser
import logging
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "script"))

import db
import fetch_fundamentals as fundamentals
from model_transport import needs_proxy


class SourceIntegrityTest(unittest.TestCase):
    def test_first_party_python_parses_and_has_no_package_shadowing(self):
        roots = [ROOT / "script", ROOT / "backend/src/main/python/stocksage_alpha"]
        checked = 0
        for source_root in roots:
            for source in source_root.rglob("*.py"):
                if any(part in {".venv", "venv", "__pycache__"} for part in source.parts):
                    continue
                with self.subTest(path=source.relative_to(ROOT)):
                    ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
                    self.assertFalse(source.with_suffix("").joinpath("__init__.py").exists(),
                                     "A package shadows this same-name module")
                checked += 1
        self.assertGreater(checked, 30)


class DatabaseConfigTest(unittest.TestCase):
    def test_environment_overrides_ini_and_preserves_port_type(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "config.ini"
            config.write_text("[database]\nhost=ini-host\nport=3307\nuser=reader\n[proxy]\nurl=http://proxy\n")
            with patch.object(db, "CONFIG_FILE", config), patch.dict(os.environ, {"DB_HOST": "env-host"}, clear=True):
                result = db.load_config()
        self.assertEqual(result["db_host"], "env-host")
        self.assertEqual(result["db_port"], 3307)
        self.assertEqual(result["db_user"], "reader")
        self.assertEqual(result["proxy_url"], "http://proxy")

    def test_connection_autocommit_is_explicit(self):
        driver = types.SimpleNamespace(connect=Mock())
        config = {"db_host": "host", "db_port": 3306, "db_name": "db", "db_user": "user", "db_password": "test"}
        with patch.dict(sys.modules, {"pymysql": driver}):
            db.get_conn(config)
            self.assertFalse(driver.connect.call_args.kwargs["autocommit"])
            db.get_conn(config, autocommit=True)
            self.assertTrue(driver.connect.call_args.kwargs["autocommit"])

    def test_proxy_config_does_not_require_valid_database_settings(self):
        from model_transport import get_proxy
        config = configparser.ConfigParser()
        config.read_dict({"database": {"port": "unused"}, "proxy": {"url": " http://proxy "}})
        with patch("model_transport.read_config", return_value=config):
            self.assertEqual(get_proxy(), "http://proxy")


class ModelTransportTest(unittest.TestCase):
    def test_local_and_domestic_endpoints_bypass_proxy(self):
        for url in ["http://127.0.0.1:8200/v1", "http://[::1]:8765", "http://192.168.1.5:8000/v1",
                    "http://localhost:8080", "https://api.deepseek.com/v1", "https://dashscope.aliyuncs.com"]:
            with self.subTest(url=url):
                self.assertFalse(needs_proxy(url))

    def test_foreign_and_lookalike_domains_are_not_misclassified(self):
        for url in ["", "https://api.openai.com/v1", "https://deepseek.com.example.org", "https://example.org/deepseek.com"]:
            with self.subTest(url=url):
                self.assertTrue(needs_proxy(url))


class FundamentalsTest(unittest.TestCase):
    def setUp(self):
        self.config = {"proxy_url": "http://test-proxy", "delay": 0}
        self.log = Mock(spec=logging.Logger)

    def test_market_symbols_and_broken_rows_do_not_stop_remaining_stocks(self):
        ticker = Mock(return_value=types.SimpleNamespace(info={"trailingPE": 12}))
        with patch.dict(sys.modules, {"yfinance": types.SimpleNamespace(Ticker=ticker)}), \
                patch.object(fundamentals, "get_stocks", return_value=[(1, "116.invalid", "bad", "HK"), (2, "116.00700", "Tencent", "HK")]), \
                patch.object(fundamentals, "upsert_fundamentals") as upsert, patch.object(fundamentals.time, "sleep"):
            fundamentals.fetch_yahoo_fundamentals("HK", object(), self.config, False, self.log)
            ticker.assert_called_once_with("0700.HK")
            upsert.assert_called_once()
        ticker.reset_mock()
        with patch.dict(sys.modules, {"yfinance": types.SimpleNamespace(Ticker=ticker)}), \
                patch.object(fundamentals, "get_stocks", return_value=[(3, "105.AAPL", "Apple", "US")]), \
                patch.object(fundamentals, "upsert_fundamentals"), patch.object(fundamentals.time, "sleep"):
            fundamentals.fetch_yahoo_fundamentals("US", object(), self.config, True, self.log)
            ticker.assert_called_once_with("AAPL")

    def test_proxy_environment_is_restored_even_if_loop_is_interrupted(self):
        with patch.dict(os.environ, {"HTTP_PROXY": "original"}, clear=True), \
                patch.dict(sys.modules, {"yfinance": types.SimpleNamespace(Ticker=Mock(side_effect=KeyboardInterrupt))}), \
                patch.object(fundamentals, "get_stocks", return_value=[(1, "105.AAPL", "Apple", "US")]):
            with self.assertRaises(KeyboardInterrupt):
                fundamentals.fetch_yahoo_fundamentals("US", object(), self.config, True, self.log)
            self.assertEqual(os.environ["HTTP_PROXY"], "original")
            self.assertNotIn("HTTPS_PROXY", os.environ)

    def test_dry_run_never_creates_tables_or_commits_and_closes_connection(self):
        connection = Mock()
        with patch.object(sys, "argv", ["fetch_fundamentals.py", "--dry-run", "-m", "us"]), \
                patch.object(fundamentals, "setup_logging", return_value=self.log), \
                patch.object(fundamentals, "load_config", return_value=self.config), \
                patch.object(fundamentals, "get_conn", return_value=connection) as connect, \
                patch.object(fundamentals, "ensure_table") as ensure, \
                patch.object(fundamentals, "fetch_yahoo_fundamentals"):
            fundamentals.main()
            ensure.assert_not_called()
            connection.commit.assert_not_called()
            connection.close.assert_called_once()
            connect.assert_called_once()

    def test_successful_import_commits_once(self):
        connection = Mock()
        with patch.object(sys, "argv", ["fetch_fundamentals.py", "-m", "us"]), \
                patch.object(fundamentals, "setup_logging", return_value=self.log), \
                patch.object(fundamentals, "load_config", return_value=self.config), \
                patch.object(fundamentals, "get_conn", return_value=connection), \
                patch.object(fundamentals, "ensure_table"), \
                patch.object(fundamentals, "fetch_yahoo_fundamentals"):
            fundamentals.main()
            connection.commit.assert_called_once()
            connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
