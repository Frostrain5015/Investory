import concurrent.futures
from datetime import date, datetime, timedelta
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import market_history as mh


def bars(start, end):
    return [{"date": (start + timedelta(days=i)).isoformat(), "open": 10, "high": 12,
             "low": 9, "close": 11 + i / 10000, "volume": 1000, "amount": 11000}
            for i in range((end - start).days + 1)]


def provider(symbol, start, end, adjust, kind):
    return {"source": "fixture", "adjust": adjust, "timestamp": None, "points": bars(start, end)}


class HistoryCacheTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.env = patch.dict(os.environ, {"INVESTORY_HISTORY_CACHE_DIR": self.directory.name})
        self.env.start(); self.addCleanup(self.env.stop)

    def test_equity_fund_index_aliases_do_not_collide(self):
        self.assertEqual(mh.canonical("1.600519"), "600519.SH")
        self.assertEqual(mh.instrument_kind("600519.SH"), "equity")
        self.assertEqual(mh.instrument_kind("510300.SH"), "fund")
        self.assertEqual(mh.instrument_kind("000016.SH"), "index")
        self.assertEqual(mh.instrument_kind("0.000001"), "equity")
        self.assertEqual(mh.instrument_kind("000001.SH"), "index")

    def test_raw_overlaps_download_only_missing_days_and_never_expire(self):
        with patch.object(mh, "_provider", side_effect=provider) as fetch:
            first = mh.history("1.600519", "2025-01-01", "2025-06-30", "none")
            first["points"][0]["close"] = 0
            second = mh.history("600519.SH", "2025-03-01", "2025-08-31", "none")
            self.assertEqual(fetch.call_count, 2)
            self.assertEqual(fetch.call_args.args[1:3], (date(2025,7,1), date(2025,8,31)))
            with patch.object(mh.time, "time", return_value=time.time() + 86400 * 90):
                third = mh.history("600519.SH", "2025-01-01", "2025-06-30", "none")
            self.assertEqual(fetch.call_count, 2)
            self.assertEqual(third["points"][0]["close"], 11)
            self.assertEqual(len(second["points"]), 184)

    def test_today_refreshes_only_two_mutable_days(self):
        today = datetime.now(mh.SHANGHAI).date()
        with patch.object(mh, "_provider", side_effect=provider) as fetch:
            mh.history("600519.SH", today - timedelta(days=100), today, "none")
            self.assertEqual(fetch.call_count, 2)
            future = time.time() + 26
            with patch.object(mh.time, "time", return_value=future):
                mh.history("600519.SH", today - timedelta(days=100), today, "none")
            self.assertEqual(fetch.call_count, 3)
            self.assertEqual(fetch.call_args.args[1:3], (today-timedelta(days=1), today))

    def test_empty_gap_is_not_permanently_frozen_by_existing_nonempty_history(self):
        def sometimes_empty(symbol, start, end, adjust, kind):
            value = provider(symbol,start,end,adjust,kind)
            if start >= date(2025,2,1): value["points"] = []
            return value
        with patch.object(mh, "_provider", side_effect=sometimes_empty):
            mh.history("600519.SH","2025-01-01","2025-01-31","none")
            partial=mh.history("600519.SH","2025-01-01","2025-02-28","none")
            self.assertTrue(partial["transientEmpty"])
        future=time.time()+86401
        with patch.object(mh.time,"time",return_value=future), patch.object(mh,"_provider",side_effect=provider) as fetch:
            complete=mh.history("600519.SH","2025-01-01","2025-02-28","none")
            self.assertEqual(len(complete["points"]),59)
            self.assertEqual(fetch.call_args.args[1:3],(date(2025,2,1),date(2025,2,28)))

    def test_adjusted_history_reuses_overlaps_with_separate_adjustment_keys(self):
        with patch.object(mh, "_provider", side_effect=provider) as fetch:
            mh.history("600519.SH", "2025-01-01", "2025-02-28", "forward")
            mh.history("600519.SH", "2025-02-01", "2025-03-31", "forward")
            self.assertEqual(fetch.call_args.args[1:3], (date(2025,3,1), date(2025,3,31)))
            mh.history("600519.SH", "2025-01-01", "2025-02-28", "none")
            self.assertEqual(fetch.call_count, 3)

    def test_adjustment_epoch_changes_at_midnight(self):
        real = datetime
        with patch.object(mh, "_provider", side_effect=provider) as fetch:
            with patch.object(mh, "datetime", wraps=real) as clock:
                clock.now.return_value = real(2026,10,22,tzinfo=mh.SHANGHAI)
                mh.history("600519.SH", "2025-01-01", "2025-02-28", "forward")
                clock.now.return_value = real(2026,10,23,tzinfo=mh.SHANGHAI)
                mh.history("600519.SH", "2025-01-01", "2025-02-28", "forward")
            self.assertEqual(fetch.call_count, 2)

    def test_cold_full_history_coalesces_years(self):
        with patch.object(mh, "_provider", side_effect=provider) as fetch:
            result = mh.history("600519.SH", "1990-01-01", "2025-12-31", "none")
            self.assertLessEqual(fetch.call_count, 5)
            self.assertEqual(len(result["points"]), (date(2025,12,31)-date(1990,1,1)).days+1)

    def test_concurrent_threads_share_one_request_and_return_independent_results(self):
        def delayed(*args):
            time.sleep(.1)
            return provider(*args)
        with patch.object(mh, "_provider", side_effect=delayed) as fetch, concurrent.futures.ThreadPoolExecutor(5) as pool:
            jobs = [pool.submit(mh.history,"600519.SH","2025-01-01","2025-01-10","none") for _ in range(5)]
            results = [job.result() for job in jobs]
            self.assertEqual(fetch.call_count, 1)
            results[0]["points"][0]["close"] = 0
            self.assertEqual(results[1]["points"][0]["close"], 11)

    def test_processes_share_disk_singleflight(self):
        code = '''import market_history as m, os, time
def fetch(symbol,start,end,adjust,kind):
    with open(os.environ['HISTORY_TEST_COUNTER'],'a') as f: f.write('request\\n')
    time.sleep(.15)
    return {'source':'fixture','adjust':adjust,'points':[{'date':start.isoformat(),'open':10,'high':12,'low':9,'close':11,'volume':100}]}
m._provider=fetch
assert m.history('600519.SH','2025-01-01','2025-01-10','none')['points'][0]['close']==11
'''
        counter = Path(self.directory.name)/"counter.txt"
        env = {**os.environ, "PYTHONPATH": str(Path(mh.__file__).parent), "HISTORY_TEST_COUNTER": str(counter)}
        jobs = [subprocess.Popen([sys.executable,"-X","utf8","-c",code],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE) for _ in range(5)]
        for job in jobs:
            out, error = job.communicate(timeout=15)
            self.assertEqual(job.returncode, 0, error.decode())
        self.assertEqual(counter.read_text().splitlines(), ["request"])

    def test_failure_is_not_cached_and_wrong_adjustment_is_rejected(self):
        with patch.object(mh, "_provider", side_effect=mh.HistoryError("unavailable")):
            with self.assertRaises(mh.HistoryError): mh.history("600519.SH","2025-01-01","2025-01-10","none")
        with patch.object(mh, "_provider", side_effect=provider):
            self.assertTrue(mh.history("600519.SH","2025-01-01","2025-01-10","none")["points"])
        with patch.object(mh, "_provider", return_value={"source":"fixture","adjust":"forward","points":[]}):
            with self.assertRaises(mh.HistoryError): mh.history("000001.SZ","2025-01-01","2025-01-10","none")

    def test_invalid_and_duplicate_bars_rejected(self):
        point = bars(date(2025,1,1), date(2025,1,1))[0]
        for values in ([point, point], [{**point, "close": None}], [{**point, "high": 5}], [{**point, "volume": -1}]):
            with self.assertRaises(mh.HistoryError): mh._validate_points(values, date(2025,1,1), date(2025,1,10))

    def test_yahoo_undoes_splits_after_requested_end(self):
        split = int(datetime(2020,8,31,tzinfo=mh.SHANGHAI).timestamp())
        stamp = int(datetime(2020,8,28,tzinfo=mh.SHANGHAI).timestamp())
        payload = {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Shanghai","currency":"USD"},
            "timestamp":[stamp],"events":{"splits":{"one":{"date":split,"numerator":4,"denominator":1}}},
            "indicators":{"quote":[{"open":[125],"high":[126],"low":[124],"close":[125],"volume":[400]}]}}]}}
        with patch("db.load_config", return_value={}), patch.object(mh.subprocess,"run",return_value=Mock(returncode=0,stdout=json.dumps(payload).encode())):
            result = mh._yahoo("AAPL.US",date(2020,8,28),date(2020,8,28),"none")
        self.assertEqual(result["points"][0]["close"],500)
        self.assertEqual(result["points"][0]["volume"],100)


class NoPriceSqlTest(unittest.TestCase):
    def test_active_modules_have_no_price_table_sql(self):
        root = Path(__file__).resolve().parents[2]
        files = [p for p in (root/"script").glob("*.py") if p.name != "retire_price_store.py"]
        files += list((root/"backend/src/main/java").rglob("*.java"))
        import re
        for path in files:
            with self.subTest(file=path.name):
                self.assertIsNone(re.search(r"(?:FROM|JOIN|UPDATE|INTO|ANALYZE\s+TABLE)\s+stock_prices\b",path.read_text(encoding="utf-8"),re.I))

    def test_backtest_loads_forward_prices_using_only_metadata_sql(self):
        engine = importlib.import_module("backtest_engine")
        conn = Mock()
        points = bars(date(2025,1,1), date(2025,2,15))
        with patch.object(engine,"resolve_symbol",return_value="1.600519"), patch.object(mh,"history",return_value={"points":points,"source":"fixture","adjust":"forward","fetchedAtMs":1}) as fetch:
            result = engine.load_ohlcv(conn,"茅台","2025-01-01","2025-02-15")
        self.assertEqual(result["close"][0],11)
        self.assertEqual(fetch.call_args.args[-1],"forward")
        conn.cursor.assert_not_called()

    def test_no_volume_cannot_silently_become_zero_volume_backtest(self):
        engine = importlib.import_module("backtest_engine")
        points = bars(date(2025,1,1), date(2025,2,15)); points[0]["volume"] = None
        with patch.object(engine,"resolve_symbol",return_value="1.600519"), patch.object(mh,"history",return_value={"points":points}), self.assertRaises(mh.HistoryError):
            engine.load_ohlcv(Mock(),"茅台","2025-01-01","2025-02-15")

    def test_optimizer_intersects_actual_dates_and_keeps_portfolio_members(self):
        optimizer=importlib.import_module("optimizer")
        conn=Mock(); conn.cursor.return_value.fetchone.side_effect=[("600519.SH",),("000001.SZ",)]
        a=bars(date(2025,1,1),date(2025,4,1)); b=bars(date(2025,1,3),date(2025,4,3))
        with patch.object(mh,"recent_by_id",side_effect=[a,b]):
            returns, symbols=optimizer.load_returns(conn,[1,2])
        self.assertEqual(symbols,["600519.SH","000001.SZ"])
        self.assertEqual(returns.shape,(2,88))
        for call in conn.cursor.return_value.execute.call_args_list:
            self.assertNotIn("stock_prices",call.args[0])

    def test_optimizer_refuses_a_partial_portfolio(self):
        optimizer=importlib.import_module("optimizer")
        conn=Mock(); conn.cursor.return_value.fetchone.return_value=("600519.SH",)
        with patch.object(mh,"recent_by_id",side_effect=[bars(date(2025,1,1),date(2025,4,1)),[]]):
            self.assertEqual(optimizer.load_returns(conn,[1,2]),(None,None))


if __name__ == "__main__": unittest.main()
