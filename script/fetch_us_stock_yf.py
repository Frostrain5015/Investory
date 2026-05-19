"""
美股增量日K线抓取脚本
- 抓取近5个交易日数据（前复权）
- 数据源: Yahoo Finance (yfinance)
- 目标: MySQL investory 库 stock_prices 表
- 使用 INSERT ... ON DUPLICATE KEY UPDATE 防止重复 + 更新最新价

用法:
    python fetch_us_stock_yf.py
"""

import pymysql
import yfinance as yf
import time
import traceback
from datetime import datetime, timedelta

# ============================================================
# 配置
# ============================================================
DB_CONFIG = dict(host='localhost', port=3306, user='root',
                 password='REDACTED_DB_PW', database='investory', charset='utf8mb4')

MARKET   = 'US'
CURRENCY = 'USD'

# 抓取近 5 个日历日（足以覆盖最多 2 个周末 + 节假日）
DAYS_BACK = 7
START_DATE = (datetime.now() - timedelta(days=DAYS_BACK)).strftime('%Y-%m-%d')
END_DATE   = datetime.now().strftime('%Y-%m-%d')

PAUSE = 0.2   # 请求间隔（秒），防 Yahoo 限速

# ============================================================
# 股票列表（yfinance ticker → 数据库 symbol = XXXXX.US）
# ============================================================
US_TICKERS = sorted([
    # 科技
    'AAPL','MSFT','GOOGL','GOOG','AMZN','NVDA','META','TSLA','AVGO','ADBE',
    'CSCO','ORCL','CRM','ACN','IBM','INTC','AMD','QCOM','TXN','AMAT','MU',
    'NOW','INTU','SNPS','CDNS','PANW','CRWD','NET','ZS','DDOG','SNOW','PLTR',
    'UBER','LYFT','DASH','ABNB','COIN','HOOD','RBLX','SNAP','PINS','MTCH',
    'TWLO','ZM','DOCU','TEAM','WDAY','OKTA','SPLK','ESTC','MDB','DKNG','PENN',
    'MELI','SE','GLBE','VEEV','CDAY','APP','LNW','MTDR','SMCI','ARM','TSM',
    'ASML','TTD','NFLX','DIS','CMCSA','T','VZ','TMUS','DISH','CHTR','WBD',
    'PARA','FOX','FOXA','EA','TTWO','U','SKLZ','GPRO','AMC','BB','NOK',
    'INFY','CTS','WIT','HCLTECH','LUMN','PLT','COMM','GLDS',
    # 互联网/中概
    'BABA','BIDU','JD','PDD','NTES','NIO','XPEV','LI','BILI','TCEHY','TAL',
    'EDU','TCOM','VIPS','BEKE','MOMO','IQ','LX','ZTO','YUMC','GSX','GOTU',
    'LU','LXEH','ME','HUYA','DOYU','YY','KC','QTT','MNSO','MPU',
    'IREN','RIOT','MARA','RLX','GRPN',
    # 金融
    'SQ','PYPL','AFRM','SOFI','UPST','G','O','MCO','NTRS','SCHW',
    'RJF','RF','KEY','HBAN','CFG','STT','BK','JPM','BAC','WFC','C','GS',
    'MS','BLK','V','MA','AXP','DFS','COF','SYF','ALL','PGR','TRV','MET',
    'PRU','AFL','FIS','FISV','GPN','PAYX','ADP','NDAQ','ICE','CB','MMC',
    'AON','WLTW','AJG','BRO','CTSH','IT','GLW','KEYS','TDY','HP','SLF',
    'BNS','TD','RY','CM','MQ','NU','AFRI',
    # 能源/工业
    'SLB','HAL','DVN','EOG','PXD','MPC','PSX','VLO','WMB','KMI','OXY',
    'APA','COP','CVX','XOM','UNP','CSX','NSC','GWW','FAST','PCAR','DE',
    'CAT','EMR','ETN','HON','UPS','FDX','RTX','BA','LMT','NOC','GD','LHX',
    'TDG','PH','ROK','ITW','MMM','GE','CARR','TT','JCI','HWM','CPRT','LAZ',
    # 公用/基建
    'AMT','PLD','CCI','EQIX','PSA','SPG','WELL','DLR','AVB','EQR','VTR',
    'SBAC','ARE','EXR','MAA','ESS','IRM','WY','D','SO','DUK','AEP','SRE',
    'PCG','EIX','DTE','FE','XEL','EXC','NEE','CP',
    # 消费/零售
    'HSY','K','MDLZ','GIS','KMB','CL','PG','KO','PEP','MCD','SBUX','WMT',
    'COST','TGT','LOW','HD','NKE','LULU','UAA','RL','VFC','TPR','NWL',
    'HAS','MAT','EL','COTY','CHD','CLX','PKG','IP','LYB','PPG','SHW','RPM',
    'EMN','CE','FMC','CF','MOS','NEM','GOLD','NUE','STLD','FCX','AA','DOW',
    'DD','ECL','IFF','APD','LIN','AP','MLM','VMC','CEM','EXP','SUM','ST',
    # 医药
    'ABT','ABBV','JNJ','UNH','PFE','LLY','NVS','MRK','BMY','AMGN','GILD',
    'VRTX','REGN','BIIB','MRNA','AZN','ISRG','MDT','SYK','ZTS','BSX','EW',
    'DXCM','TMO','DHR','IQV','IDXX','LH','DGX','ALGN','MTD','WAT','PKI',
    'HOLX','PODD','AXNX','INCY','RMD','STE','HSIC','XRAY','COO','VTRS','CNC',
    'CI','HUM','ELV','ANTM','MOH','WBA','CVS','MCK','CAH','ABC',
    # 新能源
    'ENPH','SEDG','FSLR','RUN','SPWR','NOVA','BE','PLUG','BLDP','FCEL','CLNE','GEVO','RS',
    # 媒体/娱乐
    'ROKU','YELP','WORK','HUBS','SMAR','SQSP','FVRR','UPWK','WIX','GLOB','EPAM',
    'APPI','FROG','KRC','PATH','DAY','COUP','BKNG','EXPE','TRIP','MAR','HLT',
    'RCL','CCL','NCLH','WYNN','MGM','FUBO','W','STNE','PAGS','SALES',
    # 生物科技
    'BNTX','NVAX','INO','SGEN','Aphria','CRON',
])

# 去重
US_TICKERS = sorted(set(US_TICKERS))

# ============================================================
# 工具
# ============================================================
def log(msg):
    ts = datetime.now().strftime('%H:%M:%S')
    line = f'[{ts}] {msg}'
    print(line)

def db_conn():
    return pymysql.connect(**DB_CONFIG)

def ensure_stock(ticker):
    """确保 stocks 表有记录，返回 stock_id（不在则尝试写入）"""
    conn = db_conn()
    cur = conn.cursor()
    db_symbol = f'{ticker}.US'
    cur.execute("SELECT id FROM stocks WHERE symbol=%s", (db_symbol,))
    row = cur.fetchone()
    if row:
        conn.close()
        return row[0]
    # 不在 DB 中则尝试从 Yahoo 获取名称后写入
    name = ticker
    try:
        t = yf.Ticker(ticker)
        info = t.info
        name = info.get('longName') or info.get('shortName') or ticker
    except Exception:
        pass
    try:
        cur.execute(
            "INSERT IGNORE INTO stocks (symbol, name, market, currency) VALUES (%s,%s,%s,%s)",
            (db_symbol, name, MARKET, CURRENCY)
        )
        conn.commit()
    except Exception as e:
        log(f'  [WARN] 插入stocks失败 {db_symbol}: {e}')
    cur.execute("SELECT id FROM stocks WHERE symbol=%s", (db_symbol,))
    row = cur.fetchone()
    conn.close()
    return row[0] if row else None

def fetch_and_upsert(ticker, stock_id):
    """用 yfinance 抓取近5交易日 K 线，使用 UPSERT 写入数据库"""
    try:
        t = yf.Ticker(ticker)
        # 指定日期范围，不用 period='2y'
        hist = t.history(start=START_DATE, end=END_DATE, interval='1d')
        if hist.empty:
            return 0
        conn = db_conn()
        cur = conn.cursor()
        rows = []
        for dt, row in hist.iterrows():
            trade_date = dt.strftime('%Y-%m-%d')
            try:
                rows.append((
                    stock_id,
                    trade_date,
                    round(float(row['Open']), 4),
                    round(float(row['Close']), 4),
                    round(float(row['High']), 4),
                    round(float(row['Low']), 4),
                    int(float(row['Volume'])),
                    # ON DUPLICATE KEY UPDATE 部分
                    round(float(row['Open']), 4),
                    round(float(row['Close']), 4),
                    round(float(row['High']), 4),
                    round(float(row['Low']), 4),
                    int(float(row['Volume'])),
                ))
            except (ValueError, KeyError):
                continue
        if not rows:
            conn.close()
            return 0
        cur.executemany(
            """INSERT INTO stock_prices
               (stock_id, trade_date, open, close, high, low, volume)
               VALUES (%s, %s, %s, %s, %s, %s, %s)
               ON DUPLICATE KEY UPDATE
               open=%s, close=%s, high=%s, low=%s, volume=%s""",
            rows
        )
        conn.commit()
        n = cur.rowcount
        conn.close()
        return n
    except Exception as e:
        return 0

# ============================================================
# 主流程
# ============================================================
def main():
    log('=' * 60)
    log(f'美股增量日K线抓取  (Yahoo Finance → MySQL investory)')
    log(f'区间: {START_DATE} ~ {END_DATE}（近{DAYS_BACK}个日历日）')
    log(f'股票数量: {len(US_TICKERS)} 只')
    log('=' * 60 + '\n')

    total = len(US_TICKERS)
    errors = []
    total_rows = 0

    for idx, ticker in enumerate(US_TICKERS):
        db_symbol = f'{ticker}.US'
        seq = idx + 1
        pct = seq / total * 100

        print(f"  [{seq:5d}/{total}] {pct:5.1f}%  {db_symbol}", end=" ", flush=True)

        stock_id = ensure_stock(ticker)
        if not stock_id:
            print("→ 跳过（无法获取stock_id）")
            errors.append((ticker, 'no stock_id'))
            time.sleep(PAUSE)
            continue

        n = fetch_and_upsert(ticker, stock_id)
        if n > 0:
            print(f"→ +{n} 行")
            total_rows += n
        else:
            print("→ 无数据（停牌或API异常）")

        time.sleep(PAUSE)

    log('\n' + '=' * 60)
    log(f'完成！本次写入/更新 {total_rows:,} 行')
    log(f'成功: {total - len(errors)} 只，失败: {len(errors)} 只')
    if errors:
        log('\n失败列表:')
        for t, msg in errors[:20]:
            log(f'  {t}: {msg}')
        if len(errors) > 20:
            log(f'  ... 共 {len(errors)} 条')
    log('=' * 60)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n已中断。")
    except Exception:
        traceback.print_exc()
