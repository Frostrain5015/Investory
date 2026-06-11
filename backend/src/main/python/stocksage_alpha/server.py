#!/usr/bin/env python3
"""
Resident StockSage engine.

Imports the heavy analysis stack (akshare / baostock / pandas / research / factors)
ONCE at process start, then serves the same bridge commands over a local HTTP
socket. This removes the per-call cold start that the subprocess model paid on
every factor/regime/scan request.

Endpoints (all return the same JSON the CLI prints after `RESULT:`):
  GET  /health
  GET  /factor_breakdown?symbol=1.600519
  GET  /regime_status
  GET  /chip_distribution?symbol=1.600519
  GET  /scan_universe?type=main
  GET  /score_stocks?symbols=1.600519,0.000858
  POST /portfolio_analysis           body: {"holdings": "<json string>"}
  POST /prefetch_data                body: {}
  GET  /pick_stocks?strategy=value

Bound to 127.0.0.1:8200 (loopback only). Engine calls are serialized by a lock
because bridge.dispatch() temporarily rebinds a module global (not thread-safe);
/health is lock-free so liveness checks never block on a long scan.
"""

import json
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
sys.path.insert(0, str(SRC))

import bridge  # noqa: E402

PORT = 8200
_lock = threading.Lock()

# In-memory result cache. The heavy cost is per-stock live data fetching
# (~90s for factor_breakdown), not the warm imports. A computed factor/regime
# result is stable for hours, so caching it makes every repeat query that day
# instant — the real latency win on top of warm imports.
_CACHE_TTL = 4 * 3600          # 4h: refreshes a few times per trading day
_CACHEABLE = {"factor_breakdown", "regime_status", "portfolio_analysis",
              "scan_universe", "chip_distribution", "stocksage_report",
              "stock_report", "pick_stocks"}
_cache = {}                    # key -> (epoch, result)
_cache_lock = threading.Lock()


def _cache_get(command, params):
    if command not in _CACHEABLE:
        return None
    key = command + "|" + json.dumps(params, sort_keys=True, ensure_ascii=False)
    with _cache_lock:
        hit = _cache.get(key)
    if hit and (time.time() - hit[0]) < _CACHE_TTL:
        return hit[1]
    return None


def _cache_put(command, params, result):
    # Never cache errors/empty — those should retry.
    if command not in _CACHEABLE or not isinstance(result, dict) or result.get("error"):
        return
    key = command + "|" + json.dumps(params, sort_keys=True, ensure_ascii=False)
    with _cache_lock:
        _cache[key] = (time.time(), result)


def _run(command, params):
    """Cache-aware dispatch: serve from cache, else compute under the engine lock.
    The lock has a short timeout so a long-running command (e.g. pick_stocks)
    doesn't block all other engine requests — they fall back to subprocess."""
    cached = _cache_get(command, params)
    if cached is not None:
        return cached
    if not _lock.acquire(timeout=15):
        return {"error": "engine busy, retry later", "_fallback": True}
    try:
        result = bridge.dispatch(command, params)
    finally:
        _lock.release()
    _cache_put(command, params, result)
    return result


def _warm() -> None:
    """Pre-import the heavy modules so the first request isn't slow."""
    try:
        import fetcher  # noqa: F401
        import research  # noqa: F401
        from factors import DEFAULT_WEIGHTS  # noqa: F401
        print("[stocksage] heavy modules warmed", flush=True)
    except Exception:
        print("[stocksage] warmup failed:\n" + traceback.format_exc(), flush=True)


class Handler(BaseHTTPRequestHandler):
    def _send(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        cmd = u.path.strip("/")
        if cmd == "health":
            return self._send({"status": "ok"})
        params = {k: v[0] for k, v in parse_qs(u.query).items()}
        self._send(_run(cmd, params))

    def do_POST(self):
        u = urlparse(self.path)
        cmd = u.path.strip("/")
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        try:
            params = json.loads(raw) if raw.strip() else {}
        except Exception:
            params = {}
        self._send(_run(cmd, params))

    def log_message(self, *args):
        pass  # stay quiet; engine logs go through bridge/fetcher


if __name__ == "__main__":
    _warm()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"[stocksage] listening on 127.0.0.1:{PORT}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        srv.shutdown()
