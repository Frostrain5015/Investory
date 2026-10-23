from contextlib import closing
from datetime import date, datetime, timedelta
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import index_archive as ia
import market_history as mh
import retire_price_store as retirement


class PriceRetirementTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(); self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.conn = sqlite3.connect(":memory:"); self.addCleanup(self.conn.close)
        self.conn.executescript("""
          CREATE TABLE stocks(id INTEGER PRIMARY KEY,symbol TEXT,name TEXT,market TEXT);
          CREATE TABLE stock_prices(id INTEGER PRIMARY KEY AUTOINCREMENT,stock_id INTEGER,trade_date TEXT,
            open NUMERIC,high NUMERIC,low NUMERIC,close NUMERIC,volume INTEGER,UNIQUE(stock_id,trade_date));
          CREATE TABLE transactions(id INTEGER PRIMARY KEY,stock_id INTEGER,quantity INTEGER);
          CREATE TABLE crawl_history(id INTEGER PRIMARY KEY,status TEXT);
          INSERT INTO stocks VALUES(1,'1.600519','茅台','SH'),(2,'000001.SH','上证','SH'),
            (3,'116.00700','腾讯','HK'),(4,'105.AAPL','苹果','US'),(5,'0.000001','平安','SZ'),
            (6,'510300.SH','ETF','SH'),(7,'HSI.HK','恒生','HK');
          INSERT INTO transactions VALUES(1,1,100);
          INSERT INTO crawl_history VALUES(1,'ok');
        """)
        rows = [(1,"2019-01-02"),(1,"2026-10-22"),(2,"2016-10-23"),(2,"2016-10-24"),
                (2,"2019-01-02"),(2,"2021-10-23"),(2,"2021-10-24"),(2,"2026-10-22"),
                (3,"2019-01-02"),(4,"2019-01-02"),(5,"2019-01-02"),(6,"2026-10-22"),(7,"2019-01-02")]
        self.conn.executemany("INSERT INTO stock_prices(stock_id,trade_date,open,high,low,close,volume) VALUES(?,?,10,12,9,11,100)", rows)
        self.conn.commit()
        self.store = retirement.PriceStore(self.conn,"sqlite")
        self.as_of = date(2026,10,24)
        self.backup = self.root/"backup"
        self.archive = self.root/"data/index-history.sqlite"
        self.environment = patch.dict(os.environ,{"INVESTORY_INDEX_ARCHIVE":str(self.archive),"INVESTORY_HISTORY_CACHE_DIR":str(self.root/"cache")})
        self.environment.start(); self.addCleanup(self.environment.stop)

    def prepare(self):
        return retirement.prepare(self.store,self.backup,self.as_of,"fixture")

    def test_scope_archive_delete_restore_preserve_foreign_and_business_data(self):
        before = self.conn.execute("SELECT stock_id,trade_date,close FROM stock_prices ORDER BY stock_id,trade_date").fetchall()
        plan = self.prepare()
        self.assertEqual(plan["retainedRows"],3)
        self.assertEqual(plan["scope"]["rows"],10)
        self.assertFalse(self.archive.exists())
        self.conn.commit()
        result = retirement.apply(self.store,self.backup,"fixture",self.archive)
        self.assertEqual(result["deletedRows"],10)
        self.assertEqual(self.conn.execute("SELECT DISTINCT stock_id FROM stock_prices ORDER BY stock_id").fetchall(),[(3,),(4,),(7,)])
        self.assertEqual(self.conn.execute("SELECT * FROM transactions").fetchall(),[(1,1,100)])
        self.assertEqual(self.conn.execute("SELECT * FROM crawl_history").fetchall(),[(1,"ok")])
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM stocks").fetchone()[0],7)
        self.assertEqual(retirement.compact(self.store,self.backup,"fixture")["state"],"compacted")
        archived = ia.read("000001.SH",date(2019,1,1),date(2019,1,3))
        self.assertEqual(archived["points"][0]["close"],11)
        self.assertIsNone(ia.read("600519.SH",date(2019,1,1),date(2019,1,3)))
        self.assertEqual(retirement.restore(self.store,self.backup,"fixture")["restoredRows"],10)
        after = self.conn.execute("SELECT stock_id,trade_date,close FROM stock_prices ORDER BY stock_id,trade_date").fetchall()
        self.assertEqual(before,after)
        self.assertEqual(retirement.restore(self.store,self.backup,"fixture")["restoredRows"],0)

    def test_inspect_is_read_only_and_calendar_year_boundaries_are_exact(self):
        result = retirement.inspect(self.store,self.as_of)
        self.assertEqual(result["retainStart"],"2016-10-24")
        self.assertEqual(result["retainEndExclusive"],"2021-10-24")
        self.assertEqual(ia.years_ago(date(2024,2,29),5),date(2019,2,28))
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM stock_prices").fetchone()[0],13)
        self.assertFalse(self.backup.exists())

    def test_tampered_backup_prevents_deletion(self):
        self.prepare()
        with (self.backup/"domestic-prices.jsonl.gz").open("ab") as stream: stream.write(b"changed")
        with self.assertRaises(retirement.RetirementError):
            retirement.apply(self.store,self.backup,"fixture",self.archive)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM stock_prices").fetchone()[0],13)

    def test_changed_price_and_wrong_database_prevent_deletion(self):
        self.prepare()
        with self.assertRaises(retirement.RetirementError):
            retirement.apply(self.store,self.backup,"other",self.archive)
        self.conn.execute("UPDATE stock_prices SET close=10.5 WHERE id=1"); self.conn.commit()
        with self.assertRaises(retirement.RetirementError):
            retirement.apply(self.store,self.backup,"fixture",self.archive)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM stock_prices").fetchone()[0],13)

    def test_new_writes_prevent_cleanup(self):
        self.prepare()
        self.conn.execute("INSERT INTO stock_prices(stock_id,trade_date,open,high,low,close,volume) VALUES(1,'2026-10-23',10,12,9,11,100)")
        self.conn.commit()
        with self.assertRaises(retirement.RetirementError):
            retirement.apply(self.store,self.backup,"fixture",self.archive)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM stock_prices").fetchone()[0],14)

    def test_existing_archive_and_conflicting_restore_are_never_overwritten(self):
        self.prepare()
        self.archive.parent.mkdir(); self.archive.write_bytes(b"another archive")
        with self.assertRaises(retirement.RetirementError):
            retirement.apply(self.store,self.backup,"fixture",self.archive)
        self.assertEqual(self.archive.read_bytes(),b"another archive")
        self.archive.unlink(); self.conn.commit()
        retirement.apply(self.store,self.backup,"fixture",self.archive)
        self.conn.execute("INSERT INTO stock_prices(stock_id,trade_date,open,high,low,close,volume) VALUES(1,'2019-01-02',10,12,9,10.5,100)")
        self.conn.commit()
        with self.assertRaises(retirement.RetirementError):
            retirement.restore(self.store,self.backup,"fixture")
        self.assertEqual(self.conn.execute("SELECT close FROM stock_prices WHERE stock_id=1").fetchone()[0],10.5)

    def test_foreign_key_references_stop_retirement(self):
        self.conn.execute("CREATE TABLE reference_test(id INTEGER REFERENCES stock_prices(id))")
        with self.assertRaises(retirement.RetirementError): self.prepare()

    def test_retained_history_works_offline_without_a_price_database(self):
        self.prepare(); self.conn.commit()
        retirement.apply(self.store,self.backup,"fixture",self.archive)
        self.conn.execute("DROP TABLE stock_prices"); self.conn.commit()
        with patch.object(mh,"_network_provider",side_effect=AssertionError("offline")):
            result = mh.history("000001.SH","2019-01-01","2019-01-03","none")
        self.assertEqual(result["points"][0]["close"],11)
        self.assertEqual(result["sources"],[ia.SOURCE])

    def test_index_boundary_uses_archive_and_current_provider_without_duplicates(self):
        self.prepare(); self.conn.commit()
        retirement.apply(self.store,self.backup,"fixture",self.archive)
        clock = datetime
        def network(symbol,start,end,adjust,kind):
            self.assertGreaterEqual(start,date(2021,10,24))
            return {"source":"hithink_finance_api","adjust":"none","points":[
                {"date":start.isoformat(),"open":10,"high":12,"low":9,"close":11,"volume":100}]}
        with patch.object(mh,"datetime",wraps=clock) as mocked, patch.object(mh,"_network_provider",side_effect=network):
            mocked.now.return_value = clock(2026,10,24,tzinfo=mh.SHANGHAI)
            result = mh.history("000001.SH","2021-10-23","2021-10-25","none")
        self.assertEqual([p["date"] for p in result["points"]],["2021-10-23","2021-10-24"])
        self.assertEqual(set(result["sources"]),{ia.SOURCE,"hithink_finance_api"})

    def test_archive_install_invalidates_cached_network_results(self):
        def remote(*args):
            return {"source":"yahoo","adjust":"none","points":[
                {"date":"2019-01-02","open":20,"high":22,"low":19,"close":21,"volume":100}]}
        with patch.object(mh,"_network_provider",side_effect=remote):
            self.assertEqual(mh.history("000001.SH","2019-01-01","2019-01-03","none")["points"][0]["close"],21)
        self.prepare(); self.conn.commit()
        retirement.apply(self.store,self.backup,"fixture",self.archive)
        with patch.object(mh,"_network_provider",side_effect=AssertionError("offline")):
            self.assertEqual(mh.history("000001.SH","2019-01-01","2019-01-03","none")["points"][0]["close"],11)


if __name__ == "__main__": unittest.main()
