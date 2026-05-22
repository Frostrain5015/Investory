#!/usr/bin/env python3
"""
Investory 回测辅助库 — 技术指标计算

所有函数输入为 numpy arrays，输出为等长 numpy arrays。
缺失值处填充 NaN，与 OHLCV 原始数组对齐。
"""

import numpy as np
from typing import Tuple, Optional


def compute_sma(closes: np.ndarray, period: int) -> np.ndarray:
    """简单移动平均"""
    if len(closes) < period:
        return np.full_like(closes, np.nan)
    result = np.full_like(closes, np.nan)
    cumsum = np.cumsum(np.insert(closes, 0, 0))
    result[period - 1:] = (cumsum[period:] - cumsum[:-period]) / period
    return result


def compute_ema(closes: np.ndarray, period: int) -> np.ndarray:
    """指数移动平均"""
    result = np.full_like(closes, np.nan)
    if len(closes) < period:
        return result
    alpha = 2.0 / (period + 1)
    result[period - 1] = np.mean(closes[:period])
    for i in range(period, len(closes)):
        result[i] = alpha * closes[i] + (1 - alpha) * result[i - 1]
    return result


def compute_rsi(closes: np.ndarray, period: int = 14) -> np.ndarray:
    """相对强弱指标"""
    result = np.full_like(closes, np.nan)
    if len(closes) < period + 1:
        return result
    deltas = np.diff(closes)
    gains = np.where(deltas > 0, deltas, 0.0)
    losses = np.where(deltas < 0, -deltas, 0.0)
    avg_gain = np.mean(gains[:period])
    avg_loss = np.mean(losses[:period])
    if avg_loss == 0:
        result[period] = 100.0
    else:
        rs = avg_gain / avg_loss
        result[period] = 100.0 - (100.0 / (1.0 + rs))
    for i in range(period + 1, len(closes)):
        avg_gain = (avg_gain * (period - 1) + gains[i - 1]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i - 1]) / period
        if avg_loss == 0:
            result[i] = 100.0
        else:
            rs = avg_gain / avg_loss
            result[i] = 100.0 - (100.0 / (1.0 + rs))
    return result


def compute_macd(closes: np.ndarray, fast: int = 12, slow: int = 26, signal: int = 9) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """MACD: 返回 (dif, dea, histogram)"""
    ema_fast = compute_ema(closes, fast)
    ema_slow = compute_ema(closes, slow)
    dif = ema_fast - ema_slow
    dea = compute_ema(dif, signal)
    histogram = dif - dea
    return dif, dea, histogram


def compute_bollinger(closes: np.ndarray, period: int = 20, nbdev: float = 2.0) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """布林带: 返回 (upper, middle, lower)"""
    middle = compute_sma(closes, period)
    rolling_std = np.full_like(closes, np.nan)
    for i in range(period - 1, len(closes)):
        rolling_std[i] = np.std(closes[i - period + 1:i + 1], ddof=0)
    upper = middle + nbdev * rolling_std
    lower = middle - nbdev * rolling_std
    return upper, middle, lower


def compute_atr(highs: np.ndarray, lows: np.ndarray, closes: np.ndarray, period: int = 14) -> np.ndarray:
    """平均真实波幅"""
    result = np.full_like(closes, np.nan)
    if len(closes) < period + 1:
        return result
    prev_close = np.roll(closes, 1)
    prev_close[0] = closes[0]
    tr = np.maximum.reduce([
        highs - lows,
        np.abs(highs - prev_close),
        np.abs(lows - prev_close),
    ])
    result[period] = np.mean(tr[1:period + 1])
    for i in range(period + 1, len(closes)):
        result[i] = (result[i - 1] * (period - 1) + tr[i]) / period
    return result


def compute_kdj(highs: np.ndarray, lows: np.ndarray, closes: np.ndarray,
                period: int = 9, k_period: int = 3, d_period: int = 3) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """KDJ 指标: 返回 (k, d, j)"""
    n = len(closes)
    k = np.full(n, np.nan); d = np.full(n, np.nan); j = np.full(n, np.nan)
    if n < period:
        return k, d, j
    rsv = np.full(n, np.nan)
    for i in range(period - 1, n):
        hh = np.max(highs[i - period + 1:i + 1])
        ll = np.min(lows[i - period + 1:i + 1])
        rsv[i] = 100.0 * (closes[i] - ll) / (hh - ll) if hh != ll else 50.0
    k[period - 1] = 50.0; d[period - 1] = 50.0
    for i in range(period, n):
        if np.isnan(rsv[i]):
            k[i] = k[i - 1]; d[i] = d[i - 1]
        else:
            k[i] = (k[i - 1] * (k_period - 1) + rsv[i]) / k_period
            d[i] = (d[i - 1] * (d_period - 1) + k[i]) / d_period
        j[i] = 3 * k[i] - 2 * d[i]
    return k, d, j


def compute_volume_ma(volumes: np.ndarray, period: int = 20) -> np.ndarray:
    """成交量移动平均"""
    return compute_sma(volumes, period)


def compute_high_n(closes: np.ndarray, period: int) -> np.ndarray:
    """N日最高价"""
    result = np.full_like(closes, np.nan)
    if len(closes) < period:
        return result
    for i in range(period - 1, len(closes)):
        result[i] = np.max(closes[i - period + 1:i + 1])
    return result


def compute_low_n(closes: np.ndarray, period: int) -> np.ndarray:
    """N日最低价"""
    result = np.full_like(closes, np.nan)
    if len(closes) < period:
        return result
    for i in range(period - 1, len(closes)):
        result[i] = np.min(closes[i - period + 1:i + 1])
    return result


# ── Strategy evaluation helpers ──────────────────────────────────────────

def eval_indicator(name: str, ohlcv: dict, params: dict, idx: int) -> Optional[float]:
    """
    评估单个指标在当前时刻的值。
    ohlcv: { open, high, low, close, volume } — 全部为全量 np.ndarray
    返回指标值或 None
    """
    closes = ohlcv["close"]
    highs = ohlcv["high"]
    lows = ohlcv["low"]
    volumes = ohlcv["volume"]
    n = idx + 1  # 截止到 idx 的数据窗口

    try:
        if name == "sma":
            v = compute_sma(closes[:n], int(params.get("period", 20)))
            return float(v[-1]) if not np.isnan(v[-1]) else None
        elif name == "ema":
            v = compute_ema(closes[:n], int(params.get("period", 20)))
            return float(v[-1]) if not np.isnan(v[-1]) else None
        elif name == "rsi":
            v = compute_rsi(closes[:n], int(params.get("period", 14)))
            return float(v[-1]) if not np.isnan(v[-1]) else None
        elif name == "macd_dif":
            dif, _, _ = compute_macd(closes[:n],
                                     int(params.get("fast", 12)),
                                     int(params.get("slow", 26)),
                                     int(params.get("signal", 9)))
            return float(dif[-1]) if not np.isnan(dif[-1]) else None
        elif name == "macd_dea":
            _, dea, _ = compute_macd(closes[:n],
                                     int(params.get("fast", 12)),
                                     int(params.get("slow", 26)),
                                     int(params.get("signal", 9)))
            return float(dea[-1]) if not np.isnan(dea[-1]) else None
        elif name == "macd_histogram":
            _, _, hist = compute_macd(closes[:n],
                                      int(params.get("fast", 12)),
                                      int(params.get("slow", 26)),
                                      int(params.get("signal", 9)))
            return float(hist[-1]) if not np.isnan(hist[-1]) else None
        elif name == "bollinger_upper":
            upper, _, _ = compute_bollinger(closes[:n],
                                            int(params.get("period", 20)),
                                            float(params.get("nbdev", 2.0)))
            return float(upper[-1]) if not np.isnan(upper[-1]) else None
        elif name == "bollinger_lower":
            _, _, lower = compute_bollinger(closes[:n],
                                            int(params.get("period", 20)),
                                            float(params.get("nbdev", 2.0)))
            return float(lower[-1]) if not np.isnan(lower[-1]) else None
        elif name == "bollinger_middle":
            _, middle, _ = compute_bollinger(closes[:n],
                                             int(params.get("period", 20)),
                                             float(params.get("nbdev", 2.0)))
            return float(middle[-1]) if not np.isnan(middle[-1]) else None
        elif name == "atr":
            v = compute_atr(highs[:n], lows[:n], closes[:n], int(params.get("period", 14)))
            return float(v[-1]) if not np.isnan(v[-1]) else None
        elif name == "volume_ma":
            v = compute_volume_ma(volumes[:n], int(params.get("period", 20)))
            return float(v[-1]) if not np.isnan(v[-1]) else None
        elif name == "high_n":
            v = compute_high_n(closes[:n], int(params.get("period", 20)))
            return float(v[-1]) if not np.isnan(v[-1]) else None
        elif name == "low_n":
            v = compute_low_n(closes[:n], int(params.get("period", 20)))
            return float(v[-1]) if not np.isnan(v[-1]) else None
        elif name == "kdj_k":
            k, _, _ = compute_kdj(highs[:n], lows[:n], closes[:n],
                                  int(params.get("period", 9)),
                                  int(params.get("k_period", 3)),
                                  int(params.get("d_period", 3)))
            return float(k[-1]) if not np.isnan(k[-1]) else None
        elif name == "kdj_d":
            _, d, _ = compute_kdj(highs[:n], lows[:n], closes[:n],
                                  int(params.get("period", 9)),
                                  int(params.get("k_period", 3)),
                                  int(params.get("d_period", 3)))
            return float(d[-1]) if not np.isnan(d[-1]) else None
        elif name == "price":
            return float(closes[idx])
        elif name == "volume":
            return float(volumes[idx])
        return None
    except Exception:
        return None


def eval_condition(indicator_value: Optional[float], rule: dict) -> bool:
    """检查指标值是否满足规则条件"""
    if indicator_value is None:
        return False

    condition = rule.get("condition", "")
    threshold = float(rule.get("threshold", 0))

    if condition == "above":
        return indicator_value > threshold
    elif condition == "below":
        return indicator_value < threshold
    elif condition == "cross_above":
        return indicator_value > threshold
    elif condition == "cross_below":
        return indicator_value < threshold
    elif condition == "overbought":
        return indicator_value > threshold
    elif condition == "oversold":
        return indicator_value < threshold
    return False
