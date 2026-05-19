"""仅对新发现的港股（无任何价格记录的）拉取日K"""
import pymysql, requests, re, time, json
from datetime import datetime, timedelta

DB = dict(host='localhost', port=3306, user='root', password='REDACTED_DB_PW',
          database='investory', charset='utf8mb4')
DAYS_BACK = 800
START = (datetime.now() - timedelta(days=DAYS_BACK)).strftime('%Y-%m-%d')
END   = datetime.now().strftime('%Y-%m-%d')

conn = pymysql.connect(**DB)
cur = conn.cursor()
cur.execute("""
    SELECT s.id, s.symbol FROM stocks s
    WHERE s.market='HK'
    AND NOT EXISTS (SELECT 1 FROM stock_prices sp WHERE sp.stock_id=s.id)
    ORDER BY s.symbol
""")
new = cur.fetchall()
print(f"需拉取: {len(new)} 只  ({START} ~ {END})")
print()

total_rows = 0
for idx, (sid, symbol) in enumerate(new):
    code5d = symbol.replace('.HK', '')
    url = (f'https://web.ifzq.gtimg.cn/appstock/app/fqkline/get'
           f'?_var=kline_dayqfq&param=hk{code5d},day,{START},{END},730,qfq')
    try:
        r = requests.get(url, timeout=10)
        m = re.search(r'=(\{.+})', r.text)
        if not m:
            print(f"  [{idx+1}] {symbol} → 正则未匹配")
            continue
        data = json.loads(m.group(1))
        klines = data.get('data', {}).get(f'hk{code5d}', {}).get('day', [])
        if not klines:
            print(f"  [{idx+1}] {symbol} → 无K线")
            continue
        rows = []
        for k in klines:
            try:
                rows.append((sid, k[0],
                    float(k[1]), float(k[4]), float(k[2]), float(k[3]), int(float(k[5])),
                    float(k[1]), float(k[4]), float(k[2]), float(k[3]), int(float(k[5]))))
            except Exception as ke:
                print(f"  [{idx+1}] {symbol} 行解析错: {ke}  k={k}")
                continue
        if rows:
            c2 = conn.cursor()
            sql = """INSERT INTO stock_prices (stock_id, trade_date, open, close, high, low, volume)
                     VALUES (%s,%s,%s,%s,%s,%s,%s)
                     ON DUPLICATE KEY UPDATE open=VALUES(open), close=VALUES(close),
                     high=VALUES(high), low=VALUES(low), volume=VALUES(volume)"""
            for r in rows:
                c2.execute(sql, r[:7])
            conn.commit(); c2.close()
            total_rows += len(rows)
            print(f"  [{idx+1}/{len(new)}] {symbol} → +{len(rows)} 行  (累计 {total_rows})")
        else:
            print(f"  [{idx+1}] {symbol} → 空数据")
    except Exception as e:
        print(f"  [{idx+1}] {symbol} → 异常: {e}")
    time.sleep(0.03)

print(f"\n完成！{len(new)} 只，写入 {total_rows} 行")
conn.close()
