"""Immutable index-only historical archive; never opens the business database."""
from __future__ import annotations

from contextlib import closing
from datetime import date
import hashlib
import os
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parent.parent
SOURCE = "legacy_index_archive"


class ArchiveError(RuntimeError):
    pass


def years_ago(day: date, years: int) -> date:
    try:
        return day.replace(year=day.year - years)
    except ValueError:  # February 29 -> February 28, rather than a 365-day estimate.
        return day.replace(year=day.year - years, day=28)


def archive_path() -> Path:
    return Path(os.getenv("INVESTORY_INDEX_ARCHIVE", str(ROOT / "data/index-history.sqlite"))).expanduser().resolve()


def fingerprint() -> str:
    path = archive_path()
    if not path.is_file():
        return "absent"
    stat = path.stat()
    return hashlib.sha256(f"{path}|{stat.st_size}|{stat.st_mtime_ns}".encode()).hexdigest()[:24]


def read(symbol: str, start: date, end: date) -> dict | None:
    """Return only explicitly retained coverage. Missing dates are not fabricated."""
    path = archive_path()
    if not path.is_file():
        return None
    try:
        with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as conn:
            meta = dict(conn.execute("SELECT key,value FROM metadata"))
            if meta.get("format") != "investory-index-archive-v1":
                raise ArchiveError("指数归档版本无效")
            coverage = conn.execute("SELECT start,end FROM coverage WHERE symbol=?", (symbol,)).fetchone()
            if not coverage or start.isoformat() < coverage[0] or end.isoformat() > coverage[1]:
                return None
            rows = conn.execute("SELECT trade_date,open,high,low,close,volume FROM index_prices "
                                "WHERE symbol=? AND trade_date>=? AND trade_date<=? ORDER BY trade_date",
                                (symbol, start.isoformat(), end.isoformat())).fetchall()
        points = [{"date": row[0], **{key: float(row[i + 1]) for i, key in enumerate(("open", "high", "low", "close"))},
                   "volume": float(row[5]) if row[5] is not None else None, "amount": None,
                   "source": SOURCE} for row in rows]
        return {"source": SOURCE, "adjust": "none", "timestamp": None, "points": points,
                "archiveAsOf": meta["asOf"], "volumeBasis": "legacy_provider_units"}
    except (sqlite3.Error, KeyError, ValueError, TypeError) as error:
        raise ArchiveError("指数归档无法读取或格式无效") from error


def create(path: Path, as_of: date) -> sqlite3.Connection:
    """Create a new archive; callers publish it atomically only after verification."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb"):
        pass
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE coverage (symbol TEXT PRIMARY KEY, start TEXT NOT NULL, end TEXT NOT NULL, rows INTEGER NOT NULL);
        CREATE TABLE index_prices (symbol TEXT NOT NULL, trade_date TEXT NOT NULL,
            open TEXT NOT NULL, high TEXT NOT NULL, low TEXT NOT NULL, close TEXT NOT NULL, volume TEXT,
            PRIMARY KEY(symbol,trade_date)) WITHOUT ROWID;
    """)
    conn.executemany("INSERT INTO metadata VALUES (?,?)", [
        ("format", "investory-index-archive-v1"), ("asOf", as_of.isoformat()),
        ("retentionStart", years_ago(as_of, 10).isoformat()),
        ("retentionEndExclusive", years_ago(as_of, 5).isoformat()),
        ("volumeBasis", "legacy_provider_units"),
    ])
    conn.commit()
    return conn
