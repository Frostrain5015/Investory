package com.investory.dao;

import com.investory.model.StockPrice;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * 股票历史行情价格数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code stock_prices}（股票历史行情表）</p>
 *
 * <p>该表以 {@code (stock_id, trade_date)} 作为唯一约束，
 * 每行记录某只股票在某个交易日的 OHLCV（开盘价、最高价、最低价、收盘价、成交量）数据。
 * 数据由爬虫定时抓取并写入，支持 upsert 操作以幂等更新。</p>
 *
 * <p>收盘价（close）是计算持仓市值、每日净值、盈亏的核心字段。</p>
 */
public class StockPriceDao extends BaseDao {

    /**
     * 将结果集当前行映射为 {@link StockPrice} 对象。
     *
     * @param rs 已定位到目标行的结果集
     * @return 填充好字段的 {@link StockPrice} 实例
     * @throws SQLException 读取字段时可能抛出的数据库异常
     */
    private StockPrice map(ResultSet rs) throws SQLException {
        StockPrice p = new StockPrice();
        p.setId(rs.getLong("id"));                          // 行情记录主键
        p.setStockId(rs.getLong("stock_id"));               // 关联股票 ID
        Date d = rs.getDate("trade_date");
        if (d != null) p.setTradeDate(d.toLocalDate());    // 交易日期（Date → LocalDate）
        p.setOpen(rs.getBigDecimal("open"));                // 开盘价
        p.setClose(rs.getBigDecimal("close"));              // 收盘价（最重要字段）
        p.setHigh(rs.getBigDecimal("high"));                // 当日最高价
        p.setLow(rs.getBigDecimal("low"));                  // 当日最低价
        p.setVolume(rs.getLong("volume"));                  // 成交量（股）
        return p;
    }

    /**
     * 查询指定股票的最新一条行情记录（按交易日期降序取第一条）。
     *
     * <p>用于获取当前最新收盘价，计算持仓实时市值。</p>
     *
     * @param stockId 股票 ID
     * @return 最新行情对象，无数据时返回 {@code null}
     */
    public StockPrice findLatest(long stockId) {
        return queryOne(
            "SELECT * FROM stock_prices WHERE stock_id = ? ORDER BY trade_date DESC LIMIT 1",
            this::map, stockId);
    }

    /**
     * 查询指定股票最近两个交易日的行情记录，按交易日期降序排列。
     *
     * <p>用于计算日涨跌幅：最新收盘价相对于前一交易日收盘价的变化百分比。</p>
     *
     * @param stockId 股票 ID
     * @return 最近 2 条行情列表（index 0 为最新，index 1 为前一日）；不足 2 条时返回实际数量
     */
    public List<StockPrice> findLatestTwo(long stockId) {
        return query(
            "SELECT * FROM stock_prices WHERE stock_id = ? ORDER BY trade_date DESC LIMIT 2",
            this::map, stockId);
    }

    /**
     * 查询指定股票在某日期范围内的历史行情，按交易日期升序排列。
     *
     * <p>SQL 逻辑：使用 {@code BETWEEN ? AND ?} 筛选闭区间内的记录，
     * 结果按日期升序排列，用于绘制 K 线图或计算区间收益率。</p>
     *
     * @param stockId 股票 ID
     * @param from    查询起始日期（含）
     * @param to      查询结束日期（含）
     * @return 日期范围内的行情列表，按 trade_date 升序排列
     */
    public List<StockPrice> findRange(long stockId, LocalDate from, LocalDate to) {
        return query(
            "SELECT * FROM stock_prices WHERE stock_id = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
            this::map, stockId, Date.valueOf(from), Date.valueOf(to));
    }

    /**
     * 插入或更新完整的 OHLCV 行情记录（upsert）。
     *
     * <p>SQL 逻辑：使用 MySQL 的 {@code INSERT ... ON DUPLICATE KEY UPDATE} 语法，
     * 以 {@code (stock_id, trade_date)} 唯一键判断：
     * <ul>
     *   <li>若该日行情不存在 → 执行 INSERT，写入完整 OHLCV</li>
     *   <li>若已存在 → 执行 UPDATE，覆盖 open / close / high / low / volume 五个字段</li>
     * </ul>
     * 爬虫补录历史数据或当日行情更新时均通过此方法写入。</p>
     *
     * @param p 行情对象（stockId、tradeDate、open、close、high、low、volume 不可为空）
     */
    public void upsert(StockPrice p) {
        update("""
            INSERT INTO stock_prices (stock_id, trade_date, open, close, high, low, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              open = VALUES(open), close = VALUES(close),
              high = VALUES(high), low  = VALUES(low),
              volume = VALUES(volume)
            """,
            p.getStockId(), Date.valueOf(p.getTradeDate()),
            p.getOpen(), p.getClose(), p.getHigh(), p.getLow(), p.getVolume());
    }

    /**
     * 获取指定股票的最新收盘价（快捷方法）。
     *
     * <p>内部委托 {@link #findLatest} 查询，再提取 close 字段。</p>
     *
     * @param stockId 股票 ID
     * @return 最新收盘价，无行情数据时返回 {@code null}
     */
    public BigDecimal findLatestClose(long stockId) {
        StockPrice sp = findLatest(stockId);
        return sp != null ? sp.getClose() : null;
    }

    /**
     * 仅插入或更新收盘价快照，不覆盖已有的完整 OHLCV 数据（close-only upsert）。
     *
     * <p>SQL 逻辑：INSERT 时将 open / high / low 均设为 close 值（无完整行情时的占位填充）；
     * ON DUPLICATE KEY UPDATE 时：
     * <ul>
     *   <li>close 字段：直接覆盖为新值（收盘价以最新抓取为准）</li>
     *   <li>volume 字段：使用 {@code IF(VALUES(volume) > volume, ...)} 保留较大值，
     *       避免盘中快照的小成交量覆盖收盘后的全天成交量</li>
     *   <li>open / high / low：不更新，保留原有完整行情数据</li>
     * </ul>
     * 适用于只有收盘价的简化行情源（如港股/美股盘后快速补价）。</p>
     *
     * @param stockId 股票 ID
     * @param date    行情日期
     * @param close   收盘价
     */
    public void upsertCloseOnly(long stockId, LocalDate date, BigDecimal close) {
        update("""
            INSERT INTO stock_prices (stock_id, trade_date, open, close, high, low, volume)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            ON DUPLICATE KEY UPDATE
              close = VALUES(close),
              volume = IF(VALUES(volume) > volume, VALUES(volume), volume)
            """,
            // open / high / low 均用 close 填充，volume 固定传 0（INSERT 时占位）
            stockId, Date.valueOf(date), close, close, close, close);
    }
}
