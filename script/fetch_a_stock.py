"""
A股增量日K线抓取脚本
- 抓取近5个交易日数据（前复权）
- 数据源: BaoStock
- 目标: MySQL investory 库 stock_prices 表
- 使用 INSERT ... ON DUPLICATE KEY UPDATE 防止重复 + 更新最新价

用法:
    python fetch_a_stock.py
"""

import baostock as bs
import pymysql
import pandas as pd
import time
import sys
import traceback
from datetime import datetime, timedelta

# ─────────────────────────────────────────
# 配置
# ─────────────────────────────────────────
DB_CONFIG = dict(
    host="localhost",
    port=3306,
    db="investory",
    user="root",
    password="REDACTED_DB_PW",
    charset="utf8mb4",
)

# 抓取近 5 个日历日（足以覆盖最多 2 个周末 + 节假日）
DAYS_BACK = 7
START_DATE = (datetime.today() - timedelta(days=DAYS_BACK)).strftime("%Y-%m-%d")
END_DATE   = datetime.today().strftime("%Y-%m-%d")

REQUEST_DELAY = 0.15   # BaoStock 请求间隔

# ─────────────────────────────────────────
# 工具函数
# ─────────────────────────────────────────

def get_conn():
    return pymysql.connect(**DB_CONFIG)


def bs_code_to_symbol(bs_code: str) -> str:
    """sh.600000 -> 1.600000"""
    exch, code = bs_code.split(".")
    prefix = "1" if exch == "sh" else "0"
    return f"{prefix}.{code}"


def bs_code_to_market(bs_code: str) -> str:
    return "SH" if bs_code.startswith("sh.") else "SZ"


# ─────────────────────────────────────────
# Step 1: 同步 stocks 表（增量，新增股票才入库）
# ─────────────────────────────────────────

def sync_stock_list(conn) -> pd.DataFrame:
    """
    从 BaoStock 获取全部 A 股列表，新增记录写入 stocks 表。
    返回 DataFrame: bs_code, symbol, name, market, stock_id
    """
    print("[1/3] 获取 A 股股票列表...")
    rs = bs.query_stock_basic()
    rows = []
    while rs.error_code == "0" and rs.next():
        rows.append(rs.get_row_data())
    df = pd.DataFrame(rows, columns=rs.fields)

    df = df[df["code"].str.startswith(("sh.", "sz."))].copy()
    df = df[df["type"] == "1"].copy()
    df = df[df["outDate"] == ""].copy()
    df["bs_code"] = df["code"]
    df["symbol"]  = df["bs_code"].apply(bs_code_to_symbol)
    df["market"]  = df["bs_code"].apply(bs_code_to_market)
    df["name"]    = df["code_name"]
    df = df[["bs_code", "symbol", "name", "market"]].drop_duplicates(subset=["symbol"])
    print(f"    共 {len(df)} 只 A 股（沪 {(df.market=='SH').sum()}，深 {(df.market=='SZ').sum()}）")

    cursor = conn.cursor()
    sql = ("INSERT IGNORE INTO stocks (symbol, name, market, currency) "
           "VALUES (%s, %s, %s, 'CNY')")
    insert_rows = [(r["symbol"], r["name"], r["market"]) for _, r in df.iterrows()]
    if insert_rows:
        cursor.executemany(sql, insert_rows)
        conn.commit()
        print(f"    stocks 表同步完成，新增 {cursor.rowcount} 条")
    else:
        print(f"    stocks 表同步完成，新增 0 条（无新增）")

    cursor.execute("SELECT id, symbol FROM stocks WHERE market IN ('SH','SZ')")
    id_map = {row[1]: row[0] for row in cursor.fetchall()}
    df["stock_id"] = df["symbol"].map(id_map)
    df = df[df["stock_id"].notna()].copy()
    cursor.close()
    return df.reset_index(drop=True)


# ─────────────────────────────────────────
# Step 2: 抓取单只 + 写入（UPSERT）
# ─────────────────────────────────────────

def fetch_and_upsert(conn, stock_id: int, bs_code: str) -> int:
    """
    抓取一只股票近5交易日日K（前复权），写入 stock_prices。
    使用 INSERT ... ON DUPLICATE KEY UPDATE，重复则更新最新价。
    返回写入/更新行数。
    """
    rs = bs.query_history_k_data_plus(
        bs_code,
        "date,open,high,low,close,volume",
        start_date=START_DATE,
        end_date=END_DATE,
        frequency="d",
        adjustflag="2",   # 前复权
    )
    if rs.error_code != "0":
        raise RuntimeError(f"BaoStock error {rs.error_code}: {rs.error_msg}")

    data = []
    while rs.next():
        data.append(rs.get_row_data())

    if not data:
        return 0

    df = pd.DataFrame(data, columns=["trade_date", "open", "high", "low", "close", "volume"])
    df = df[(df["close"] != "") & (df["close"] != "0") & (df["volume"] != "")].copy()
    if df.empty:
        return 0

    df["trade_date"] = pd.to_datetime(df["trade_date"]).dt.date
    for col in ["open", "high", "low", "close"]:
        df[col] = df[col].astype(float).round(4)
    df["volume"] = df["volume"].astype(float).astype(int)
    df["stock_id"] = stock_id

    rows = [
        (r["stock_id"], r["trade_date"],
         r["open"], r["close"], r["high"], r["low"], r["volume"],
         r["open"], r["close"], r["high"], r["low"], r["volume"])
        for _, r in df.iterrows()
    ]

    cursor = conn.cursor()
    cursor.executemany(
        """INSERT INTO stock_prices
           (stock_id, trade_date, open, close, high, low, volume)
           VALUES (%s, %s, %s, %s, %s, %s, %s)
           ON DUPLICATE KEY UPDATE
           open=%s, close=%s, high=%s, low=%s, volume=%s""",
        rows,
    )
    conn.commit()
    n = cursor.rowcount
    cursor.close()
    return n


# ─────────────────────────────────────────
# 主流程
# ─────────────────────────────────────────

def main():
    print("=" * 60)
    print("  A股增量日K线抓取  (BaoStock → MySQL investory)")
    print(f"  区间: {START_DATE} ~ {END_DATE}（近{DAYS_BACK}个日历日）")
    print("=" * 60)

    lg = bs.login()
    if lg.error_code != "0":
        print(f"BaoStock 登录失败: {lg.error_msg}")
        sys.exit(1)
    print("BaoStock 登录成功\n")

    conn = get_conn()

    try:
        df_stocks = sync_stock_list(conn)
        total = len(df_stocks)
        print(f"\n[2/3] 开始抓取 {total} 只 A 股...\n")

        errors = []
        total_rows = 0

        for i, row in df_stocks.iterrows():
            bs_code  = row["bs_code"]
            name     = row["name"]
            stock_id = int(row["stock_id"])
            seq      = i + 1
            pct      = seq / total * 100

            print(f"  [{seq:5d}/{total}] {pct:5.1f}%  {bs_code}  {name:<12s}", end=" ", flush=True)

            try:
                n = fetch_and_upsert(conn, stock_id, bs_code)
                total_rows += n
                print(f"→ +{n} 行")
            except Exception as e:
                msg = str(e)[:100]
                print(f"→ 失败: {msg}")
                errors.append((bs_code, name, msg))

            time.sleep(REQUEST_DELAY)

    finally:
        conn.close()
        bs.logout()

    print("\n" + "=" * 60)
    print(f"  完成！本次写入/更新 {total_rows:,} 行")
    print(f"  成功: {total - len(errors)} 只，失败: {len(errors)} 只")
    if errors:
        print("\n  失败列表:")
        for sym, nm, msg in errors:
            print(f"    {sym}  {nm}: {msg}")
    print("=" * 60)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n已中断。")
        bs.logout()
        sys.exit(0)
    except Exception:
        traceback.print_exc()
        bs.logout()
        sys.exit(1)
