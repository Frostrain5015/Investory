"""
港股增量日K线抓取脚本
- 抓取近5个交易日数据（前复权）
- 数据源: 腾讯财经 API
- 目标: MySQL investory 库 stock_prices 表
- 使用 INSERT ... ON DUPLICATE KEY UPDATE 防止重复 + 更新最新价

用法:
    python fetch_hk_stock.py
"""

import pymysql
import requests
import time
import re
import sys
import traceback
from datetime import datetime, timedelta

# ============================================================
# 配置
# ============================================================
DB_CONFIG = dict(host='localhost', port=3306, user='root',
                 password='REDACTED_DB_PW', database='investory', charset='utf8mb4')

# 抓取近 5 个日历日（足以覆盖最多 2 个周末 + 节假日）
DAYS_BACK = 7
START_DATE = (datetime.now() - timedelta(days=DAYS_BACK)).strftime('%Y-%m-%d')
END_DATE   = datetime.now().strftime('%Y-%m-%d')

MARKET   = 'HK'
CURRENCY = 'HKD'
DELAY    = 0.05   # 请求间隔（秒）

# ============================================================
# 工具函数
# ============================================================
def log(msg):
    ts = datetime.now().strftime('%H:%M:%S')
    line = f'[{ts}] {msg}'
    print(line)

def db_conn():
    return pymysql.connect(**DB_CONFIG)

def symbol_to_tencent(code5d):
    """00001 -> hk00001"""
    return f'hk{code5d}'

# ============================================================
# Step 1: 从 DB 读取现有港股（增量，新增股票在外层单独处理）
# ============================================================
def get_hk_stocks_from_db():
    """从 stocks 表读取所有 HK 市场股票，返回 [(stock_id, symbol, name)]"""
    conn = db_conn()
    cur = conn.cursor()
    cur.execute(
        "SELECT id, symbol, name FROM stocks WHERE market=%s",
        (MARKET,)
    )
    rows = cur.fetchall()
    conn.close()
    return rows

# ============================================================
# Step 2: 同步新增港股（增量，抓取时发现不在 DB 中的则写入）
# ============================================================
def ensure_hk_stock(symbol, name):
    """确保 stocks 表有该港股记录，返回 stock_id"""
    conn = db_conn()
    cur = conn.cursor()
    cur.execute("SELECT id FROM stocks WHERE symbol=%s", (symbol,))
    row = cur.fetchone()
    if row:
        conn.close()
        return row[0]
    try:
        cur.execute(
            "INSERT IGNORE INTO stocks (symbol, name, market, currency) VALUES (%s,%s,%s,%s)",
            (symbol, name, MARKET, CURRENCY)
        )
        conn.commit()
    except Exception as e:
        log(f'  [WARN] 插入stocks失败 {symbol}: {e}')
    cur.execute("SELECT id FROM stocks WHERE symbol=%s", (symbol,))
    row = cur.fetchone()
    conn.close()
    return row[0] if row else None

# ============================================================
# Step 3: 抓取单只 K 线（前复权）
# ============================================================
def fetch_kline(code5d):
    """
    通过腾讯财经 API 抓取港股日K（前复权）。
    返回 [(date, open, close, high, low, volume)]，失败返回 []
    """
    tc_code = symbol_to_tencent(code5d)
    url = (f'https://web.ifzq.gtimg.cn/appstock/app/fqkline/get'
           f'?_var=kline_dayqfq&param={tc_code},day,{START_DATE},{END_DATE},730,qfq')
    try:
        r = requests.get(url, timeout=10)
        text = r.text
        m = re.search(r'=(\{.+})', text)
        if not m:
            return []
        data = m.group(1)
        import json
        data = json.loads(data)
        stock_data = data.get('data', {}).get(tc_code, {})
        klines = stock_data.get('day', [])
        rows = []
        for k in klines:
            try:
                rows.append((
                    k[0],           # date YYYY-MM-DD
                    float(k[1]),    # open
                    float(k[4]),    # close
                    float(k[2]),    # high
                    float(k[3]),    # low
                    int(float(k[5]))  # volume
                ))
            except (ValueError, IndexError):
                continue
        return rows
    except Exception:
        return []

# ============================================================
# Step 4: 写入 stock_prices（UPSERT）
# ============================================================
def upsert_klines(stock_id, rows):
    """使用 INSERT ON DUPLICATE KEY UPDATE 写入/更新 K 线"""
    if not rows:
        return 0
    conn = db_conn()
    cur = conn.cursor()
    try:
        # 构建 ON DUPLICATE KEY UPDATE 参数（open/close/high/low/volume 各出现两次）
        params = []
        for r in rows:
            params.append((
                stock_id, r[0], r[1], r[2], r[3], r[4], r[5],  # INSERT 部分
                r[1], r[2], r[3], r[4], r[5]                      # UPDATE 部分
            ))
        cur.executemany(
            """INSERT INTO stock_prices
               (stock_id, trade_date, open, close, high, low, volume)
               VALUES (%s, %s, %s, %s, %s, %s, %s)
               ON DUPLICATE KEY UPDATE
               open=%s, close=%s, high=%s, low=%s, volume=%s""",
            params
        )
        conn.commit()
        n = cur.rowcount
        return n
    except Exception as e:
        log(f'  写入失败 stock_id={stock_id}: {e}')
        return 0
    finally:
        conn.close()

# ============================================================
def fix_hk_names():
    """修复港股名称：对名称仅为代码的股票，从腾讯 API 拉取中文名"""
    conn = db_conn()
    cur = conn.cursor()
    cur.execute("SELECT id, symbol FROM stocks WHERE market='HK' AND (name REGEXP '^[0-9]+$' OR CHAR_LENGTH(name)<=5)")
    bad = cur.fetchall()
    if not bad:
        conn.close()
        return
    log(f'[0/3] 修复 {len(bad)} 只港股的名称...')
    fixed = 0
    for stock_id, symbol in bad:
        code5d = symbol.replace('.HK', '').replace('hk', '')
        try:
            r = requests.get(f'http://qt.gtimg.cn/q=hk{code5d}', timeout=5)
            r.encoding = 'gbk'
            text = r.text
            # Format: v_hk00001="1~长和~00001~..."
            m = re.search(r'"\d+~([^~]+)~', text)
            if m:
                name = m.group(1).strip()
                if name and name != code5d and not name.isdigit():
                    cur.execute("UPDATE stocks SET name=%s WHERE id=%s", (name, stock_id))
                    conn.commit()
                    fixed += 1
        except Exception:
            pass
        time.sleep(0.02)
    conn.close()
    log(f'  修复完成: {fixed} 只')

# 主流程
# ============================================================
def main():
    print("=" * 60)
    print("  港股增量日K线抓取  (腾讯财经 → MySQL investory)")
    print(f"  区间: {START_DATE} ~ {END_DATE}（近{DAYS_BACK}个日历日）")
    print("=" * 60 + "\n")

    # Step 0: 修复名称缺失的港股
    fix_hk_names()

    # Step 1: 读取 DB 中的港股列表
    stocks = get_hk_stocks_from_db()
    print(f"[1/3] 从数据库读取港股: {len(stocks)} 只\n")
    if not stocks:
        print("未找到任何港股记录，请先运行全量抓取脚本。")
        return

    # Step 2: 逐只抓取 + 写入
    errors = []
    total_rows = 0
    total = len(stocks)

    print(f"[2/3] 开始抓取 {total} 只港股...\n")

    for i, (stock_id, symbol, name) in enumerate(stocks):
        # symbol 格式: 00001.HK → 提取 5 位代码
        code5d = symbol.replace('.HK', '').replace('hk', '')
        seq = i + 1
        pct = seq / total * 100

        print(f"  [{seq:5d}/{total}] {pct:5.1f}%  {symbol}  {name:<12s}", end=" ", flush=True)

        rows = fetch_kline(code5d)
        if rows:
            n = upsert_klines(stock_id, rows)
            total_rows += n
            print(f"→ +{n} 行")
        else:
            print("→ 无数据（停牌或API异常）")

        time.sleep(DELAY)

    print("\n" + "=" * 60)
    print(f"  完成！本次写入/更新 {total_rows:,} 行")
    print(f"  成功: {total - len(errors)} 只，失败: {len(errors)} 只")
    if errors:
        print("\n  失败列表:")
        for sym, nm, msg in errors:
            print(f"    {sym}  {nm}: {msg}")
    print("=" * 60)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n已中断。")
        sys.exit(0)
    except Exception:
        traceback.print_exc()
        sys.exit(1)
