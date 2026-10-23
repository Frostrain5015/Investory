"""One-off, scoped retirement of domestic price rows; never run on app startup.

Default: inspect only. prepare: verified rollback export + retained index archive.
apply: verify original bytes under a price-table write lock, then delete only
exported domestic rows. restore: restore only exported prices, without touching
user/business tables. Database credentials use the existing private config.
"""
from __future__ import annotations

import argparse
from contextlib import closing, contextmanager
from datetime import date, datetime, timedelta
from decimal import Decimal
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3

from db import get_conn, load_config
from hithink_finance import HithinkError, SHANGHAI
import index_archive
from market_history import canonical, _validate_points


class RetirementError(RuntimeError):
    pass


def encoded(row: tuple) -> bytes:
    def value(item):
        if isinstance(item, (date, datetime)):
            return item.isoformat()
        if isinstance(item, Decimal):
            return str(item)
        raise TypeError(type(item).__name__)
    return (json.dumps(row, default=value, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode()


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class PriceStore:
    """DB-API adapter. SQLite mode supports real offline destructive/restore tests."""
    def __init__(self, conn, dialect: str = "mysql"):
        if dialect not in {"mysql", "sqlite"}:
            raise ValueError("Unsupported database")
        self.conn, self.dialect = conn, dialect

    def execute(self, sql: str, args: tuple = ()) -> list[tuple]:
        with closing(self.conn.cursor()) as cursor:
            cursor.execute(sql if self.dialect == "sqlite" else sql.replace("?", "%s"), args)
            return cursor.fetchall() if cursor.description else []

    def targets(self) -> list[dict]:
        result = []
        for identity, symbol, name, market in self.execute("SELECT id,symbol,name,market FROM stocks ORDER BY id"):
            try:
                code = canonical(symbol)
            except (ValueError, HithinkError):
                code = symbol.upper()
            domestic = code.endswith((".SH", ".SZ", ".BJ", ".TI")) or market in {"SH", "SZ", "BJ"}
            if not domestic:
                continue
            if not isinstance(identity, int) or identity <= 0:
                raise RetirementError("证券身份无效")
            result.append({"id": identity, "symbol": code, "name": name,
                           "index": bool(re.fullmatch(r"000\d{3}\.SH|399\d{3}\.SZ|\d{6}\.TI", code))})
        return result

    def assert_no_price_references(self):
        if self.dialect == "mysql":
            references = self.execute("SELECT table_name,column_name FROM information_schema.key_column_usage "
                                      "WHERE referenced_table_schema=DATABASE() AND referenced_table_name='stock_prices'")
        else:
            references = []
            for (name,) in self.execute("SELECT name FROM sqlite_master WHERE type='table'"):
                if not re.fullmatch(r"[a-z_]+", name):
                    raise RetirementError("表名无效")
                references.extend(row for row in self.execute(f"PRAGMA foreign_key_list({name})") if row[2] == "stock_prices")
        if references:
            raise RetirementError("其他表仍引用行情行，禁止删除或重建价格主键")

    def columns(self) -> list[str]:
        with closing(self.conn.cursor()) as cursor:
            cursor.execute("SELECT * FROM stock_prices WHERE 1=0")
            columns = [item[0] for item in cursor.description]
        if not {"id", "stock_id", "trade_date", "open", "high", "low", "close", "volume"} <= set(columns):
            raise RetirementError("旧行情表结构与清理工具不匹配")
        if not all(re.fullmatch(r"[a-z_]+", name) for name in columns):
            raise RetirementError("旧行情表字段无效")
        return columns

    def stats(self, identities: list[int], high_water: int) -> dict:
        count = 0
        for identity in identities:
            count += self.execute("SELECT COUNT(*) FROM stock_prices WHERE stock_id=? AND id<=?", (identity, high_water))[0][0]
        return {"rows": count, "instruments": len(identities)}

    def rows(self, identities: list[int], high_water: int):
        # Keyset batches keep memory bounded without a long-lived streaming cursor.
        for identity in identities:
            last = 0
            while True:
                rows = self.execute("SELECT * FROM stock_prices WHERE stock_id=? AND id>? AND id<=? ORDER BY id LIMIT 5000",
                                    (identity, last, high_water))
                if not rows:
                    break
                yield from rows
                last = int(rows[-1][self.columns_cached.index("id")])

    @contextmanager
    def price_lock(self):
        if self.dialect == "mysql":
            self.execute("LOCK TABLES stock_prices WRITE")
        else:
            self.conn.execute("BEGIN IMMEDIATE")
        try:
            yield
            self.conn.commit()
        except BaseException:
            self.conn.rollback()
            raise
        finally:
            if self.dialect == "mysql":
                self.execute("UNLOCK TABLES")


def inspect(store: PriceStore, as_of: date) -> dict:
    targets = store.targets()
    maximum = int(store.execute("SELECT COALESCE(MAX(id),0) FROM stock_prices")[0][0])
    low, high = index_archive.years_ago(as_of, 10), index_archive.years_ago(as_of, 5)
    candidates = []
    for item in targets:
        if item["index"]:
            count, first, last = store.execute("SELECT COUNT(*),MIN(trade_date),MAX(trade_date) FROM stock_prices "
                                              "WHERE stock_id=? AND id<=? AND trade_date>=? AND trade_date<?",
                                              (item["id"], maximum, low.isoformat(), high.isoformat()))[0]
            candidates.append({"symbol": item["symbol"], "rows": count,
                               "firstDate": str(first) if first is not None else None,
                               "lastDate": str(last) if last is not None else None})
    return {"format": "investory-price-retirement-v1", "asOf": as_of.isoformat(),
            "retainStart": low.isoformat(), "retainEndExclusive": high.isoformat(),
            "highWaterId": maximum, "targets": targets,
            "retentionCandidates": candidates,
            "scope": store.stats([item["id"] for item in targets], maximum)}


def prepare(store: PriceStore, destination: Path, as_of: date, database: str) -> dict:
    store.assert_no_price_references()
    if (destination / "index-history.sqlite").resolve() == index_archive.archive_path():
        raise RetirementError("备份目录必须与运行归档目录分开")
    destination.mkdir(parents=True, exist_ok=False)
    plan = inspect(store, as_of)
    plan["database"] = database
    columns = store.columns(); store.columns_cached = columns
    plan["columns"] = columns
    archive_file = destination / "index-history.sqlite"
    start, end = date.fromisoformat(plan["retainStart"]), date.fromisoformat(plan["retainEndExclusive"])
    identities = {item["id"]: item for item in plan["targets"]}
    retained, written, digest = {}, 0, hashlib.sha256()
    with closing(index_archive.create(archive_file, as_of)) as archive:
        with gzip.open(destination / "domestic-prices.jsonl.gz", "xb", compresslevel=6) as backup:
            for row in store.rows(list(identities), plan["highWaterId"]):
                payload = encoded(row); backup.write(payload); digest.update(payload); written += 1
                item = dict(zip(columns, row)); security = identities[item["stock_id"]]
                day = date.fromisoformat(str(item["trade_date"]))
                if security["index"] and start <= day < end:
                    point = {"date": day.isoformat(), **{key: float(item[key]) if item[key] is not None else None for key in ("open", "high", "low", "close", "volume")}}
                    _validate_points([point], day, day)
                    symbol = security["symbol"]
                    archive.execute("INSERT INTO index_prices VALUES (?,?,?,?,?,?,?)",
                                    (symbol, day.isoformat(), *(str(item[key]) if item[key] is not None else None for key in ("open", "high", "low", "close", "volume"))))
                    retained[symbol] = retained.get(symbol, 0) + 1
                if written % 100000 == 0:
                    archive.commit()
                    print(json.dumps({"phase": "backup", "rows": written}), flush=True)
        for symbol, count in retained.items():
            archive.execute("INSERT INTO coverage VALUES (?,?,?,?)", (symbol, start.isoformat(), (end-timedelta(days=1)).isoformat(), count))
        archive.commit()
        if archive.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RetirementError("指数归档完整性检查失败")
    if written != plan["scope"]["rows"]:
        raise RetirementError("备份期间数据发生变化，禁止删除")
    if sum(retained.values()) != sum(item["rows"] for item in plan["retentionCandidates"]):
        raise RetirementError("保留区间的源数据与归档行数不匹配，禁止删除")
    plan.update({"backupRows": written, "contentSha256": digest.hexdigest(), "retainedIndices": retained,
                 "retainedRows": sum(retained.values()), "backupSha256": sha256(destination / "domestic-prices.jsonl.gz"),
                 "archiveSha256": sha256(archive_file), "state": "prepared"})
    (destination / "manifest.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
    verify(destination)
    return plan


def verify(directory: Path) -> dict:
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest["format"] != "investory-price-retirement-v1":
        raise RetirementError("备份清单格式无效")
    if sha256(directory / "domestic-prices.jsonl.gz") != manifest["backupSha256"] or sha256(directory / "index-history.sqlite") != manifest["archiveSha256"]:
        raise RetirementError("备份或指数归档校验失败")
    digest, count = hashlib.sha256(), 0
    with gzip.open(directory / "domestic-prices.jsonl.gz", "rb") as backup:
        for line in backup:
            values = json.loads(line)
            if len(values) != len(manifest["columns"]):
                raise RetirementError("备份字段数量不匹配")
            digest.update(line); count += 1
    if count != manifest["backupRows"] or digest.hexdigest() != manifest["contentSha256"]:
        raise RetirementError("备份行数或内容校验失败")
    return manifest


def apply(store: PriceStore, directory: Path, database: str, archive_target: Path) -> dict:
    manifest = verify(directory)
    if manifest["database"] != database:
        raise RetirementError("备份与目标数据库不匹配")
    columns = store.columns(); store.columns_cached = columns
    if columns != manifest["columns"]:
        raise RetirementError("数据库结构已改变，禁止删除")
    identities = [item["id"] for item in manifest["targets"]]
    store.assert_no_price_references()
    current = {item["id"]: item["symbol"] for item in store.targets()}
    if any(current.get(item["id"]) != item["symbol"] for item in manifest["targets"]):
        raise RetirementError("证券身份已改变，禁止删除")
    _install(directory, archive_target, manifest)
    deleted = 0
    with store.price_lock():
        for identity in identities:
            if store.execute("SELECT COUNT(*) FROM stock_prices WHERE stock_id=? AND id>?", (identity, manifest["highWaterId"]))[0][0]:
                raise RetirementError("备份后仍有新行情写入，禁止删除")
        digest, count = hashlib.sha256(), 0
        for row in store.rows(identities, manifest["highWaterId"]):
            digest.update(encoded(row)); count += 1
        if count != manifest["backupRows"] or digest.hexdigest() != manifest["contentSha256"]:
            raise RetirementError("现有行情与备份不一致，禁止删除；可重新prepare")
        # Foreign rows, orphan rows and rows created after the snapshot stay intact.
        for identity in identities:
            while True:
                rows = store.execute("SELECT id FROM stock_prices WHERE stock_id=? AND id<=? ORDER BY id LIMIT 5000",
                                     (identity, manifest["highWaterId"]))
                if not rows:
                    break
                slots = ",".join("?" for _ in rows)
                store.execute(f"DELETE FROM stock_prices WHERE stock_id=? AND id IN ({slots})", (identity, *(row[0] for row in rows)))
                deleted += len(rows)
                if store.dialect == "mysql":
                    store.conn.commit()
        if deleted != count:
            raise RetirementError("实际删除行数不匹配")
        if store.stats(identities, manifest["highWaterId"])["rows"]:
            raise RetirementError("清理后仍有目标行情残留")
    receipt = {"state": "applied", "deletedRows": deleted, "archive": str(archive_target),
               "retainedRows": manifest["retainedRows"], "database": database}
    (directory / "applied.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    return receipt


def _install(directory: Path, archive_target: Path, manifest: dict):
    if archive_target.resolve() == (directory / "index-history.sqlite").resolve():
        raise RetirementError("运行归档不能覆盖备份文件")
    archive_target.parent.mkdir(parents=True, exist_ok=True)
    if archive_target.exists() and sha256(archive_target) != manifest["archiveSha256"]:
        raise RetirementError("目标已有不同指数归档，请先合并，禁止覆盖")
    temporary = archive_target.with_suffix(".installing")
    if temporary.exists():
        raise RetirementError("存在未完成的归档安装")
    shutil.copy2(directory / "index-history.sqlite", temporary)
    os.replace(temporary, archive_target)


def install(directory: Path, archive_target: Path) -> dict:
    manifest = verify(directory)
    _install(directory, archive_target, manifest)
    return {"state": "installed", "retainedRows": manifest["retainedRows"], "archive": str(archive_target)}


def restore(store: PriceStore, directory: Path, database: str) -> dict:
    manifest = verify(directory)
    if manifest["database"] != database or store.columns() != manifest["columns"]:
        raise RetirementError("数据库或结构与备份不匹配")
    restored = 0
    store.assert_no_price_references()
    columns = manifest["columns"]
    names = [name for name in columns if name != "id"]
    # IDs are not referenced by business tables. Use the existing security/date
    # identity; do not overwrite newer data or collide with unrelated auto IDs.
    with store.price_lock():
        with gzip.open(directory / "domestic-prices.jsonl.gz", "rt", encoding="utf-8") as stream:
            for line in stream:
                item = dict(zip(columns, json.loads(line)))
                existing = store.execute("SELECT " + ",".join(names) + " FROM stock_prices WHERE stock_id=? AND trade_date=?",
                                         (item["stock_id"], item["trade_date"]))
                if existing:
                    # Never silently replace a price that has changed since backup.
                    current = existing[0]
                    if any((None if a is None else str(a)) != (None if item[k] is None else str(item[k]))
                           for k, a in zip(names, current)):
                        raise RetirementError("恢复目标已存在不同数据，停止覆盖")
                    continue
                store.execute("INSERT INTO stock_prices (" + ",".join(names) + ") VALUES (" + ",".join("?" for _ in names) + ")",
                              tuple(item[name] for name in names))
                restored += 1
                if store.dialect == "mysql" and restored % 5000 == 0:
                    store.conn.commit()
    return {"state": "restored", "restoredRows": restored}


def compact(store: PriceStore, directory: Path, database: str) -> dict:
    """Explicit maintenance step: reclaim disk space after a verified apply."""
    manifest = verify(directory)
    receipt = json.loads((directory / "applied.json").read_text(encoding="utf-8"))
    if manifest["database"] != database or receipt.get("state") != "applied":
        raise RetirementError("必须先完成同一数据库的清理")
    maximum = int(store.execute("SELECT COALESCE(MAX(id),0) FROM stock_prices")[0][0])
    if store.stats([item["id"] for item in manifest["targets"]], maximum)["rows"]:
        raise RetirementError("目标行情仍存在，禁止重建表")
    store.conn.commit()
    if store.dialect == "mysql":
        result = store.execute("OPTIMIZE TABLE stock_prices")
        if not result or not any(str(row[-2]).lower() == "status" and str(row[-1]).upper() == "OK" for row in result):
            raise RetirementError("行情表空间整理未确认成功")
    else:
        store.conn.execute("VACUUM")
    return {"state": "compacted", "table": "stock_prices"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("inspect", "prepare", "verify", "install", "apply", "restore", "compact"), nargs="?", default="inspect")
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--archive", type=Path, default=index_archive.archive_path())
    parser.add_argument("--as-of", type=date.fromisoformat, default=datetime.now(SHANGHAI).date())
    args = parser.parse_args()
    if args.operation != "inspect" and args.directory is None:
        parser.error("This operation requires --directory")
    if args.operation in {"verify", "install"}:
        result = verify(args.directory) if args.operation == "verify" else install(args.directory, args.archive.resolve())
        print(json.dumps(result, ensure_ascii=False)); return
    cfg = load_config()
    try:
        conn = get_conn(cfg)
        with closing(conn):
            store = PriceStore(conn)
            if args.operation == "inspect":
                result = inspect(store, args.as_of)
            elif args.operation == "prepare":
                result = prepare(store, args.directory, args.as_of, cfg["db_name"])
            elif args.operation == "apply":
                result = apply(store, args.directory, cfg["db_name"], args.archive.resolve())
            elif args.operation == "compact":
                result = compact(store, args.directory, cfg["db_name"])
            else:
                result = restore(store, args.directory, cfg["db_name"])
        print(json.dumps(result, ensure_ascii=False))
    except Exception as error:
        # Connection errors may contain credentials/server internals; expose type only.
        message = str(error) if isinstance(error, RetirementError) else type(error).__name__
        code = error.args[0] if error.args and isinstance(error.args[0], int) else None
        print(json.dumps({"state": "failed", "error": message, "databaseCode": code}, ensure_ascii=False))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
