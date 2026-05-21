#!/usr/bin/env python3
"""
AKShare 可访问性测试脚本
测试港股、美股、指数近10天日K线是否可直连获取（无需代理）
"""

import sys
from datetime import datetime, timedelta

import os, urllib.request
for _k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
    os.environ.pop(_k, None)
# Disable Windows registry proxy so requests lib doesn't pick up Clash system proxy
urllib.request.getproxies = lambda: {}

try:
    import akshare as ak
except ImportError:
    print("NG akshare 未安装，请先: pip install akshare")
    sys.exit(1)

end   = datetime.today()
start = end - timedelta(days=14)  # 多取14天保证有10个交易日
start_s = start.strftime("%Y%m%d")
end_s   = end.strftime("%Y%m%d")

print(f"AKShare 版本: {ak.__version__}")
print(f"测试区间: {start_s} ~ {end_s}\n")

# ─── 港股 ─────────────────────────────────────────────────────────────────────

HK_CASES = [
    ("00700", "腾讯控股"),
    ("09988", "阿里巴巴-W"),
    ("00005", "汇丰控股"),
    ("03690", "美团-W"),
    ("01810", "小米集团-W"),
]

print("=" * 55)
print("港股 (ak.stock_hk_hist)")
print("=" * 55)
for symbol, name in HK_CASES:
    try:
        df = ak.stock_hk_hist(
            symbol=symbol, period="daily",
            start_date=start_s, end_date=end_s, adjust=""
        )
        if df is None or df.empty:
            print(f"  NG {symbol} {name:<12} → 无数据")
        else:
            latest = df.iloc[-1]
            print(f"  OK {symbol} {name:<12} → {len(df)}行  "
                  f"最新:{latest['日期'] if '日期' in df.columns else latest.iloc[0]}  "
                  f"收:{latest['收盘'] if '收盘' in df.columns else latest.iloc[4]:.2f}")
    except Exception as e:
        print(f"  NG {symbol} {name:<12} → 错误: {e}")

# ─── 美股 ─────────────────────────────────────────────────────────────────────

US_CASES = [
    ("AAPL",  "苹果"),
    ("MSFT",  "微软"),
    ("NVDA",  "英伟达"),
    ("BABA",  "阿里巴巴"),
    ("TSLA",  "特斯拉"),
]

print()
print("=" * 55)
print("美股 (ak.stock_us_hist)")
print("=" * 55)
for symbol, name in US_CASES:
    try:
        df = ak.stock_us_hist(
            symbol=symbol, period="daily",
            start_date=start_s, end_date=end_s, adjust=""
        )
        if df is None or df.empty:
            print(f"  NG {symbol:<6} {name:<8} → 无数据")
        else:
            latest = df.iloc[-1]
            print(f"  OK {symbol:<6} {name:<8} → {len(df)}行  "
                  f"最新:{latest['日期'] if '日期' in df.columns else latest.iloc[0]}  "
                  f"收:{latest['收盘'] if '收盘' in df.columns else latest.iloc[4]:.2f}")
    except Exception as e:
        print(f"  NG {symbol:<6} {name:<8} → 错误: {e}")

# ─── 指数 ─────────────────────────────────────────────────────────────────────

print()
print("=" * 55)
print("全球指数 (ak.stock_us_index_daily / ak.index_investing_global)")
print("=" * 55)

INDEX_CASES = [
    ("^HSI",   "恒生指数",   "hk"),
    ("^GSPC",  "标普500",    "us"),
    ("^IXIC",  "纳斯达克",   "us"),
]
for symbol, name, _ in INDEX_CASES:
    try:
        df = ak.stock_us_hist(
            symbol=symbol, period="daily",
            start_date=start_s, end_date=end_s, adjust=""
        )
        if df is None or df.empty:
            print(f"  NG {symbol:<8} {name:<10} → 无数据")
        else:
            latest = df.iloc[-1]
            print(f"  OK {symbol:<8} {name:<10} → {len(df)}行  "
                  f"最新:{latest.iloc[0]}  收:{latest.iloc[4]:.2f}")
    except Exception as e:
        print(f"  NG {symbol:<8} {name:<10} → 错误: {e}")

print()
print("测试完成")
