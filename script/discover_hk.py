"""一次性扫描腾讯API，补齐数据库中所有缺失的港股"""
import pymysql, requests, re, time

DB = dict(host='localhost', port=3306, user='root', password='REDACTED_DB_PW', database='investory', charset='utf8mb4')
conn = pymysql.connect(**DB)
cur = conn.cursor()

# Get existing codes
cur.execute("SELECT REPLACE(symbol, '.HK', '') FROM stocks WHERE market='HK'")
existing = {row[0] for row in cur.fetchall()}
print(f"现有港股: {len(existing)} 只")

found = 0
for code5d in [f'{c:05d}' for c in range(1, 10000)]:
    if code5d in existing: continue
    try:
        r = requests.get(f'http://qt.gtimg.cn/q=hk{code5d}', timeout=5)
        r.encoding = 'gbk'
        m = re.search(r'"\d+~([^~]+)~', r.text)
        if not m: continue
        name = m.group(1).strip()
        if not name or name == code5d or name.isdigit(): continue
        symbol = f'{code5d}.HK'
        cur.execute("INSERT IGNORE INTO stocks (symbol, name, market, currency) VALUES (%s,%s,'HK','HKD')", (symbol, name))
        conn.commit()
        found += 1
        if found % 50 == 0: print(f"  已发现 {found} 只新股 ({code5d})")
    except: pass
    time.sleep(0.01)

print(f"完成！新增港股: {found} 只")
cur.execute("SELECT COUNT(*) FROM stocks WHERE market='HK'")
print(f"港股总数: {cur.fetchone()[0]}")
conn.close()
