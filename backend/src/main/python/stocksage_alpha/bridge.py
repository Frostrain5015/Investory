#!/usr/bin/env python3
"""
StockSage Alpha Bridge — unified entry point for Java backend.

Commands:
  score_stocks --symbols 600519,000858    Multi-factor scores
  factor_breakdown --symbol 600519        Per-factor detail
  regime_status                           Market regime detection
  chip_distribution --symbol 600519       Chip/cost distribution
  scan_universe --type main               Full market scan
  prefetch_data                           Warm up caches

All commands output JSON to stdout. Errors are {"error": "message"}.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import sys
import threading
import time
import traceback
from datetime import datetime
from dataclasses import asdict, is_dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
sys.path.insert(0, str(SRC))


def output_json(obj: dict) -> None:
    """Write final result to stdout with RESULT: prefix for Java parsing."""
    sys.stdout.write("RESULT: " + json.dumps(obj, ensure_ascii=False, default=str) + "\n")
    sys.stdout.flush()


_RESEARCH_CACHE_TTL = int(os.environ.get("STOCKSAGE_RESEARCH_CACHE_TTL", str(4 * 3600)))
_research_cache: dict[str, tuple[float, dict]] = {}
_research_cache_lock = threading.Lock()


def _weights_cache_key(weights) -> str:
    try:
        if is_dataclass(weights):
            payload = asdict(weights)
        else:
            payload = getattr(weights, "__dict__", str(weights))
        return json.dumps(payload, sort_keys=True, ensure_ascii=False, default=str)
    except Exception:
        return repr(weights)


def _research_cached(code: str, weights) -> dict:
    """Run research() with an in-process per-stock cache.

    The resident server imports this module once, so this cache is shared by
    factor_breakdown, stock_report, and portfolio_analysis. CLI subprocess calls
    still work normally; they just get a process-local cache for that invocation.
    """
    import fetcher
    from research import research

    norm = fetcher.normalize_code(code)
    key = norm + "|" + _weights_cache_key(weights)
    now = time.time()
    with _research_cache_lock:
        hit = _research_cache.get(key)
        if hit and now - hit[0] < _RESEARCH_CACHE_TTL:
            return copy.deepcopy(hit[1])

    result = research(norm, weights)
    if isinstance(result, dict) and not result.get("error"):
        _patch_value_factor(norm, result)
        with _research_cache_lock:
            _research_cache[key] = (time.time(), copy.deepcopy(result))
    return result


def _patch_value_factor(code: str, r: dict) -> None:
    """Fix value factor score: research() gets PE/PB from realtime quote (returns 0).
    Extract from valuation history instead and re-score."""
    try:
        import fetcher
        from factors import score_value
        import pandas as pd
        val_df = fetcher.get_valuation_history(code)
        if val_df is not None and not val_df.empty and "pe_ttm" in val_df.columns:
            last = val_df.iloc[-1]
            real_pe = float(last["pe_ttm"]) if pd.notna(last["pe_ttm"]) and last["pe_ttm"] > 0 else 0
            real_pb = float(last["pb"]) if "pb" in val_df.columns and pd.notna(last["pb"]) and last["pb"] > 0 else 0
            if real_pe > 0 or real_pb > 0:
                vf = score_value(real_pe, real_pb, val_df)
                if vf.get("score", 0) > 0:
                    r["factors"]["value"] = vf
    except Exception:
        pass


# ----------------------------------------------------------------
# score_stocks — use research() for each symbol
# ----------------------------------------------------------------

def cmd_score_stocks(args):
    symbols = [s.strip() for s in args.symbols.split(",") if s.strip()]
    if not symbols:
        return output_json({"error": "no symbols provided"})

    import fetcher
    from factors import DEFAULT_WEIGHTS

    total = len(symbols)
    results = {}
    for i, symbol in enumerate(symbols):
        # Emit progress line for SSE (matches Java PROGRESS_RE)
        pct = int((i + 1) / total * 100)
        sys.stderr.write(f"[{i+1}/{total} {pct}%] 分析 {symbol}\n")
        sys.stderr.flush()
        try:
            code = fetcher.normalize_code(symbol)
            r = _research_cached(code, DEFAULT_WEIGHTS)
            results[code] = {
                "total_score": round(r.get("total_score", 0), 1),
                "factor_count": len(r.get("factors", {})),
            }
        except Exception as e:
            results[symbol] = {"error": str(e)[:200]}

    output_json({"scores": results})


# ----------------------------------------------------------------
# factor_breakdown — full factor detail via research()
# ----------------------------------------------------------------

def _factor_signal(detail: dict) -> str:
    """Pull a short human-readable signal string out of a factor's details dict."""
    d = detail.get("details", {})
    if isinstance(d, dict):
        for k in ("signal", "scenario", "quality_signal", "vol_signal", "regime_signal"):
            v = d.get(k)
            if v and isinstance(v, str):
                return v[:80]
    return ""


def cmd_factor_breakdown(args):
    symbol = args.symbol.strip()
    try:
        import fetcher
        from factors import DEFAULT_WEIGHTS

        code = fetcher.normalize_code(symbol)
        r = _research_cached(code, DEFAULT_WEIGHTS)

        raw = r.get("factors") or {}
        factors = []          # full per-factor list with max + pct
        for name, detail in raw.items():
            if not isinstance(detail, dict):
                continue
            score = round(float(detail.get("score", 0) or 0), 1)
            mx = float(detail.get("max", 0) or 0)
            pct = round(score / mx * 100, 0) if mx > 0 else None
            factors.append({
                "name": name,
                "group": FACTOR_GROUP_MAP.get(name, "其他"),
                "score": score,
                "max": round(mx, 1),
                "pct": pct,                                      # 0-100, None if no max
                "sell_score": round(float(detail.get("sell_score", 0) or 0), 1),
                "signal": _factor_signal(name, detail),
            })

        output_json({
            "symbol": code,
            "total_score": round(r.get("total_score", 0), 1),
            "factors": factors,
        })
    except Exception:
        output_json({"error": traceback.format_exc()[-400:]})


# ----------------------------------------------------------------
# regime_status — fetch CSI300 data and score market regime
# ----------------------------------------------------------------

def cmd_regime_status(_args=None):
    try:
        import fetcher
        from factors import score_market_regime

        mkt_data = fetcher.get_market_regime_data()
        if mkt_data is None or (hasattr(mkt_data, 'empty') and mkt_data.empty):
            return output_json({
                "regime": {"signal": "unknown", "score": 5.0,
                           "description": "无法获取市场数据",
                           "timestamp": datetime.now().isoformat()}
            })

        mkt = score_market_regime(mkt_data)

        # score_market_regime returns {signal, score, sell_score, index_close, ma5, ma20, ma60, ...}
        raw_signal = mkt.get("signal", "") if isinstance(mkt, dict) else ""
        score = float(mkt.get("score", 5.0)) if isinstance(mkt, dict) else 5.0

        # Classify by score (0-10 scale from score_market_regime)
        if score >= 8:
            signal = "BULL"
        elif score >= 6:
            signal = "BULL"
        elif score >= 4:
            signal = "NORMAL"
        elif score >= 2:
            signal = "CAUTION"
        else:
            signal = "BEAR"

        from factors.config import REGIME_EXPOSURE
        exposure = REGIME_EXPOSURE.get(signal, 0.85)

        descriptions = {
            "NORMAL": "正常市场", "CAUTION": "谨慎（20日跌幅 > 3%）",
            "CRISIS": "危机（20日跌幅 > 6%）", "BULL": "牛市（20日涨幅 > 2.5%）",
            "EXTREME_BULL": "极端牛市（20日涨幅 > 6%）",
            "BEAR": "熊市（CSI300 < MA60）",
        }

        # Include indicators for display
        indicators = {k: v for k, v in (mkt.items() if isinstance(mkt, dict) else {})
                      if k not in ("details",) and isinstance(v, (int, float, str, bool))}

        output_json({
            "regime": {
                "signal": signal, "score": round(score, 1),
                "exposure": exposure,
                "description": descriptions.get(signal, raw_signal),
                "indicators": indicators,
                "timestamp": datetime.now().isoformat(),
            }
        })
    except Exception:
        output_json({"error": traceback.format_exc()[-400:]})


# ----------------------------------------------------------------
# chip_distribution — CYQ chip analysis from 同花顺
# ----------------------------------------------------------------

def cmd_chip_distribution(args):
    symbol = args.symbol.strip()
    try:
        import fetcher
        code = fetcher.normalize_code(symbol)
        df = fetcher.get_cyq(code)
        if df is not None and not (hasattr(df, 'empty') and df.empty):
            # Convert DataFrame to list of dicts
            chip_list = df.to_dict(orient="records") if hasattr(df, 'to_dict') else []
            output_json({"symbol": code, "chip": chip_list})
        else:
            output_json({"symbol": code, "chip": [], "message": "暂无筹码数据"})
    except Exception:
        output_json({"error": traceback.format_exc()[-400:]})


# ----------------------------------------------------------------
# scan_universe — full market scan using main strategy pipeline
# ----------------------------------------------------------------

def cmd_scan_universe(args):
    scan_type = args.type or "main"
    try:
        if scan_type == "main":
            return _scan_main()
        elif scan_type == "golden_cross":
            return _scan_technical_resonance()
        elif scan_type == "hot":
            return _scan_hot_list()
        elif scan_type == "chip":
            return _scan_chip_concentration()
        else:
            return output_json({"error": f"unknown scan type: {scan_type}"})
    except Exception:
        output_json({"error": traceback.format_exc()[-500:]})


def _scan_main():
    import fetcher
    from factors.config import REGIME_WEIGHTS
    from strategies._scoring import score_universe, filter_buys
    from report.utils import regime_key

    # Load universe (use small set for testing, full set from data/universe_main.json)
    universe_file = ROOT / "data" / "universe_main.json"
    if universe_file.exists():
        universe = json.loads(universe_file.read_text(encoding="utf-8"))
    else:
        # Fallback: use CSI300 components if no universe file
        import akshare as ak
        try:
            df = ak.index_stock_cons_csindex(symbol="000300")
            universe = [fetcher.normalize_code(c) for c in df["成分券代码"].tolist()[:20]]
        except Exception:
            return output_json({"error": "无法加载股票池，请先配置 data/universe_main.json"})

    universe = [fetcher.normalize_code(c) for c in universe]

    # Regime
    try:
        from factors import score_market_regime
        mkt = score_market_regime(fetcher.get_market_regime_data())
        regime_score = mkt.get("score", 5.0) if isinstance(mkt, dict) else 5.0
    except Exception:
        regime_score = 5.0

    rk = regime_key(regime_score)
    print(f"[bridge] scanning {len(universe)} stocks, regime={rk}", flush=True)

    scored = score_universe(universe, REGIME_WEIGHTS[rk], max_workers=8)
    top_n = 10

    # Filter for buy signals
    buy_alerts = filter_buys(
        scored[:top_n * 3],
        buy_trig=65, sell_guard=50, top_n=top_n,
    )

    picks = []
    for b in buy_alerts:
        picks.append({
            "code": b["code"], "name": b.get("name", b["code"]),
            "buy_score": round(b.get("buy_score", 0), 1),
            "sell_score": round(b.get("sell_score", 0), 1),
            "total_score": round(b.get("total_score", 0), 1),
            "bullish": b.get("bullish", []),
            "bearish": b.get("bearish", []),
        })

    candidates = []
    for s in scored[:20]:
        if not s.get("error") and s.get("buy_score", 0) > 0:
            candidates.append({
                "code": s["code"], "name": s.get("name", s["code"]),
                "buy_score": round(s.get("buy_score", 0), 1),
                "sell_score": round(s.get("sell_score", 0), 1),
                "total_score": round(s.get("total_score", 0), 1),
            })

    output_json({
        "regime_score": round(regime_score, 1),
        "regime": rk,
        "picks": picks,
        "candidates": candidates,
        "scanned": len(universe),
        "timestamp": datetime.now().isoformat(),
    })


def _load_universe_fallback(limit: int = 50) -> list[str]:
    universe_file = ROOT / "data" / "universe_main.json"
    if universe_file.exists():
        return json.loads(universe_file.read_text(encoding="utf-8"))[:limit]
    try:
        import akshare as ak
        df = ak.index_stock_cons_csindex(symbol="000300")
        import fetcher
        return [fetcher.normalize_code(str(c)) for c in df["成分券代码"].tolist()[:limit]]
    except Exception:
        return []


def _format_scan_output(buy_alerts: list, scored: list, strategy_name: str) -> None:
    picks = [{"code": b["code"], "name": b.get("name", b["code"]),
              "total_score": round(b.get("total_score", 0), 1),
              "buy_score": round(b.get("buy_score", 0), 1),
              "bullish": b.get("bullish", [])[:3]}
             for b in buy_alerts]
    output_json({"strategy": strategy_name, "picks": picks,
                 "scanned": len(scored), "timestamp": datetime.now().isoformat()})


def _scan_technical_resonance():
    import fetcher
    from factors.config import REGIME_WEIGHTS
    from strategies._scoring import score_universe
    tech_weights = {k: max(v, 1.5) for k, v in REGIME_WEIGHTS["NORMAL"].items()
                    if k in ("volume", "divergence", "rsi_signal", "macd_signal",
                             "ma_alignment", "ma60_deviation", "hammer_bottom",
                             "gap_frequency", "atr_normalized", "volume_ratio")}
    universe = [fetcher.normalize_code(c) for c in _load_universe_fallback(50)]
    scored = score_universe(universe, tech_weights, max_workers=8)
    alerts = [s for s in scored[:20] if not s.get("error") and s.get("total_score", 0) >= 50][:10]
    _format_scan_output(alerts, scored, "golden_cross")


def _scan_hot_list():
    import fetcher
    from factors.config import FACTOR_WEIGHTS_BULL
    from strategies._scoring import score_universe
    try:
        import akshare as ak
        df = ak.stock_hot_rank_em()
        hot_codes = [fetcher.normalize_code(str(c)) for c in df["代码"].tolist()[:20]]
    except Exception:
        return output_json({"error": "无法获取热榜数据"})
    hot_weights = dict(FACTOR_WEIGHTS_BULL)
    for k in ("momentum", "medium_term_momentum", "volume", "main_inflow"):
        hot_weights[k] = max(hot_weights.get(k, 0), 2.0)
    scored = score_universe(hot_codes, hot_weights, max_workers=8)
    alerts = [s for s in scored[:15] if not s.get("error") and s.get("total_score", 0) >= 40][:10]
    _format_scan_output(alerts, scored, "hot")


def _scan_chip_concentration():
    import fetcher
    from factors.config import REGIME_WEIGHTS
    from strategies._scoring import score_universe
    chip_weights = dict(REGIME_WEIGHTS.get("CAUTION", REGIME_WEIGHTS["NORMAL"]))
    for k in ("chip_distribution", "overhead_resistance", "position_52w", "return_skewness", "div_yield"):
        chip_weights[k] = max(chip_weights.get(k, 0), 2.0)
    universe = [fetcher.normalize_code(c) for c in _load_universe_fallback(50)]
    scored = score_universe(universe, chip_weights, max_workers=8)
    alerts = [s for s in scored[:20] if not s.get("error") and s.get("total_score", 0) >= 45][:10]
    _format_scan_output(alerts, scored, "chip")


# ----------------------------------------------------------------
# portfolio_analysis — aggregate factor scores for a portfolio
# ----------------------------------------------------------------

# Map 51 individual factor names to 7 display groups for the frontend
FACTOR_GROUP_MAP = {
    "value": "价值", "growth": "成长", "roe_trend": "成长",
    "momentum": "动量", "medium_term_momentum": "动量", "return_skewness": "动量",
    "reversal": "动量", "max_return": "动量", "nearness_to_high": "动量",
    "price_inertia": "动量",
    "quality": "质量", "piotroski": "质量", "cash_flow_quality": "质量",
    "accruals": "质量", "asset_growth": "质量", "div_yield": "质量",
    "earnings_revision": "质量",
    "volume": "技术", "rsi_signal": "技术", "macd_signal": "技术",
    "ma_alignment": "技术", "ma60_deviation": "技术", "atr_normalized": "技术",
    "low_volatility": "技术", "idiosyncratic_vol": "技术", "divergence": "技术",
    "hammer_bottom": "技术", "gap_frequency": "技术", "volume_ratio": "技术",
    "upday_ratio": "技术", "bollinger_position": "技术",
    "limit_hits": "事件", "limit_open_rate": "事件", "chip_distribution": "事件",
    "shareholder_change": "事件", "insider": "事件", "lockup_pressure": "事件",
    "northbound": "资金", "northbound_actual": "资金", "main_inflow": "资金",
    "turnover_percentile": "资金", "turnover_acceleration": "资金",
    "social_heat": "情绪", "concept_momentum": "情绪",
    "industry_momentum": "情绪", "institutional_visits": "情绪", "lhb": "情绪",
    "market_regime": "风控", "amihud_illiquidity": "风控",
    "short_interest": "风控", "price_volume_corr": "风控",
    "position_52w": "风控",
}


def cmd_portfolio_analysis(args):
    """Analyse a portfolio: research each holding, aggregate by weight."""
    # Read holdings from file to avoid shell escaping issues (--holdings @/path/to/file.json)
    holdings_path = args.holdings
    if holdings_path.startswith("@"):
        try:
            holdings = json.loads(Path(holdings_path[1:]).read_text(encoding="utf-8"))
        except Exception as e:
            return output_json({"error": f"cannot read holdings file: {e}"})
    else:
        try:
            holdings = json.loads(holdings_path)
        except Exception:
            return output_json({"error": "invalid holdings JSON"})

    if not holdings:
        return output_json({"error": "no holdings provided"})

    import fetcher
    from factors import DEFAULT_WEIGHTS

    total_weight = sum(float(h.get("weight", 0)) for h in holdings)
    if total_weight <= 0:
        return output_json({"error": "zero total weight"})

    # Aggregate factor scores
    factor_totals: dict[str, dict] = {}  # factor_name -> {buy_sum, sell_sum, group}
    group_totals: dict[str, dict] = {}   # group -> {buy_sum, sell_sum, count}
    holding_scores = []
    total_score_sum = 0.0

    for i, h in enumerate(holdings):
        sym = h.get("symbol", "")
        weight = float(h.get("weight", 30))
        name = h.get("name", sym)

        pct = int((i + 1) / len(holdings) * 100)
        sys.stderr.write(f"[{i + 1}/{len(holdings)} {pct}%] 分析 {sym}\n")
        sys.stderr.flush()

        try:
            # Strip "1." / "0." exchange prefix before normalize_code
            code = fetcher.normalize_code(sym.split(".")[-1] if "." in sym else sym)
            r = _research_cached(code, DEFAULT_WEIGHTS)
            ts = round(r.get("total_score", 0), 1)

            # research() returns factors as {group_name: {score, sell_score, max, details}}
            # The key IS the factor group (value/growth/momentum/quality/northbound/...)
            fbuy_sum = 0.0
            fsell_sum = 0.0
            for fname, fdetail in (r.get("factors") or {}).items():
                if not isinstance(fdetail, dict):
                    continue
                fbuy = float(fdetail.get("score", 0) or 0)
                fsell = float(fdetail.get("sell_score", 0) or 0)
                fbuy_sum += fbuy
                fsell_sum += fsell
                display_group = FACTOR_GROUP_MAP.get(fname, "其他")

                if fname not in factor_totals:
                    factor_totals[fname] = {"buy_sum": 0, "sell_sum": 0, "group": display_group}
                factor_totals[fname]["buy_sum"] += fbuy * weight
                factor_totals[fname]["sell_sum"] += fsell * weight

                if display_group not in group_totals:
                    group_totals[display_group] = {"buy_sum": 0, "sell_sum": 0, "count": 0}
                group_totals[display_group]["buy_sum"] += fbuy * weight
                group_totals[display_group]["sell_sum"] += fsell * weight
                group_totals[display_group]["count"] += 1

            total_score_sum += ts * weight
            holding_scores.append({
                "symbol": code, "name": name, "weight": round(weight, 1),
                "total_score": ts, "factor_count": len(r.get("factors", {})),
            })
        except Exception as e:
            holding_scores.append({"symbol": sym, "name": name, "weight": round(weight, 1), "error": str(e)[:100]})

    # Normalize by total weight
    factor_exposure = {}
    for fname, fdata in factor_totals.items():
        factor_exposure[fname] = {
            "group": fdata["group"],
            "buy_score": round(fdata["buy_sum"] / total_weight, 1),
            "sell_score": round(fdata["sell_sum"] / total_weight, 1),
        }

    group_exposure = {}
    for gname, gdata in group_totals.items():
        group_exposure[gname] = {
            "buy_score": round(gdata["buy_sum"] / total_weight, 1),
            "sell_score": round(gdata["sell_sum"] / total_weight, 1),
        }

    # Sort holdings by score desc
    holding_scores.sort(key=lambda x: x.get("total_score", 0), reverse=True)
    top = [h for h in holding_scores if "error" not in h][:3]
    # Bottom: lowest scores, ascending (worst first)
    bottom_all = [h for h in holding_scores if "error" not in h]
    bottom = list(reversed(bottom_all[-3:]))

    output_json({
        "portfolio_score": round(total_score_sum / total_weight, 1) if total_weight > 0 else 0.0,
        "holdings_scored": len([h for h in holding_scores if "error" not in h]),
        "holdings_total": len(holdings),
        "factor_exposure": factor_exposure,
        "group_exposure": group_exposure,
        "top_holdings": top,
        "bottom_holdings": bottom,
        "all_holdings": holding_scores,
        "timestamp": datetime.now().isoformat(),
    })


# ----------------------------------------------------------------
# prefetch_data — warm up caches
# ----------------------------------------------------------------

def cmd_prefetch_data(_args=None):
    """Pre-warm akshare caches for CSI300 universe + regime data."""
    import fetcher
    from research import research
    from factors import DEFAULT_WEIGHTS

    universe = _load_universe_fallback(30)
    total = len(universe)
    results = {"ok": 0, "fail": 0}

    for i, code in enumerate(universe):
        code = fetcher.normalize_code(code)
        pct = int((i + 1) / max(total, 1) * 100)
        sys.stderr.write(f"[{i + 1}/{total} {pct}%] 预热 {code}\n")
        sys.stderr.flush()
        try:
            _research_cached(code, DEFAULT_WEIGHTS)  # populates .cache/
            results["ok"] += 1
        except Exception:
            results["fail"] += 1

    # Also warm regime data
    try:
        fetcher.get_market_regime_data()
    except Exception:
        pass

    output_json({
        "status": "ok",
        "cached": results["ok"],
        "failed": results["fail"],
        "total": total,
        "timestamp": datetime.now().isoformat(),
    })


# ----------------------------------------------------------------
# stocksage_report — auditable report artifacts for Guanlan/Investory
# ----------------------------------------------------------------

def _factor_pct(factor: dict) -> float:
    mx = float(factor.get("max", 0) or 0)
    if mx <= 0:
        return 0.0
    return round(float(factor.get("score", 0) or 0) / mx * 100, 1)


def _factor_sell_pct(factor: dict) -> float:
    mx = float(factor.get("max", 0) or 0)
    if mx <= 0:
        return 0.0
    return round(float(factor.get("sell_score", 0) or 0) / mx * 100, 1)


def _factor_signal(name: str, factor: dict) -> str:
    details = factor.get("details") if isinstance(factor, dict) else {}
    if isinstance(details, dict):
        for key in ("signal", "scenario", "quality_signal", "vol_signal", "regime_signal"):
            value = details.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return name


def _factor_row(name: str, factor: dict, side: str) -> dict:
    score_key = "sell_score" if side == "against" else "score"
    pct = _factor_sell_pct(factor) if side == "against" else _factor_pct(factor)
    return {
        "factor": name,
        "group": FACTOR_GROUP_MAP.get(name, "其他"),
        "score": round(float(factor.get(score_key, 0) or 0), 1),
        "max": round(float(factor.get("max", 0) or 0), 1),
        "pct": pct,
        "signal": _factor_signal(name, factor),
    }


def _is_missing_factor(factor: dict) -> bool:
    if not isinstance(factor, dict):
        return True
    details = factor.get("details")
    score = float(factor.get("score", 0) or 0)
    sell = float(factor.get("sell_score", 0) or 0)
    return score == 0 and sell == 0 and (not isinstance(details, dict) or not details)


def _regime_label_from_score(score: float | None) -> str:
    if score is None:
        return "UNKNOWN"
    if score >= 8:
        return "BULL"
    if score >= 4:
        return "NORMAL"
    if score >= 2:
        return "CAUTION"
    return "BEAR"


def _build_stock_report(symbol: str) -> dict:
    import fetcher
    from factors import DEFAULT_WEIGHTS

    code = fetcher.normalize_code(symbol)
    result = _research_cached(code, DEFAULT_WEIGHTS)
    if not isinstance(result, dict) or result.get("error"):
        return {"error": result.get("error", "report generation failed") if isinstance(result, dict) else "report generation failed"}

    factors = result.get("factors") or {}
    raw_factor_rows = []
    missing = []
    for name, factor in factors.items():
        if not isinstance(factor, dict):
            continue
        if _is_missing_factor(factor):
            missing.append(name)
        raw_factor_rows.append({
            "factor": name,
            "group": FACTOR_GROUP_MAP.get(name, "其他"),
            "score": round(float(factor.get("score", 0) or 0), 1),
            "sell_score": round(float(factor.get("sell_score", 0) or 0), 1),
            "max": round(float(factor.get("max", 0) or 0), 1),
            "pct": _factor_pct(factor),
            "sell_pct": _factor_sell_pct(factor),
            "signal": _factor_signal(name, factor),
            "details": factor.get("details") if isinstance(factor.get("details"), dict) else {},
        })

    evidence_for = sorted(
        [_factor_row(name, f, "for") for name, f in factors.items()
         if isinstance(f, dict) and float(f.get("score", 0) or 0) > 0],
        key=lambda row: (-row["pct"], -row["score"]),
    )[:8]
    evidence_against = sorted(
        [_factor_row(name, f, "against") for name, f in factors.items()
         if isinstance(f, dict) and float(f.get("sell_score", 0) or 0) > 0],
        key=lambda row: (-row["pct"], -row["score"]),
    )[:8]

    while len(evidence_for) < 3 and len(raw_factor_rows) > len(evidence_for):
        candidate = sorted(raw_factor_rows, key=lambda row: (-row["pct"], -row["score"]))[len(evidence_for)]
        evidence_for.append({k: candidate[k] for k in ("factor", "group", "score", "max", "pct", "signal")})
    while len(evidence_against) < 2 and len(raw_factor_rows) > len(evidence_against):
        candidate = sorted(raw_factor_rows, key=lambda row: (-row["sell_pct"], -row["sell_score"]))[len(evidence_against)]
        evidence_against.append({
            "factor": candidate["factor"],
            "group": candidate["group"],
            "score": candidate["sell_score"],
            "max": candidate["max"],
            "pct": candidate["sell_pct"],
            "signal": candidate["signal"],
        })

    market_factor = factors.get("market_regime") or {}
    regime_score = None
    if isinstance(market_factor, dict):
        try:
            regime_score = float(market_factor.get("score", 0) or 0)
        except Exception:
            regime_score = None
    regime = _regime_label_from_score(regime_score)
    total_score = round(float(result.get("total_score", 0) or 0), 1)
    sell_score = round(float(result.get("total_sell_score", 0) or 0), 1)
    conflicted = total_score >= 60 and sell_score >= 35
    conflicts = []
    if conflicted:
        conflicts.append({
            "type": "buy_sell_conflict",
            "message": f"综合机会分较高({total_score})，但风险/卖出压力也不低({sell_score})，不能按单一分数解释。",
        })
    if evidence_for and evidence_against:
        for_signal = evidence_for[0].get("signal", "")
        against_signal = evidence_against[0].get("signal", "")
        if for_signal and against_signal:
            conflicts.append({
                "type": "mixed_evidence",
                "message": f"最强看多证据是「{for_signal}」，同时最强风险证据是「{against_signal}」。",
            })

    regime_message = "市场环境中性，报告不额外放大或折扣风险。"
    if regime in ("CAUTION", "CRISIS", "BEAR"):
        regime_message = f"当前环境偏防御({regime})，高动量和高波动证据需要折扣，仓位判断应更保守。"
    elif regime == "BULL":
        regime_message = "当前环境偏强，趋势和资金类证据可获得更高解释权重，但仍需检查拥挤与回撤风险。"

    title = f"StockSage 个股审计报告 · {result.get('name') or code}({code})"
    summary = {
        "title": title,
        "symbol": code,
        "name": result.get("name", ""),
        "industry": (result.get("basic") or {}).get("industry", "Unknown"),
        "overall_view": "conflicted" if conflicted else ("constructive" if total_score >= 60 else "cautious" if sell_score >= 35 else "neutral"),
        "opportunity_score": total_score,
        "risk_score": sell_score,
        "regime": regime,
        "generated_at": datetime.now().isoformat(),
    }

    markdown_lines = [
        f"# {title}",
        "",
        f"- 结论状态：{summary['overall_view']}",
        f"- 行业：{summary['industry']}",
        f"- 市场环境：{regime}（环境分 {regime_score if regime_score is not None else 'N/A'}）",
        f"- 环境解释：{regime_message}",
        "",
        "## 看多证据",
        *[f"- [{row['group']}] {row['signal']}（贡献 {row['score']}/{row['max']}）" for row in evidence_for[:5]],
        "",
        "## 风险证据",
        *[f"- [{row['group']}] {row['signal']}（风险 {row['score']}/{row['max']}）" for row in evidence_against[:5]],
    ]
    if conflicts:
        markdown_lines += ["", "## 冲突与限制", *[f"- {c['message']}" for c in conflicts]]
    if missing:
        markdown_lines += ["", "## 数据缺失", *[f"- {name}" for name in missing[:12]]]

    report = {
        "report_type": "stock_report",
        "title": title,
        "summary": summary,
        "evidence_for": evidence_for,
        "evidence_against": evidence_against,
        "conflicts": conflicts,
        "regime_adjustment": {
            "regime": regime,
            "score": regime_score,
            "message": regime_message,
        },
        "data_quality": {
            "missing": missing,
            "factor_count": len(raw_factor_rows),
        },
        "data_sources": [
            "realtime_quote", "price_history", "valuation_history", "financial_indicators",
            "fund_flow", "margin_data", "market_regime", "event/social best-effort APIs",
        ],
        "audit_trail": {
            "engine": "stocksage_alpha",
            "command": "stocksage_report",
            "symbol": code,
            "weights_used": result.get("weights_used", {}),
            "rules": [
                "至少展示三条正向证据和两条反向/风险证据",
                "高机会分且高风险分时标记为 conflicted",
                "缺失数据列入 data_quality.missing",
                "市场环境进入 regime_adjustment 而非被隐藏在总分中",
            ],
        },
        "raw_factors": raw_factor_rows,
        "llm_context": {
            "report_type": "stock_report",
            "report_title": title,
            "symbol": code,
            "overall_view": summary["overall_view"],
            "regime": regime,
            "core_evidence_for": evidence_for[:5],
            "core_evidence_against": evidence_against[:5],
            "conflicts": conflicts,
            "data_quality": {"missing": missing[:12]},
        },
        "markdown": "\n".join(markdown_lines),
    }
    return report


def _build_portfolio_report(args) -> dict:
    analysis = dispatch("portfolio_analysis", {"holdings": args.holdings})
    if not isinstance(analysis, dict) or analysis.get("error"):
        return analysis
    title = "StockSage 组合审计报告"
    group_exposure = analysis.get("group_exposure", {}) or {}
    top = analysis.get("top_holdings", []) or []
    bottom = analysis.get("bottom_holdings", []) or []
    markdown = "\n".join([
        f"# {title}",
        "",
        f"- 覆盖持仓：{analysis.get('holdings_scored', 0)}/{analysis.get('holdings_total', 0)}",
        f"- 组合综合状态分：{analysis.get('portfolio_score', 0)}",
        "",
        "## 主要暴露",
        *[f"- {k}: 买入暴露 {v.get('buy_score', 0)}, 风险暴露 {v.get('sell_score', 0)}" for k, v in list(group_exposure.items())[:8]],
        "",
        "## 贡献较强持仓",
        *[f"- {h.get('symbol')} {h.get('name')}：{h.get('total_score')}" for h in top],
        "",
        "## 拖累/待复核持仓",
        *[f"- {h.get('symbol')} {h.get('name')}：{h.get('total_score')}" for h in bottom],
    ])
    return {
        "report_type": "portfolio_report",
        "title": title,
        "summary": {
            "title": title,
            "overall_view": "portfolio_audit",
            "portfolio_score": analysis.get("portfolio_score", 0),
            "holdings_scored": analysis.get("holdings_scored", 0),
            "holdings_total": analysis.get("holdings_total", 0),
            "generated_at": datetime.now().isoformat(),
        },
        "evidence_for": top,
        "evidence_against": bottom,
        "conflicts": [],
        "regime_adjustment": {},
        "data_sources": ["portfolio holdings", "stocksage factor engine"],
        "audit_trail": {"engine": "stocksage_alpha", "command": "portfolio_analysis"},
        "raw_factors": analysis,
        "llm_context": {
            "report_type": "portfolio_report",
            "report_title": title,
            "portfolio_score": analysis.get("portfolio_score", 0),
            "top_holdings": top,
            "bottom_holdings": bottom,
            "group_exposure": group_exposure,
        },
        "markdown": markdown,
    }


def _build_daily_picks_report(args) -> dict:
    scan_type = getattr(args, "scan_type", None) or getattr(args, "type", None) or "main"
    data = dispatch("scan_universe", {"type": scan_type})
    if not isinstance(data, dict) or data.get("error"):
        return data
    title = f"StockSage 每日推荐审计报告 · {scan_type}"
    picks = data.get("picks", []) or []
    markdown = "\n".join([
        f"# {title}",
        "",
        f"- 市场环境：{data.get('regime', 'unknown')}",
        f"- 扫描股票数：{data.get('scanned', 0)}",
        "",
        "## 推荐候选",
        *[f"- {p.get('code')} {p.get('name')}：{'; '.join(p.get('bullish', [])[:3])}" for p in picks[:10]],
    ])
    return {
        "report_type": "daily_picks_report",
        "title": title,
        "summary": {
            "title": title,
            "strategy": scan_type,
            "regime": data.get("regime", "unknown"),
            "scanned": data.get("scanned", 0),
            "pick_count": len(picks),
            "generated_at": datetime.now().isoformat(),
        },
        "evidence_for": picks,
        "evidence_against": [],
        "conflicts": [],
        "regime_adjustment": {"regime": data.get("regime", "unknown"), "score": data.get("regime_score")},
        "data_sources": ["scan_universe", "price/factor caches", "market regime"],
        "audit_trail": {"engine": "stocksage_alpha", "command": "scan_universe", "strategy": scan_type},
        "raw_factors": data,
        "llm_context": {
            "report_type": "daily_picks_report",
            "report_title": title,
            "strategy": scan_type,
            "regime": data.get("regime", "unknown"),
            "picks": picks[:10],
        },
        "markdown": markdown,
    }


def cmd_stocksage_report(args):
    report_type = getattr(args, "report_type", None) or getattr(args, "type", None) or "stock_report"
    if report_type == "stock_report":
        symbol = getattr(args, "symbol", "")
        if not symbol:
            return output_json({"error": "symbol required"})
        return output_json(_build_stock_report(symbol))
    if report_type == "portfolio_report":
        return output_json(_build_portfolio_report(args))
    if report_type == "daily_picks_report":
        return output_json(_build_daily_picks_report(args))
    return output_json({"error": f"unknown report_type: {report_type}"})


def cmd_stock_report(args):
    return output_json(_build_stock_report(args.symbol))


# Clean StockSage report implementation.
# Overrides the legacy definitions above while preserving CLI/HTTP command names.

FACTOR_USER_GLOSSARY_CLEAN = {
    "value": ("估值吸引力", "观察价格相对盈利、账面价值和历史估值的位置，判断是否存在估值安全垫。"),
    "growth": ("成长质量", "观察收入、利润和经营趋势是否仍在扩张，避免只看短期涨跌。"),
    "momentum": ("价格趋势", "观察近期走势是否得到市场持续确认。趋势强不等于低风险，追高风险仍需单列。"),
    "quality": ("财务质量", "观察盈利能力、现金流、负债压力和财务稳健性。"),
    "technical": ("技术结构", "观察均线、位置、波动和量价配合，辅助判断短期交易拥挤度。"),
    "moneyflow": ("资金行为", "观察主力资金、融资和成交活跃度，判断资金是否支持当前走势。"),
    "risk": ("风险约束", "观察波动、回撤、拥挤和市场环境，避免只看机会。"),
    "market_regime": ("市场环境", "观察大盘环境是否支持提高风险敞口。环境差时，个股证据需要打折。"),
}


def _factor_user_label(name: str) -> str:
    return FACTOR_USER_GLOSSARY_CLEAN.get(name, (name.replace("_", " "), ""))[0]


def _factor_user_explanation(name: str) -> str:
    return FACTOR_USER_GLOSSARY_CLEAN.get(name, ("", "该指标用于补充证据链，不能单独构成买卖结论。"))[1]


def _group_user_label(name: str) -> str:
    group = FACTOR_GROUP_MAP.get(name, "其他")
    return {
        "价值": "估值", "成长": "成长", "动量": "趋势", "质量": "质量",
        "技术": "技术", "资金": "资金", "事件": "事件", "情绪": "情绪", "风控": "风险",
    }.get(group, group)


def _view_label(view: str) -> str:
    return {
        "constructive": "偏积极，但仍需结合仓位和持有周期",
        "neutral": "中性，证据不足以支持激进动作",
        "cautious": "偏谨慎，风险证据需要优先处理",
        "conflicted": "信号冲突，不能用单一方向解释",
    }.get(view, view)


def _factor_signal(name: str, factor: dict) -> str:
    details = factor.get("details") if isinstance(factor, dict) else {}
    if isinstance(details, dict):
        for key in ("signal", "scenario", "quality_signal", "vol_signal", "regime_signal"):
            value = details.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return _factor_user_label(name)


def _factor_row(name: str, factor: dict, side: str) -> dict:
    score_key = "sell_score" if side == "against" else "score"
    pct = _factor_sell_pct(factor) if side == "against" else _factor_pct(factor)
    return {
        "factor": name,
        "label": _factor_user_label(name),
        "group": _group_user_label(name),
        "score": round(float(factor.get(score_key, 0) or 0), 1),
        "max": round(float(factor.get("max", 0) or 0), 1),
        "pct": pct,
        "signal": _factor_signal(name, factor),
        "explanation": _factor_user_explanation(name),
    }


def _evidence_sentence(row: dict, side: str) -> str:
    label = row.get("label") or _factor_user_label(row.get("factor", ""))
    signal = row.get("signal") or label
    explanation = row.get("explanation") or _factor_user_explanation(row.get("factor", ""))
    strength = "强" if row.get("pct", 0) >= 70 else "中等" if row.get("pct", 0) >= 40 else "弱"
    prefix = "支持点" if side == "for" else "风险点"
    return f"{prefix}：{label}显示“{signal}”（证据强度：{strength}）。{explanation}"


def _build_stock_report(symbol: str) -> dict:
    import fetcher
    from factors import DEFAULT_WEIGHTS

    code = fetcher.normalize_code(symbol)
    result = _research_cached(code, DEFAULT_WEIGHTS)
    if not isinstance(result, dict) or result.get("error"):
        return {"error": result.get("error", "report generation failed") if isinstance(result, dict) else "report generation failed"}

    factors = result.get("factors") or {}
    raw_factor_rows = []
    missing = []
    for name, factor in factors.items():
        if not isinstance(factor, dict):
            continue
        if _is_missing_factor(factor):
            missing.append(_factor_user_label(name))
        raw_factor_rows.append({
            "factor": name,
            "label": _factor_user_label(name),
            "group": _group_user_label(name),
            "score": round(float(factor.get("score", 0) or 0), 1),
            "sell_score": round(float(factor.get("sell_score", 0) or 0), 1),
            "max": round(float(factor.get("max", 0) or 0), 1),
            "pct": _factor_pct(factor),
            "sell_pct": _factor_sell_pct(factor),
            "signal": _factor_signal(name, factor),
            "explanation": _factor_user_explanation(name),
            "details": factor.get("details") if isinstance(factor.get("details"), dict) else {},
        })

    evidence_for = sorted(
        [_factor_row(name, factor, "for") for name, factor in factors.items()
         if isinstance(factor, dict) and float(factor.get("score", 0) or 0) > 0],
        key=lambda row: (-row["pct"], -row["score"]),
    )[:8]
    evidence_against = sorted(
        [_factor_row(name, factor, "against") for name, factor in factors.items()
         if isinstance(factor, dict) and float(factor.get("sell_score", 0) or 0) > 0],
        key=lambda row: (-row["pct"], -row["score"]),
    )[:8]

    sorted_raw = sorted(raw_factor_rows, key=lambda row: (-row["pct"], -row["score"]))
    while len(evidence_for) < 3 and len(sorted_raw) > len(evidence_for):
        candidate = sorted_raw[len(evidence_for)]
        evidence_for.append({k: candidate[k] for k in ("factor", "label", "group", "score", "max", "pct", "signal", "explanation")})
    sorted_risk = sorted(raw_factor_rows, key=lambda row: (-row["sell_pct"], -row["sell_score"]))
    while len(evidence_against) < 2 and len(sorted_risk) > len(evidence_against):
        candidate = sorted_risk[len(evidence_against)]
        evidence_against.append({
            "factor": candidate["factor"], "label": candidate["label"], "group": candidate["group"],
            "score": candidate["sell_score"], "max": candidate["max"], "pct": candidate["sell_pct"],
            "signal": candidate["signal"], "explanation": candidate["explanation"],
        })

    market_factor = factors.get("market_regime") or {}
    regime_score = None
    if isinstance(market_factor, dict):
        try:
            regime_score = float(market_factor.get("score", 0) or 0)
        except Exception:
            regime_score = None
    regime = _regime_label_from_score(regime_score)
    total_score = round(float(result.get("total_score", 0) or 0), 1)
    sell_score = round(float(result.get("total_sell_score", 0) or 0), 1)
    conflicted = total_score >= 60 and sell_score >= 35
    overall_view = "conflicted" if conflicted else ("constructive" if total_score >= 60 else "cautious" if sell_score >= 35 else "neutral")

    conflicts = []
    if conflicted:
        conflicts.append({
            "type": "buy_sell_conflict",
            "message": "机会证据和风险证据同时较强，不能用“好/坏”或单一分数概括；需要先看冲突来源和仓位约束。",
        })
    if evidence_for and evidence_against:
        conflicts.append({
            "type": "mixed_evidence",
            "message": f"最强支持证据来自“{evidence_for[0].get('label')}”，同时最强风险证据来自“{evidence_against[0].get('label')}”。",
        })

    regime_message = "市场环境中性，报告不额外放大或折扣个股证据。"
    if regime in ("CAUTION", "CRISIS", "BEAR"):
        regime_message = f"当前环境偏防御（{regime}），趋势和资金类证据需要打折，仓位判断应更保守。"
    elif regime == "BULL":
        regime_message = "当前环境偏强，趋势和资金类证据更容易延续，但仍需检查拥挤和回撤风险。"

    title = f"StockSage 个股审计报告 · {result.get('name') or code}({code})"
    industry = (result.get("basic") or {}).get("industry", "Unknown")
    summary = {
        "title": title,
        "symbol": code,
        "name": result.get("name", ""),
        "industry": industry,
        "overall_view": overall_view,
        "opportunity_score": total_score,
        "risk_score": sell_score,
        "regime": regime,
        "generated_at": datetime.now().isoformat(),
    }

    markdown_lines = [
        f"# {title}", "",
        "## 1. 核心结论",
        f"- 结论状态：{_view_label(overall_view)}。",
        f"- 所属行业：{industry}。",
        f"- 市场环境：{regime}。{regime_message}",
        "- 使用方式：这是一份审计报告，不是交易指令；若要落到买卖，还需要结合你的持有周期、仓位上限和可承受回撤。",
        "", "## 2. 为什么支持继续研究",
        *[f"- {_evidence_sentence(row, 'for')}" for row in evidence_for[:5]],
        "", "## 3. 主要风险和反向证据",
        *[f"- {_evidence_sentence(row, 'against')}" for row in evidence_against[:5]],
    ]
    if conflicts:
        markdown_lines += ["", "## 4. 冲突与限制", *[f"- {item['message']}" for item in conflicts]]
    if missing:
        markdown_lines += ["", "## 5. 数据质量", "- 以下数据缺失或不足，不能当作中性结论处理：", *[f"  - {name}" for name in missing[:12]]]
    markdown_lines += [
        "", "## 6. 术语说明",
        "- 证据强度表示该证据在当前模型中的相对显著程度，不等于收益预测。",
        "- 风险证据表示可能削弱投资结论的因素，不等于必然下跌。",
        "- 市场环境用于调整解释力度：环境越差，越应降低仓位和追高倾向。",
        "", "## 7. 审计附录",
        f"- 原始机会分：{total_score}；原始风险分：{sell_score}。它们仅用于审计，不作为主结论展示。",
        f"- 覆盖因子数：{len(raw_factor_rows)}。",
    ]
    markdown_report = "\n".join(markdown_lines)

    return {
        "report_type": "stock_report",
        "title": title,
        "summary": summary,
        "evidence_for": evidence_for,
        "evidence_against": evidence_against,
        "conflicts": conflicts,
        "regime_adjustment": {"regime": regime, "score": regime_score, "message": regime_message},
        "data_quality": {"missing": missing, "factor_count": len(raw_factor_rows)},
        "data_sources": ["实时行情", "历史价格", "估值历史", "财务指标", "资金行为", "融资数据", "市场环境", "事件/情绪类公开数据（尽力获取）"],
        "audit_trail": {
            "engine": "stocksage_alpha",
            "command": "stocksage_report",
            "symbol": code,
            "weights_used": result.get("weights_used", {}),
            "rules": ["至少展示三条正向证据和两条风险证据", "机会与风险同时较强时标记为 conflicted", "缺失数据列入 data_quality.missing", "市场环境进入 regime_adjustment，不隐藏在总分里"],
        },
        "raw_factors": raw_factor_rows,
        "llm_context": {
            "report_type": "stock_report",
            "report_title": title,
            "symbol": code,
            "overall_view": overall_view,
            "regime": regime,
            "markdown_report": markdown_report,
            "core_evidence_for": evidence_for[:5],
            "core_evidence_against": evidence_against[:5],
            "conflicts": conflicts,
            "data_quality": {"missing": missing[:12]},
        },
        "markdown": markdown_report,
    }


# ----------------------------------------------------------------
# Programmatic dispatch — shared by CLI (main) and the resident server.
# Captures output_json so cmd_* needn't be rewritten. Serialized by callers.
# ----------------------------------------------------------------

_CMDS = None  # lazy: populated on first dispatch (cmd_* are defined above)


def _resolve_universe_codes(universe: str, intent: str = "") -> list:
    """Resolve a universe description to a list of 6-digit stock codes.
    When universe is "all", also checks the intent string for known industry
    names so the LLM doesn't need to explicitly set universe for sector picks."""

    # Common user-facing industry names → Shenwan industry names in DB
    _INDUSTRY_ALIASES = {
        "航天": "国防军工", "航空": "国防军工", "军工": "国防军工", "国防": "国防军工",
        "银行": "银行", "保险": "银行", "券商": "非银金融", "证券": "非银金融",
        "白酒": "食品饮料", "食品": "食品饮料", "饮料": "食品饮料",
        "房地产": "房地产", "地产": "房地产",
        "新能源": "电力设备", "光伏": "电力设备", "风电": "电力设备",
        "半导体": "电子", "芯片": "电子", "消费电子": "电子",
        "医药": "医药生物", "医疗": "医药生物", "医": "医药生物",
        "煤炭": "煤炭", "钢铁": "钢铁",
        "有色": "有色金属", "黄金": "有色金属",
        "石油": "石油石化", "石化": "石油石化",
        "汽车": "汽车", "新能源车": "汽车",
        "家电": "家用电器",
        "纺织": "纺织服饰", "服装": "纺织服饰",
        "传媒": "传媒", "游戏": "传媒",
        "计算机": "计算机", "软件": "计算机", "AI": "计算机",
        "通信": "通信", "5G": "通信",
        "建筑": "建筑装饰", "基建": "建筑装饰",
        "机械": "机械设备",
        "电力": "公用事业", "公用事业": "公用事业",
        "交运": "交通运输", "运输": "交通运输",
        "社服": "社会服务", "旅游": "社会服务",
        "轻工": "轻工制造", "造纸": "轻工制造",
        "化工": "基础化工",
        "农业": "农林牧渔", "养殖": "农林牧渔",
        "商贸": "商贸零售", "零售": "商贸零售",
        "建材": "建筑材料", "水泥": "建筑材料",
        "环保": "环保",
        "军工": "国防军工",
    }

    # Try alias first
    if universe and universe not in ("all", ""):
        alias_target = None
        for alias, target in _INDUSTRY_ALIASES.items():
            if alias in universe or universe in alias:
                alias_target = target
                break
        if alias_target:
            universe = alias_target

    if universe and universe not in ("all", ""):
        # Explicit universe takes priority
        if universe == "csi300":
            try:
                from fetcher import get_index_constituents
                return get_index_constituents("000300")[:20]
            except Exception:
                return []
        elif universe == "csi500":
            try:
                from fetcher import get_index_constituents
                return get_index_constituents("000905")[:20]
            except Exception:
                return []
        else:
            codes = []
            try:
                from fetcher import get_sw_industry_map
                imap = get_sw_industry_map()
                univ_clean = universe.replace("板块","").replace("行业","").replace("概念","").replace("股","").strip()
                codes = [code for code, ind in imap.items()
                        if (universe in ind or ind in universe or (univ_clean and univ_clean in ind))][:20]
            except Exception:
                pass
            if codes:
                return codes
            # Industry match failed — search stock names in DB so ANY keyword works
            try:
                import pymysql
                conn = pymysql.connect(
                    host="localhost", port=3306, database="investory",
                    user="root", password="Phy80923883",
                    charset="utf8mb4", autocommit=True)
                cur = conn.cursor()
                keyword = univ_clean or universe
                cur.execute(
                    "SELECT symbol FROM stocks WHERE name LIKE %s OR symbol LIKE %s LIMIT 20",
                    (f"%{keyword}%", f"%{keyword}%"))
                result = [r[0][:6] if len(r[0]) > 6 else r[0] for r in cur.fetchall()]
                cur.close(); conn.close()
                if result:
                    print(f"  [信息] 按名称搜索到 {len(result)} 只相关股票", flush=True)
                    return result
            except Exception:
                pass
            return []

    # universe is "all" or empty — try to auto-detect industry from intent
    if intent.strip():
        # Also apply alias mapping for intent-based detection
        alias_intent = intent
        for alias, target in _INDUSTRY_ALIASES.items():
            if alias in intent:
                alias_intent = intent.replace(alias, target)
                break
        try:
            from fetcher import get_sw_industry_map
            imap = get_sw_industry_map()
            all_industries = sorted(set(imap.values()))
            intent_clean = alias_intent.replace("板块","").replace("行业","").replace("概念","").replace("股","").strip()
            for ind in all_industries:
                if ind and (ind in alias_intent or alias_intent in ind or (intent_clean and intent_clean in ind)):
                    codes = [code for code, i in imap.items() if i == ind][:20]
                    if codes:
                        print(f"  [信息] 从 intent 中检测到行业「{ind}」，范围缩小至 {len(codes)} 只", flush=True)
                        return codes
        except Exception:
            pass

    # Intent industry detection failed — try stock name search
    if intent.strip():
        try:
            import pymysql
            conn = pymysql.connect(
                host="localhost", port=3306, database="investory",
                user="root", password="Phy80923883",
                charset="utf8mb4", autocommit=True)
            cur = conn.cursor()
            kw = intent.replace("板块","").replace("行业","").replace("概念","").replace("股","").replace("好股票","").replace("推荐","").strip()
            if len(kw) >= 2:
                cur.execute(
                    "SELECT symbol FROM stocks WHERE name LIKE %s LIMIT 20",
                    (f"%{kw}%",))
                result = [r[0][:6] if len(r[0]) > 6 else r[0] for r in cur.fetchall()]
                if result:
                    print(f"  [信息] 按 intent 名称搜索到 {len(result)} 只股票", flush=True)
                    cur.close(); conn.close()
                    return result
            cur.close(); conn.close()
        except Exception:
            pass

    # Fallback to full universe
    uf = ROOT / "data" / "universe_main.json"
    if uf.exists():
        codes = json.loads(uf.read_text(encoding="utf-8"))
        return [c[:6] if len(c) > 6 else c for c in codes[:20]]
    # Last resort: get liquid stocks from index
    try:
        from fetcher import get_index_constituents
        return get_index_constituents("000300")[:20]
    except Exception:
        return []


def _build_pick_stocks_report(intent: str, universe: str = "all", top_n: int = 10) -> dict:
    """Core pick_stocks pipeline: intent -> weights -> score -> filter -> rank."""
    from factors import DEFAULT_WEIGHTS, parse_weights, weights_from_config_dict
    from factors.config import REGIME_WEIGHTS

    start_t = time.time()

    # 1. Parse intent into factor weight overrides
    override_weights = parse_weights(intent) if intent.strip() else DEFAULT_WEIGHTS

    # 2. Determine market regime for base weights
    regime_result = dispatch("regime_status", {})
    if isinstance(regime_result, dict) and not regime_result.get("error"):
        regime_data = regime_result.get("regime", {})
        if isinstance(regime_data, dict):
            regime = regime_data.get("signal", "NORMAL")
            regime_score_val = float(regime_data.get("score", 5.0))
        else:
            regime = str(regime_data) if regime_data else "NORMAL"
            regime_score_val = 5.0
    else:
        regime = "NORMAL"
        regime_score_val = 5.0

    rk = "NORMAL"
    if regime in ("BEAR", "CRISIS"):
        rk = regime
    elif regime == "CAUTION":
        rk = "CAUTION"
    elif regime in ("BULL", "EXTREME_BULL"):
        rk = "BULL"

    # 3. Combine regime base weights with intent overrides
    # score_universe() expects a plain dict, not FactorWeights — work with dicts.
    combined = dict(REGIME_WEIGHTS.get(rk, REGIME_WEIGHTS["NORMAL"]))
    if intent.strip():
        from dataclasses import fields as _dc_fields
        default_w = DEFAULT_WEIGHTS
        for field in _dc_fields(type(override_weights)):
            ov = getattr(override_weights, field.name)
            dv = getattr(default_w, field.name)
            if ov != dv:
                combined[field.name] = ov

    # 4. Resolve universe
    universe_label = universe if universe else "全市场"
    codes = _resolve_universe_codes(universe, intent)
    if not codes:
        return {"error": f"无法解析选股范围: {universe}"}

    scanned = len(codes)

    # 5. Score the universe with per-stock timeout (some fetchers hang)
    import concurrent.futures
    from concurrent.futures import ThreadPoolExecutor, as_completed
    from functools import partial
    from strategies._scoring import filter_buys
    from factors import weights_from_config_dict
    from report.utils import score_one_buy as _score_one_buy
    fw = weights_from_config_dict(combined)
    score_fn = partial(_score_one_buy, weights=fw)
    scored = []
    ex = ThreadPoolExecutor(max_workers=8)
    try:
        futs = {ex.submit(score_fn, c): c for c in codes}
        try:
            for fut in as_completed(futs, timeout=95):
                try:
                    scored.append(fut.result())
                except Exception:
                    pass
        except concurrent.futures.TimeoutError:
            pass  # Return partial results collected so far
        # Cancel all remaining futures so ex.__exit__ won't hang on them
        for fut in futs:
            fut.cancel()
    finally:
        ex.shutdown(wait=False)  # Don't wait for stragglers
    # Any stragglers past 90s are skipped silently
    if not scored:
        return {"error": "评分超时：所有股票均未能在 90 秒内完成分析"}

    # 6. Filter
    buy_trig = 55 if regime in ("BEAR", "CRISIS") else 50
    sell_guard = 50
    picks = filter_buys(scored, buy_trig=buy_trig, sell_guard=sell_guard, top_n=top_n)

    elapsed = time.time() - start_t

    # 7. Build markdown report
    regime_desc = {"BULL": "强势", "NORMAL": "正常", "CAUTION": "谨慎", "BEAR": "弱势", "CRISIS": "危机"}.get(regime, "正常")

    mk_lines = [
        f"# StockSage 智能选股报告 - {intent or '综合筛选'}",
        "",
        f"## 1. 选股概要",
        f"- 投资偏好：{intent or '综合'}",
        f"- 选股范围：{universe_label}（{scanned}只股票）",
        f"- 市场环境：{regime}（{regime_desc}）",
        f"- 入选候选：{len(picks)}只",
        f"- 分析耗时：{elapsed:.0f}秒",
        "",
        f"## 2. 入选候选",
    ]

    if picks:
        mk_lines.append("| 排名 | 代码 | 名称 | 买入分 | 风险分 | 核心亮点 |")
        mk_lines.append("|------|------|------|--------|--------|----------|")
        for i, p in enumerate(picks[:top_n], 1):
            code = p.get("code", "")
            name = p.get("name", code)
            bs = p.get("buy_score", 0) or 0
            ss = p.get("sell_score", 0) or 0
            bullish = p.get("bullish", []) or []
            highlights = ";".join(bullish[:3]) if isinstance(bullish, list) else str(bullish)[:60]
            mk_lines.append(f"| {i} | {code} | {name} | {bs:.1f} | {ss:.1f} | {highlights} |")

        mk_lines.extend(["", "## 3. 候选详情"])
        for i, p in enumerate(picks[:top_n], 1):
            code = p.get("code", "")
            name = p.get("name", code)
            bs = p.get("buy_score", 0) or 0
            ss = p.get("sell_score", 0) or 0
            bullish = p.get("bullish", []) or []
            bearish = p.get("bearish", []) or []
            for_text = "、".join(bullish[:3]) if isinstance(bullish, list) else ""
            against_text = "、".join(bearish[:2]) if isinstance(bearish, list) else ""
            mk_lines.append(f"")
            mk_lines.append(f"### {i}. {name}（{code}）")
            mk_lines.append(f"- 买入分 {bs:.1f} / 风险分 {ss:.1f}")
            mk_lines.append(f"- 支持证据：{for_text or '无'}")
            if against_text:
                mk_lines.append(f"- 风险提示：{against_text}")
    else:
        mk_lines.append("在当前筛选条件下未找到符合条件的标的。")

    mk_lines.extend([
        "",
        "## 4. 筛选说明",
        f"- 因子权重来源：{regime}环境权重 + 意图覆盖",
        f"- 买入阈值：{buy_trig} / 卖出保护：{sell_guard}",
        "",
        "## 5. 使用说明",
        "- 这是基于多因子模型的候选清单，不是交易指令",
        "- 建议对感兴趣的标的调用 get_stock_report 获取深度审计",
        f"- 当前市场环境：{regime}（{regime_desc}），请据此调整仓位",
    ])

    markdown = "\n".join(mk_lines)

    result = {
        "report_type": "pick_stocks_report",
        "title": f"StockSage 智能选股 - {intent or '综合筛选'}",
        "summary": f"在{universe_label}中（{scanned}只），按\"{intent or '综合'}\"偏好筛选出{len(picks)}只候选",
        "intent": intent,
        "universe": universe,
        "regime": regime,
        "scanned": scanned,
        "picks": picks[:top_n],
        "evidence_for": picks[:top_n],
        "evidence_against": [],
        "conflicts": [],
        "regime_adjustment": {"regime": regime, "score": regime_score_val, "adaptation": f"{regime_desc}环境权重"},
        "data_sources": ["stock_prices", "factor_scores", "market_regime", "industry_map"],
        "audit_trail": {
            "engine": "stocksage_alpha",
            "command": "pick_stocks",
            "intent": intent,
            "universe": universe,
            "top_n": top_n,
            "regime": regime,
            "buy_trig": buy_trig,
            "sell_guard": sell_guard,
            "scanned": scanned,
            "elapsed_seconds": round(elapsed, 1),
        },
        "llm_context": {
            "report_type": "pick_stocks_report",
            "intent": intent,
            "universe": universe,
            "regime": regime,
            "scanned": scanned,
            "picks": [{
                "code": p.get("code", ""),
                "name": p.get("name", ""),
                "buy_score": p.get("buy_score", 0),
                "sell_score": p.get("sell_score", 0),
                "top_factors": (p.get("bullish", []) or [])[:3],
            } for p in picks[:top_n]],
        },
        "markdown": markdown,
    }
    return result


def cmd_pick_stocks(args):
    intent = getattr(args, "intent", "") or ""
    universe = getattr(args, "universe", "all") or "all"
    top_n = min(int(getattr(args, "top_n", 10) or 10), 20)
    result = _build_pick_stocks_report(intent, universe, top_n)
    output_json(result)


def _cmd_table():
    global _CMDS
    if _CMDS is None:
        _CMDS = {
            "score_stocks": cmd_score_stocks,
            "scan_universe": cmd_scan_universe,
            "regime_status": cmd_regime_status,
            "factor_breakdown": cmd_factor_breakdown,
            "chip_distribution": cmd_chip_distribution,
            "portfolio_analysis": cmd_portfolio_analysis,
            "prefetch_data": cmd_prefetch_data,
            "stocksage_report": cmd_stocksage_report,
            "stock_report": cmd_stock_report,
            "pick_stocks": cmd_pick_stocks,
        }
    return _CMDS


def dispatch(command: str, params: dict | None = None) -> dict:
    """Run a bridge command programmatically and return its result dict.

    The cmd_* functions write via output_json(); we temporarily capture that
    sink instead of printing, so the same code path serves the CLI and the
    resident FastAPI server with zero duplication. NOT thread-safe — callers
    must serialize (the server holds a lock)."""
    from types import SimpleNamespace
    global output_json
    fn = _cmd_table().get(command)
    if fn is None:
        return {"error": f"unknown command: {command}"}
    captured = {}
    original = output_json
    output_json = lambda obj: captured.__setitem__("result", obj)  # noqa: E731
    try:
        fn(SimpleNamespace(**(params or {})))
    except Exception:
        captured["result"] = {"error": traceback.format_exc()[-400:]}
    finally:
        output_json = original
    return captured.get("result", {"error": "engine produced no result"})


# ----------------------------------------------------------------
# CLI dispatch
# ----------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="StockSage Alpha Bridge")
    sub = parser.add_subparsers(dest="command")

    p = sub.add_parser("score_stocks"); p.add_argument("--symbols", required=True)
    p = sub.add_parser("scan_universe"); p.add_argument("--type", default="main")
    sub.add_parser("regime_status")
    p = sub.add_parser("factor_breakdown"); p.add_argument("--symbol", required=True)
    p = sub.add_parser("chip_distribution"); p.add_argument("--symbol", required=True)
    p = sub.add_parser("portfolio_analysis"); p.add_argument("--holdings", required=True)
    sub.add_parser("prefetch_data")
    p = sub.add_parser("stocksage_report")
    p.add_argument("--report-type", default="stock_report")
    p.add_argument("--symbol", default="")
    p.add_argument("--holdings", default="[]")
    p.add_argument("--scan-type", default="main")
    p = sub.add_parser("stock_report")
    p.add_argument("--symbol", required=True)

    args = parser.parse_args()

    cmds = {
        "score_stocks": cmd_score_stocks,
        "scan_universe": cmd_scan_universe,
        "regime_status": cmd_regime_status,
        "factor_breakdown": cmd_factor_breakdown,
        "chip_distribution": cmd_chip_distribution,
        "portfolio_analysis": cmd_portfolio_analysis,
        "prefetch_data": cmd_prefetch_data,
        "stocksage_report": cmd_stocksage_report,
        "stock_report": cmd_stock_report,
    }

    fn = cmds.get(args.command)
    if fn:
        fn(args)
    else:
        output_json({"error": "unknown command"})
        sys.exit(1)


if __name__ == "__main__":
    main()
