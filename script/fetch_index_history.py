"""
抓取9个中港美指数2年历史日K线数据
- 中国: 上证指数/深证成指/创业板指 (新浪财经)
- 香港: 恒生指数/国企指数/恒生科技 (Yahoo Finance)
- 美国: 标普500/道琼斯/纳斯达克 (Yahoo Finance)
- 目标: MySQL investory 库 stocks + stock_prices 表
- 使用 INSERT ... ON DUPLICATE KEY UPDATE 防止重复

用法:
    python fetch_index_history.py
"""

import math
import os
import pymysql
import yfinance as yf
import akshare as ak
import json
import time
import traceback
from datetime import datetime, timedelta
import urllib.request
import urllib.error

# yfinance 走 SOCKS5 代理
os.environ['HTTPS_PROXY'] = 'socks5h://127.0.0.1:7897'
os.environ['HTTP_PROXY'] = 'socks5h://127.0.0.1:7897'

DB_CONFIG = dict(host='localhost', port=3306, user='root',
                 password='REDACTED_DB_PW', database='investory', charset='utf8mb4')

YEARS_BACK = 2
START_DATE = (datetime.now() - timedelta(days=YEARS_BACK * 365)).strftime('%Y-%m-%d')
END_DATE   = datetime.now().strftime('%Y-%m-%d')
PAUSE = 0.3

# ============================================================
# 指数定义: (数据源, yf_ticker/sina_code, db_symbol, name, market, currency)
# ============================================================
INDICES = [
    # 中国 — 新浪财经 K线
    ('sina', 'sh000001', '000001.SH', '上证指数',   'SH', 'CNY'),
    ('sina', 'sz399001', '399001.SZ', '深证成指',   'SZ', 'CNY'),
    ('sina', 'sz399006', '399006.SZ', '创业板指',   'SZ', 'CNY'),
    # 香港 — Yahoo Finance (HSTECH 用 akshare 因 yfinance 无历史数据)
    ('yf',   '^HSI',     'HSI.HK',    '恒生指数',   'HK', 'HKD'),
    ('yf',   '^HSCE',    'HSCE.HK',   '国企指数',   'HK', 'HKD'),
    ('ak',   'HSTECH',   'HSTECH.HK', '恒生科技',   'HK', 'HKD'),
    # 美国 — Yahoo Finance
    ('yf',   '^GSPC',    'GSPC.US',   '标普500',    'US', 'USD'),
    ('yf',   '^DJI',     'DJI.US',    '道琼斯工业',  'US', 'USD'),
    ('yf',   '^IXIC',    'IXIC.US',   '纳斯达克综合', 'US', 'USD'),
    # 日本
    ('yf',   '^N225',    'N225.JP',   '日经225',    'JP', 'JPY'),
    # 韩国
    ('yf',   '^KS11',    'KS11.KR',   '韩国KOSPI',  'KR', 'KRW'),
    # 英国
    ('yf',   '^FTSE',    'FTSE.GB',   '富时100',    'GB', 'GBP'),
    # 德国
    ('yf',   '^GDAXI',   'GDAXI.DE',  '德国DAX',    'DE', 'EUR'),
    # 法国
    ('yf',   '^FCHI',    'FCHI.FR',   '法国CAC40',  'FR', 'EUR'),
    # 台湾
    ('yf',   '^TWII',    'TWII.TW',   '台湾加权',    'TW', 'TWD'),
    # 新加坡
    ('yf',   '^STI',     'STI.SG',    '新加坡STI',  'SG', 'SGD'),
    # 印度
    ('yf',   '^BSESN',   'BSESN.IN',  '印度SENSEX', 'IN', 'INR'),
    # 澳大利亚
    ('yf',   '^AXJO',    'AXJO.AU',   '澳洲ASX200', 'AU', 'AUD'),
    # 加拿大
    ('yf',   '^GSPTSE',  'GSPTSE.CA', '加拿大TSX',  'CA', 'CAD'),
    # 巴西
    ('yf',   '^BVSP',    'BVSP.BR',   '巴西Bovespa','BR', 'BRL'),
    # 大类资产/商品
    ('yf',   'DX-Y.NYB', 'DXY.IDX',   '美元指数',   'IDX', 'USD'),
    ('yf',   'GC=F',     'XAU.CMD',   '黄金/美元',   'CMD', 'USD'),
    ('yf',   'BTC-USD',  'BTC.CCY',   '比特币/美元', 'CCY', 'USD'),
    ('yf',   'CL=F',     'CL.CMD',    'WTI 原油',   'CMD', 'USD'),
]


def log(msg):
    ts = datetime.now().strftime('%H:%M:%S')
    print(f'[{ts}] {msg}')


def db_conn():
    return pymysql.connect(**DB_CONFIG)


def ensure_stock(db_symbol, name, market, currency):
    """确保 stocks 表有记录，返回 stock_id"""
    conn = db_conn()
    cur = conn.cursor()
    cur.execute("SELECT id FROM stocks WHERE symbol=%s", (db_symbol,))
    row = cur.fetchone()
    if row:
        conn.close()
        return row[0]
    try:
        cur.execute(
            "INSERT IGNORE INTO stocks (symbol, name, market, currency) VALUES (%s,%s,%s,%s)",
            (db_symbol, name, market, currency)
        )
        conn.commit()
    except Exception as e:
        log(f'  [WARN] 插入stocks失败 {db_symbol}: {e}')
    cur.execute("SELECT id FROM stocks WHERE symbol=%s", (db_symbol,))
    row = cur.fetchone()
    conn.close()
    return row[0] if row else None


def fetch_sina_kline(sina_code):
    """从新浪财经获取日K线数据，返回 list of dict"""
    url = (f"https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
           f"CN_MarketData.getKLineData?symbol={sina_code}&scale=240&ma=no&datalen={YEARS_BACK * 260}")
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={
                'User-Agent': 'Mozilla/5.0',
                'Referer': 'https://finance.sina.com.cn/',
            })
            with urllib.request.urlopen(req, timeout=15) as resp:
                body = resp.read().decode('utf-8')
            data = json.loads(body)
            if not isinstance(data, list):
                raise ValueError(f"Unexpected response: {body[:200]}")
            return data
        except Exception as e:
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
            else:
                raise e


def fetch_yf_kline(ticker):
    """从 Yahoo Finance 获取日K线，返回 list of (date_str, open, close, high, low, volume)"""
    t = yf.Ticker(ticker)
    hist = t.history(start=START_DATE, end=END_DATE, interval='1d')
    if hist.empty:
        return []
    rows = []
    for dt, row in hist.iterrows():
        trade_date = dt.strftime('%Y-%m-%d')
        try:
            o = float(row['Open'])
            c = float(row['Close'])
            h = float(row['High'])
            l = float(row['Low'])
            v = int(float(row['Volume']))
            if any(math.isnan(x) for x in [o, c, h, l]):
                continue
            rows.append((trade_date, o, c, h, l, v))
        except (ValueError, KeyError):
            continue
    return rows


def fetch_ak_kline(symbol):
    """从 akshare (东方财富) 获取 HK 指数日K线"""
    df = ak.stock_hk_index_daily_em(symbol=symbol)
    if df.empty:
        return []
    rows = []
    for _, row in df.iterrows():
        try:
            d = row['date'].strftime('%Y-%m-%d')
            o = round(float(row['open']), 4)
            c = round(float(row['latest']), 4)
            h = round(float(row['high']), 4)
            l = round(float(row['low']), 4)
            v = 0  # akshare 的 HK 指数不含成交量
            if any(math.isnan(x) for x in [o, c, h, l]):
                continue
            rows.append((d, o, c, h, l, v))
        except (ValueError, KeyError):
            continue
    return rows


def write_prices(stock_id, rows):
    """批量写入 stock_prices。rows: [(trade_date, open, close, high, low, volume), ...]"""
    if not rows:
        return 0
    conn = db_conn()
    cur = conn.cursor()
    db_rows = [(stock_id, d, o, c, h, l, v) for (d, o, c, h, l, v) in rows]
    cur.executemany(
        """INSERT INTO stock_prices (stock_id, trade_date, open, close, high, low, volume)
           VALUES (%s, %s, %s, %s, %s, %s, %s)
           ON DUPLICATE KEY UPDATE
           open=VALUES(open), close=VALUES(close), high=VALUES(high),
           low=VALUES(low), volume=VALUES(volume)""",
        db_rows
    )
    conn.commit()
    n = cur.rowcount
    conn.close()
    return n


# ============================================================
# 主流程
# ============================================================
def main():
    log('=' * 60)
    log(f'中港美9大指数历史日K线抓取 (2年)')
    log(f'区间: {START_DATE} ~ {END_DATE}')
    log('=' * 60 + '\n')

    total_rows = 0

    for source, ticker, db_symbol, name, market, currency in INDICES:
        print(f'  {db_symbol:12s} {name:8s}  ', end='', flush=True)

        stock_id = ensure_stock(db_symbol, name, market, currency)
        if not stock_id:
            print('-> 跳过（无法获取stock_id）')
            continue

        try:
            if source == 'sina':
                data = fetch_sina_kline(ticker)
                rows = []
                for item in data:
                    try:
                        d = item['day']
                        o = round(float(item['open']), 4)
                        c = round(float(item['close']), 4)
                        h = round(float(item['high']), 4)
                        l = round(float(item['low']), 4)
                        v = int(float(item['volume']))
                        rows.append((d, o, c, h, l, v))
                    except (ValueError, KeyError):
                        continue
            elif source == 'ak':
                rows = fetch_ak_kline(ticker)
            else:  # yf
                rows = fetch_yf_kline(ticker)

            if rows:
                n = write_prices(stock_id, rows)
                print(f'-> {len(rows)} 天K线, 写入/更新 {n} 行')
                total_rows += n
            else:
                print('-> 无数据')
        except Exception as e:
            print(f'-> 失败: {e}')

        time.sleep(PAUSE)

    log('\n' + '=' * 60)
    log(f'完成！总计写入/更新 {total_rows:,} 行')
    log('=' * 60)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n已中断。")
    except Exception:
        traceback.print_exc()
