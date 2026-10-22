"""Read-only Hithink Financial API transport shared by Guanlan and StockSage.

Configuration is shared with Spring: environment overrides the private
hithink-data.local.properties file. Credentials never enter URLs or errors.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
import json
import math
import os
from pathlib import Path
import re
import threading
import time as clock
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

SHANGHAI = timezone(timedelta(hours=8))
SOURCE = "hithink_finance_api"
ROOT = Path(__file__).resolve().parent.parent


class HithinkError(RuntimeError):
    """Safe, bounded error suitable for tool output and logs."""


@dataclass(frozen=True)
class Settings:
    enabled: bool
    api_key: str = field(repr=False)
    base_url: str = "https://fuyao.aicubes.cn"
    timeout_ms: int = 5000


def load_settings() -> Settings:
    values: dict[str, str] = {}
    # Same order as Spring's optional file imports. No scan of unrelated configs.
    for path in (ROOT / "hithink-data.local.properties", ROOT / "backend/hithink-data.local.properties"):
        if path.is_file():
            for line in path.read_text(encoding="utf-8-sig").splitlines():
                match = re.match(r"^\s*investory\.hithink\.([\w-]+)\s*=\s*(.*?)\s*$", line)
                if match:
                    values[match[1]] = match[2]

    def setting(name: str, default: str) -> str:
        return os.environ.get("HITHINK_FINANCE_" + name.upper().replace("-", "_"), values.get(name, default)).strip()

    base = setting("base-url", "https://fuyao.aicubes.cn").rstrip("/")
    try:
        uri = urlsplit(base)
    except ValueError:
        raise HithinkError("同花顺接口地址配置无效") from None
    if (not uri.hostname or uri.username or uri.password or uri.query or uri.fragment or uri.path
            or not (uri.scheme == "https" or (uri.scheme == "http" and uri.hostname in {"localhost", "127.0.0.1", "::1"}))):
        raise HithinkError("同花顺接口地址配置无效")
    try:
        timeout_ms = int(setting("timeout-ms", "5000"))
        if not 100 <= timeout_ms <= 30000:
            raise ValueError()
    except ValueError:
        raise HithinkError("同花顺请求超时配置无效") from None
    key = setting("api-key", "")
    if any(ord(c) < 32 or ord(c) == 127 for c in key):
        raise HithinkError("同花顺密钥配置无效")
    return Settings(setting("enabled", "false").lower() == "true", key, base, timeout_ms)


def normalize_symbol(value: str) -> str:
    """Accept explicit exchange, Investory DB codes, or bare A-share stock codes."""
    code = value.strip().upper()
    if re.fullmatch(r"\d{6}\.(SH|SZ|BJ)", code):
        return code
    if re.fullmatch(r"[01]\.\d{6}", code):
        digits = code[2:]
        # Investory historically stores BJ stocks with the 0. prefix as well.
        market = "BJ" if digits.startswith(("4", "8", "92")) else ("SH" if code[0] == "1" else "SZ")
        return digits + "." + market
    match = re.fullmatch(r"(SH|SZ|BJ)\.?([0-9]{6})", code)
    if match:
        return match[2] + "." + match[1]
    if re.fullmatch(r"\d{6}", code):
        if code.startswith("6"):
            return code + ".SH"
        if code.startswith(("0", "3")):
            return code + ".SZ"
        if code.startswith(("4", "8", "92")):
            return code + ".BJ"
    raise HithinkError("不支持的 A 股代码")


def number(value: object) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (ValueError, TypeError):
        return None


def timestamp_day(value: object) -> str:
    stamp = number(value)
    if stamp is None:
        raise HithinkError("同花顺返回无效日期")
    try:
        return datetime.fromtimestamp(stamp / 1000, SHANGHAI).date().isoformat()
    except (ValueError, OverflowError, OSError):
        raise HithinkError("同花顺返回无效日期") from None


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, settings: Settings | None = None):
        self.settings = settings or load_settings()
        self._opener = build_opener(ProxyHandler({}), _NoRedirect())
        self._lock = threading.Lock()
        self._last_start = 0.0
        self._blocked_until = 0.0
        self._snapshot_lock = threading.Lock()
        self._snapshots: dict[str, tuple[float, dict | None]] = {}

    def get(self, path: str, params: dict[str, object] | None = None) -> dict:
        cfg = self.settings
        if not cfg.enabled or not cfg.api_key:
            raise HithinkError("请先配置并启用同花顺金融 API")
        # Small shared request budget per Python process; no retry storm in scans.
        with self._lock:
            now = clock.monotonic()
            if now < self._blocked_until:
                raise HithinkError("同花顺接口暂不可用，请稍后重试")
            clock.sleep(max(0, self._last_start + 0.25 - now))
            self._last_start = clock.monotonic()
            request = Request(cfg.base_url + path + ("?" + urlencode(params) if params else ""),
                              headers={"X-api-key": cfg.api_key, "Accept": "application/json"})
            try:
                with self._opener.open(request, timeout=cfg.timeout_ms / 1000) as response:
                    raw = response.read(16 * 1024 * 1024 + 1)
                if len(raw) > 16 * 1024 * 1024:
                    raise HithinkError("同花顺响应超出大小限制")
                payload = json.loads(raw)
                if not isinstance(payload, dict) or type(payload.get("code")) is not int:
                    raise HithinkError("同花顺响应格式无效")
                if payload["code"] != 0:
                    self._blocked_until = clock.monotonic() + 60
                    raise HithinkError(f"同花顺请求失败（业务码 {payload['code']}）")
                data = payload.get("data")
                if not isinstance(data, dict) or not isinstance(data.get("item"), list):
                    raise HithinkError("同花顺响应格式无效")
                if not all(isinstance(row, dict) for row in data["item"]):
                    raise HithinkError("同花顺响应记录无效")
                return data
            except HTTPError as error:
                error.close()
                self._blocked_until = clock.monotonic() + 60
                raise HithinkError("同花顺网络请求失败") from None
            except (URLError, TimeoutError, OSError):
                self._blocked_until = clock.monotonic() + 60
                raise HithinkError("同花顺网络请求失败") from None
            except (ValueError, UnicodeError):
                raise HithinkError("同花顺响应格式无效") from None

    def snapshot(self, symbols: list[str]) -> dict[str, dict]:
        """Bounded A-share batches; aliases share 25s successes and missing rows.

        Unknown values stay null. This method does not turn a batch timestamp
        into a per-security real-time guarantee, or fall back to the SQL table.
        """
        canonical = list(dict.fromkeys(normalize_symbol(symbol) for symbol in symbols))
        if len(canonical) > 500:
            raise HithinkError("单次报价请求最多接受 500 个标的")
        with self._snapshot_lock:
            now = clock.monotonic()
            missing = [code for code in canonical if code not in self._snapshots or now - self._snapshots[code][0] >= 25]
            for start in range(0, len(missing), 50):
                group = missing[start:start + 50]
                started = clock.monotonic()
                data = self.get("/api/a-share/prices/snapshot", {"thscodes": ",".join(group)})
                rows: dict[str, dict] = {}
                for row in data["item"]:
                    code = row.get("thscode")
                    if not isinstance(code, str) or code.upper() not in group:
                        continue
                    code = code.upper()
                    if code in rows:
                        raise HithinkError("同花顺报价包含重复标的")
                    price = number(row.get("last_price"))
                    if price is None or price <= 0:
                        continue
                    previous = number(row.get("prev_price"))
                    change = number(row.get("price_change"))
                    pct = number(row.get("price_change_ratio_pct"))
                    if change is None and previous is not None and previous > 0:
                        change = price - previous
                    if previous is None and change is not None:
                        previous = price - change
                    if pct is None and change is not None and previous is not None and previous > 0:
                        pct = change / previous * 100
                    rows[code] = {"symbol": code, "price": price, "previousClose": previous,
                                  "change": change, "changePct": pct, "source": SOURCE,
                                  "timestamp": data.get("timestamp"), "timestampScope": "BATCH",
                                  "currency": "CNY", "live": False}
                for code in group:
                    self._snapshots[code] = (started, rows.get(code))
            # Hard cap, including entries that represent upstream omissions.
            while len(self._snapshots) > 4096:
                del self._snapshots[next(iter(self._snapshots))]
            return {symbol: dict(self._snapshots[normalize_symbol(symbol)][1])
                    for symbol in symbols if self._snapshots.get(normalize_symbol(symbol), (0, None))[1] is not None}

    def history(self, symbol: str, start: date, end: date, adjust: str = "forward") -> dict:
        symbol = normalize_symbol(symbol)
        if adjust not in {"none", "forward", "backward"} or not 0 <= (end - start).days <= 3650:
            raise HithinkError("日线参数无效，单次范围须在十年内")
        data = self.get("/api/a-share/prices/historical", {
            "thscode": symbol, "interval": "1d", "adjust": adjust,
            "start": int(datetime.combine(start, time(), SHANGHAI).timestamp() * 1000),
            "end": int(datetime.combine(end, time(), SHANGHAI).timestamp() * 1000),
        })
        points = []
        seen: set[str] = set()
        for row in data["item"]:
            day = timestamp_day(row.get("date_ms"))
            if not start.isoformat() <= day <= end.isoformat() or day in seen:
                raise HithinkError("同花顺日线日期重复或超出请求范围")
            seen.add(day)
            point = {name: number(row.get(name + "_price")) for name in ("open", "high", "low", "close")}
            if any(value is None or value <= 0 for value in point.values()):
                raise HithinkError("同花顺日线价格不完整")
            if point["low"] > min(point["open"], point["close"]) or point["high"] < max(point["open"], point["close"]) or point["low"] > point["high"]:
                raise HithinkError("同花顺日线价格关系无效")
            points.append({"date": day, **point, "volume": number(row.get("volume")), "amount": number(row.get("turnover"))})
        return {"symbol": symbol, "source": SOURCE, "adjust": adjust,
                "timestamp": data.get("timestamp"), "points": sorted(points, key=lambda row: row["date"])}

    def valuation(self, symbol: str) -> dict:
        symbol = normalize_symbol(symbol)
        data = self.get("/api/a-share/valuations/snapshot", {"thscodes": symbol})
        rows = [row for row in data["item"] if isinstance(row.get("thscode"), str) and row["thscode"].upper() == symbol]
        if len(rows) != 1:
            raise HithinkError("同花顺未返回该股票的估值快照")
        row = rows[0]
        return {"source": SOURCE, "scope": "snapshot", "timestamp": data.get("timestamp"),
                **{name: number(row.get(remote)) for name, remote in
                   (("pe_ttm", "pe_ttm"), ("pb", "pb_mrq"), ("ps_ttm", "ps_ttm"), ("pcf_ttm", "pcf_ttm"))}}

    def calendar(self) -> list[str]:
        data = self.get("/api/a-share/calendar/trading-days")
        return sorted({timestamp_day(row.get("date_ms")) for row in data["item"]})


_client: Client | None = None
_client_lock = threading.Lock()


def get_client() -> Client:
    global _client
    with _client_lock:
        if _client is None:
            _client = Client()
        return _client
