"""SQL-free historical market data shared by Java, research and backtests.

Raw prices are for the ledger; forward-adjusted prices are for research.
The bounded JSON cache contains public market data only, never account data.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from concurrent.futures import Future
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
from urllib.parse import quote, urlencode
from zoneinfo import ZoneInfo

from hithink_finance import get_client, HithinkError, normalize_symbol, SHANGHAI


class HistoryError(RuntimeError):
    pass


INDEX_YAHOO = {
    "000001.SH": "000001.SS", "000300.SH": "000300.SS", "000905.SH": "000905.SS",
    "399001.SZ": "399001.SZ", "399006.SZ": "399006.SZ", "399300.SZ": "399300.SZ",
    "HSI.HK": "^HSI", "HSCE.HK": "^HSCE", "HSTECH.HK": "HSTECH.HK",
    "GSPC.US": "^GSPC", "DJI.US": "^DJI", "IXIC.US": "^IXIC", "N225.JP": "^N225",
    "KS11.KR": "^KS11", "FTSE.GB": "^FTSE", "GDAXI.DE": "^GDAXI", "FCHI.FR": "^FCHI",
    "TWII.TW": "^TWII", "STI.SG": "^STI", "BSESN.IN": "^BSESN", "AXJO.AU": "^AXJO",
    "GSPTSE.CA": "^GSPTSE", "BVSP.BR": "^BVSP", "DXY.IDX": "DX-Y.NYB",
    "XAU.CMD": "GC=F", "BTC.CCY": "BTC-USD", "CL.CMD": "CL=F",
}
_lock = threading.Lock()
_pending: dict[str, Future] = {}


def canonical(symbol: str) -> str:
    value = symbol.strip().upper()
    if re.fullmatch(r"[01]\.\d{6}", value):
        return normalize_symbol(value)
    if value.startswith("116.") and value[4:].isdigit():
        return f"{int(value[4:]):05d}.HK"
    if re.fullmatch(r"10[0567]\.[A-Z0-9-]+", value):
        return value.split(".", 1)[1] + ".US"
    if not re.fullmatch(r"[A-Z0-9^-]+\.[A-Z]+", value):
        return normalize_symbol(value)
    return value


def instrument_kind(symbol: str) -> str:
    code = canonical(symbol)
    if code in INDEX_YAHOO or code.endswith(".TI") or re.fullmatch(r"399\d{3}\.SZ|000\d{3}\.SH", code):
        return "index"
    if re.fullmatch(r"5\d{5}\.SH|1[568]\d{4}\.SZ", code):
        return "fund"
    if re.fullmatch(r"[68]\d{5}\.SH|[03]\d{5}\.SZ|[489]\d{5}\.BJ", code):
        return "equity"
    return "other"


def _day(value: date | str) -> date:
    return value if isinstance(value, date) else date.fromisoformat(value)


def _validate_points(points: object, start: date, end: date) -> list[dict]:
    if not isinstance(points, list):
        raise HistoryError("历史行情格式无效")
    previous = None
    for point in points:
        if not isinstance(point, dict):
            raise HistoryError("历史行情记录无效")
        day = _day(point["date"])
        if not start <= day <= end or previous is not None and day <= previous:
            raise HistoryError("历史行情日期重复、乱序或超出区间")
        previous = day
        values = [point.get(key) for key in ("open", "high", "low", "close")]
        if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or v <= 0 for v in values):
            raise HistoryError("历史行情价格不完整")
        opened, high, low, close = values
        if low > min(opened, close) or high < max(opened, close) or low > high:
            raise HistoryError("历史行情高低价关系无效")
        for key in ("volume", "amount"):
            value = point.get(key)
            if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0):
                raise HistoryError("历史行情成交量或成交额无效")
    return points


def _hithink(symbol: str, start: date, end: date, adjust: str, kind: str) -> dict:
    client = get_client()
    if kind == "equity":
        # The ten-year limit is per request, not a fabricated ten-year listing age.
        points = []
        cursor = start
        timestamp = None
        while cursor <= end:
            stop = min(end, cursor + timedelta(days=3650))
            result = client.history(symbol, cursor, stop, adjust)
            points.extend(result["points"])
            timestamp = result["timestamp"]
            cursor = stop + timedelta(days=1)
        return {"source": "hithink_finance_api", "adjust": adjust, "timestamp": timestamp, "points": points}
    path = "/api/a-share-index/prices/historical" if kind == "index" else "/api/fund/market/historical"
    data = client.get(path, {"thscode": symbol, "interval": "1d",
                            "start": int(datetime.combine(start, datetime.min.time(), SHANGHAI).timestamp() * 1000),
                            "end": int(datetime.combine(end, datetime.min.time(), SHANGHAI).timestamp() * 1000)})
    points = []
    for row in data["item"]:
        day = datetime.fromtimestamp(row["date_ms"] / 1000, SHANGHAI).date().isoformat()
        points.append({"date": day, **{key: row.get(key + "_price") for key in ("open", "high", "low", "close")},
                       "volume": row.get("volume"), "amount": row.get("turnover")})
    return {"source": "hithink_finance_api", "adjust": "none" if kind == "index" else "forward",
            "timestamp": data.get("timestamp"), "points": points}


def _yahoo_symbol(symbol: str) -> str:
    if symbol in INDEX_YAHOO:
        return INDEX_YAHOO[symbol]
    ticker, market = symbol.rsplit(".", 1)
    if market == "HK" and ticker.isdigit():
        return f"{int(ticker):04d}.HK"
    if market == "US":
        return ticker
    if market in {"SH", "SZ"} and ticker.isdigit():
        return ticker + (".SS" if market == "SH" else ".SZ")
    raise HistoryError("当前网络数据源尚不覆盖该标的的历史行情")


def _yahoo(symbol: str, start: date, end: date, adjust: str) -> dict:
    if adjust == "backward":
        raise HistoryError("该历史数据源不提供后复权，不能替换为其他口径")
    from db import load_config
    raw_equity = adjust == "none" and instrument_kind(symbol) != "index"
    # To undo Yahoo's split normalization we need ALL later split events,
    # including events after the requested end date.
    query_end = datetime.now(SHANGHAI).date() if raw_equity else end
    params = {"period1": int(datetime.combine(start, datetime.min.time(), timezone.utc).timestamp()) - 86400,
              "period2": int(datetime.combine(query_end + timedelta(days=2), datetime.min.time(), timezone.utc).timestamp()),
              "interval": "1d", "events": "div,splits"}
    url = "https://query1.finance.yahoo.com/v8/finance/chart/" + quote(_yahoo_symbol(symbol), safe="") + "?" + urlencode(params)
    command = ["curl", "--silent", "--show-error", "--fail", "--connect-timeout", "3", "--max-time", "7",
               "--max-filesize", "4194304", "-H", "User-Agent: Mozilla/5.0"]
    proxy = load_config().get("proxy_url")
    if proxy:
        command += ["--proxy", proxy]
    try:
        response = subprocess.run(command + [url], capture_output=True, timeout=9)
        if response.returncode or len(response.stdout) > 4 * 1024 * 1024:
            raise HistoryError("备用历史行情网络请求失败")
        root = json.loads(response.stdout)
        chart = root["chart"]
        if chart.get("error") or not chart.get("result"):
            raise HistoryError("备用数据源未返回历史行情")
        series = chart["result"][0]
        zone = ZoneInfo(series["meta"]["exchangeTimezoneName"])
        bars = series["indicators"]["quote"][0]
        adjusted = series["indicators"].get("adjclose", [{}])[0].get("adjclose", [])
        splits = []
        for event in series.get("events", {}).get("splits", {}).values():
            split_day = datetime.fromtimestamp(event["date"], zone).date()
            numerator, denominator = float(event["numerator"]), float(event["denominator"])
            if not all(math.isfinite(v) and v > 0 for v in (numerator, denominator)):
                raise HistoryError("备用数据源拆股事件无效")
            splits.append((split_day, numerator / denominator))
        points = []
        for index, timestamp in enumerate(series.get("timestamp", [])):
            day = datetime.fromtimestamp(timestamp, zone).date()
            if not start <= day <= end:
                continue
            values = {key: bars[key][index] for key in ("open", "high", "low", "close")}
            if all(value is None for value in values.values()):
                continue  # Exchange holidays have no bar, not a zero-price candle.
            ratio = 1
            if adjust == "forward":
                if index >= len(adjusted) or adjusted[index] is None or not values["close"]:
                    raise HistoryError("备用数据源缺少复权价格")
                ratio = adjusted[index] / values["close"]
            elif raw_equity:
                ratio = math.prod(factor for split_day, factor in splits if split_day > day)
            volume = bars.get("volume", [None] * len(series["timestamp"]))[index]
            points.append({"date": day.isoformat(), **{key: value * ratio if value is not None else None for key, value in values.items()},
                           "volume": volume / ratio if raw_equity and volume is not None else volume, "amount": None})
        return {"source": "yahoo", "adjust": adjust, "timestamp": None, "points": points,
                "currency": series["meta"].get("currency"),
                "priceBasis": "split_events_reconstructed_raw" if raw_equity else "index" if adjust == "none" else "provider_adjusted_close"}
    except HistoryError:
        raise
    except (OSError, ValueError, KeyError, IndexError, TypeError, subprocess.TimeoutExpired):
        raise HistoryError("备用历史行情响应无效或请求超时") from None


def _cache_dir() -> Path:
    return Path(os.getenv("INVESTORY_HISTORY_CACHE_DIR", str(Path.home() / ".cache/investory/history")))


def _save(path: Path, result: dict) -> None:
    """Atomic, bounded, best-effort public-data cache; not an account database."""
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = json.dumps(result, ensure_ascii=False, allow_nan=False).encode("utf-8")
        if len(raw) > 4 * 1024 * 1024:
            return
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False, suffix=".tmp") as stream:
            temporary = Path(stream.name)
            stream.write(raw)
        try:
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
        files = sorted((p for p in path.parent.glob("*.json") if re.fullmatch(r"[a-f0-9]{64}", p.stem)), key=lambda p: p.stat().st_mtime)
        size = sum(p.stat().st_size for p in files)
        while files and (len(files) > 2048 or size > 64 * 1024 * 1024):
            old = files.pop(0)
            size -= old.stat().st_size
            old.unlink(missing_ok=True)
    except OSError:
        pass  # An unwritable cache must not disable a working provider.


@contextmanager
def _file_lock(path: Path):
    """OS-owned lock, shared by Java child processes and Python tools.

    Locks are released if a child is killed; there are no stale lease files.
    Lock files use a fixed number of stripes to keep the directory bounded.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as stream:
        if stream.seek(0, 2) == 0:
            stream.write(b"0"); stream.flush()
        deadline = time.monotonic() + 22
        while True:
            try:
                stream.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except (BlockingIOError, OSError):
                if time.monotonic() > deadline:
                    raise HistoryError("历史行情请求繁忙，请稍后重试") from None
                time.sleep(.04)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream, fcntl.LOCK_UN)


def _provider(symbol: str, start: date, end: date, adjust: str, kind: str) -> dict:
    five_years_ago = datetime.now(SHANGHAI).date() - timedelta(days=5 * 365 - 2)
    use_hithink = (kind == "equity" or kind == "index" and symbol.endswith((".SH", ".SZ", ".TI"))
                   and start >= five_years_ago or kind == "fund" and adjust == "forward" and (end - start).days <= 5 * 365)
    # Reserve a slot across ALL local processes, without holding this lock
    # during network I/O. Each historical request costs at least one slot.
    directory = _cache_dir()
    with _file_lock(directory / "rate.lock"):
        slot_file = directory / "rate.slot"
        try:
            previous = float(slot_file.read_text())
        except (OSError, ValueError):
            previous = 0
        now = time.time()
        slot = max(now, previous if previous <= now + 30 else now)
        slot_file.write_text(str(slot + .25))
    if slot > now:
        time.sleep(slot - now)
    try:
        return _hithink(symbol, start, end, adjust, kind) if use_hithink else _yahoo(symbol, start, end, adjust)
    except HithinkError as error:
        raise HistoryError(str(error)) from None


def _missing(start: date, end: date, intervals: list) -> list[tuple[date, date]]:
    cursor, gaps = start, []
    for low, high in sorted((_day(a), _day(b)) for a, b in intervals):
        if high < cursor:
            continue
        if low > end:
            break
        if low > cursor:
            gaps.append((cursor, min(end, low - timedelta(days=1))))
        cursor = max(cursor, high + timedelta(days=1))
    if cursor <= end:
        gaps.append((cursor, end))
    return gaps


def _segmented(symbol: str, start: date, end: date, adjust: str, kind: str, epoch: str) -> dict:
    """Share covered yearly intervals; download only gaps, coalesced across years.

    Raw closed bars have no TTL. Adjusted bars share one daily epoch. The short
    file-lock scope is per instrument/adjustment, never the entire provider.
    """
    directory = _cache_dir()
    lock_key = hashlib.sha256(f"{symbol}|{adjust}|{epoch}|{kind}".encode()).hexdigest()
    with _file_lock(directory / ("stripe-" + str(int(lock_key[:8], 16) % 64) + ".lock")):
        segments, gaps = [], []
        cursor = start
        while cursor <= end:
            stop = min(end, date(cursor.year, 12, 31))
            identity = f"bars-v3|{symbol}|{cursor.year}|{adjust}|{epoch}|{kind}"
            key = hashlib.sha256(identity.encode()).hexdigest()
            path = directory / (key + ".json")
            try:
                result = json.loads(path.read_text(encoding="utf-8"))
                if result["identity"] != identity or result.get("adjust") != adjust or not isinstance(result.get("source"), str):
                    raise ValueError()
                _validate_points(result["points"], date(cursor.year, 1, 1), date(cursor.year, 12, 31))
                if time.time() - result["fetchedAtMs"] / 1000 > 86400:
                    # Nonempty bars are immutable; empty covered intervals are
                    # deliberately retried, never cached as permanent outages.
                    result["coverage"] = [[a, b] for a, b in result["coverage"]
                        if any(a <= p["date"] <= b for p in result["points"])]
            except (OSError, ValueError, KeyError, TypeError, HistoryError):
                result = {"identity": identity, "points": [], "coverage": [], "adjust": adjust}
            result["emptyCoverage"] = [item for item in result.get("emptyCoverage", []) if 0 <= time.time() - item[2] < 86400]
            gaps.extend(_missing(cursor, stop, result["coverage"] + [[a,b] for a,b,stamp in result["emptyCoverage"]]))
            segments.append((cursor, stop, path, result))
            cursor = stop + timedelta(days=1)
        # Joining adjacent cold gaps avoids issuing one request per empty year
        # when a user opens the full history of a recently listed security.
        combined = []
        max_days = 3650 if kind == "equity" else 5 * 365 - 2
        for low, high in sorted(gaps):
            if combined and low == combined[-1][1] + timedelta(days=1) and (high - combined[-1][0]).days <= max_days:
                combined[-1] = (combined[-1][0], high)
            else:
                combined.append((low, high))
        for low, high in combined:
            fetched = _provider(symbol, low, high, adjust, kind)
            if fetched.get("adjust") != adjust or not isinstance(fetched.get("source"), str):
                raise HistoryError("历史行情复权口径或来源无效")
            _validate_points(fetched["points"], low, high)
            for a, b, path, result in segments:
                left, right = max(a, low), min(b, high)
                if left > right:
                    continue
                if result.get("source") and result["source"] != fetched["source"]:
                    raise HistoryError("历史数据源发生变化，需重新核验该区间")
                bars = {p["date"]: p for p in result["points"]}
                selected = {p["date"]: p for p in fetched["points"] if left.isoformat() <= p["date"] <= right.isoformat()}
                bars.update(selected)
                result.update({k: v for k, v in fetched.items() if k != "points"})
                result["points"] = sorted(bars.values(), key=lambda p: p["date"])
                if not selected:
                    result["emptyCoverage"].append([left.isoformat(), right.isoformat(), time.time()])
                intervals = sorted(result["coverage"] + ([[left.isoformat(), right.isoformat()]] if selected else []))
                merged = []
                for c, d in intervals:
                    if merged and _day(c) <= _day(merged[-1][1]) + timedelta(days=1):
                        merged[-1][1] = max(merged[-1][1], d)
                    else:
                        merged.append([c, d])
                result["coverage"] = merged
                result["fetchedAtMs"] = int(time.time() * 1000)
                _save(path, result)
        sources = {item[3].get("source") for item in segments}
        if len(sources) != 1:
            raise HistoryError("历史数据源口径不一致")
        return {**segments[-1][3], "transientEmpty": any(result["emptyCoverage"] for a,b,path,result in segments),
                "points": [p for a, b, path, result in segments for p in result["points"]
                                              if a.isoformat() <= p["date"] <= b.isoformat()]}


def history(symbol: str, start: date | str, end: date | str, adjust: str = "forward") -> dict:
    symbol, start, end = canonical(symbol), _day(start), _day(end)
    if start > end or start < date(1990, 1, 1) or end > datetime.now(SHANGHAI).date() or adjust not in {"none", "forward", "backward"}:
        raise HistoryError("历史行情日期或复权参数无效")
    kind = instrument_kind(symbol)
    effective = "none" if kind == "index" else adjust
    # Adjusted prices have a daily adjustment epoch: a later corporate action
    # may restate old bars. Raw, closed bars have no expiry.
    today = datetime.now(SHANGHAI).date()
    epoch = today.isoformat() if effective != "none" else "raw"
    identity = f"v3|{symbol}|{effective}|{start.isoformat()}|{end.isoformat()}|{epoch}"
    key = hashlib.sha256(identity.encode()).hexdigest()
    path = _cache_dir() / (key + ".json")
    with _lock:
        pending = _pending.get(key)
        owner = pending is None
        if owner:
            pending = Future()
            _pending[key] = pending
    if not owner:
        return json.loads(json.dumps(pending.result(timeout=30)))
    try:
        with _file_lock(_cache_dir() / ("request-" + str(int(key[:8], 16) % 64) + ".lock")):
            now = time.time()
            ttl = 25 if end >= today - timedelta(days=1) else (float("inf") if effective == "none" else 86400)
            try:
                cached = json.loads(path.read_text(encoding="utf-8"))
                age = now - cached["fetchedAtMs"] / 1000
                if cached.get("identity") == identity and 0 <= age < (ttl if cached.get("points") and not cached.get("transientEmpty") else min(ttl, 86400)):
                    if cached.get("adjust") != effective or cached.get("symbol") != symbol:
                        raise HistoryError("缓存历史行情身份不一致")
                    _validate_points(cached["points"], start, end)
                    pending.set_result(cached)
                    return json.loads(json.dumps(cached))
            except (OSError, ValueError, KeyError, TypeError, HistoryError):
                pass
            if kind == "index" and start < today - timedelta(days=5 * 365 - 2):
                kind = "other"  # Keep one provider across older-index ranges.
            parts = []
            frozen_end = min(end, today - timedelta(days=2))
            if start <= frozen_end:
                parts.append(_segmented(symbol, start, frozen_end, effective, kind, epoch))
            if frozen_end < end:
                parts.append(_provider(symbol, max(start, frozen_end + timedelta(days=1)), end, effective, kind))
            if len({part.get("source") for part in parts}) > 1:
                raise HistoryError("请求跨越不同历史数据源，请分区间查询")
            result = {**parts[-1], "transientEmpty": any(part.get("transientEmpty") or not part["points"] for part in parts),
                      "points": [p for part in parts for p in part["points"]]}
            result.update({"identity": identity, "symbol": symbol, "start": start.isoformat(), "end": end.isoformat(),
                           "fetchedAtMs": int(time.time() * 1000)})
            result.setdefault("currency", "CNY" if symbol.endswith((".SH", ".SZ", ".BJ", ".TI")) else "HKD" if symbol.endswith(".HK") else "USD")
            if result.get("adjust") != effective:
                raise HistoryError("历史行情复权口径不一致")
            _validate_points(result["points"], start, end)
            _save(path, result)
            pending.set_result(result)
            return json.loads(json.dumps(result))
    except BaseException as error:
        pending.set_exception(error)
        raise
    finally:
        with _lock:
            _pending.pop(key, None)


def by_id(conn, stock_id: int, start: date | str, end: date | str, adjust: str = "forward") -> dict:
    cur = conn.cursor()
    try:
        cur.execute("SELECT symbol FROM stocks WHERE id=%s", (stock_id,))
        row = cur.fetchone()
    finally:
        cur.close()
    if not row:
        raise HistoryError("未找到股票基础信息")
    return history(row[0], start, end, adjust)


def recent_by_id(conn, stock_id: int, count: int = 253, adjust: str = "forward") -> list[dict]:
    today = datetime.now(SHANGHAI).date()
    return by_id(conn, stock_id, max(date(1990, 1, 1), today - timedelta(days=count * 2 + 30)), today, adjust)["points"][-count:]


def latest(symbols: list[str]) -> dict[str, dict]:
    result = {}
    equities = [symbol for symbol in symbols if instrument_kind(symbol) == "equity"]
    if equities:
        result.update(get_client().snapshot(equities))
    today = datetime.now(SHANGHAI).date()
    def fetch_last(symbol):
        try:
            series = history(symbol, today - timedelta(days=20), today, "none")
            points = series["points"]
            if not points:
                return symbol, None
            last = points[-1]
            previous = points[-2]["close"] if len(points) > 1 else None
            change = last["close"] - previous if previous else None
            value = {"price": last["close"], "previousClose": previous, "change": change,
                              "changePct": change / previous * 100 if previous else None,
                              "source": series["source"], "timestamp": series.get("timestamp"), "date": last["date"], "live": False}
            return symbol, value
        except (HistoryError, HithinkError):
            return symbol, None
    others = [symbol for symbol in dict.fromkeys(symbols) if symbol not in equities]
    if others:
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=8, thread_name_prefix="history-quotes") as workers:
            for symbol, value in workers.map(fetch_last, others):
                if value is not None:
                    result[symbol] = value

    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--adjust", default="forward")
    args = parser.parse_args()
    try:
        print("RESULT:" + json.dumps(history(args.symbol, args.start, args.end, args.adjust), ensure_ascii=False, allow_nan=False))
    except Exception:
        print('RESULT:{"error":"历史行情暂不可用，请检查数据源覆盖、配置及网络"}')
        raise SystemExit(1)
