package com.investory.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 股票历史日 K 线价格实体类。
 * <p>
 * 对应数据库 stock_price 表，存储每只股票每个交易日的开高低收量五项行情数据。
 * 由爬虫定时任务批量写入，供回测、净值曲线及历史分析功能查询使用。
 * </p>
 */
public class StockPrice {

    /** 数据库自增主键 */
    private Long id;

    /** 关联的股票 ID，外键引用 stock 表 */
    private Long stockId;

    /** 交易日期（自然日，非交易日无记录） */
    private LocalDate tradeDate;

    /** 开盘价，使用 BigDecimal 保证精度 */
    private BigDecimal open;

    /** 收盘价，使用 BigDecimal 保证精度 */
    private BigDecimal close;

    /** 当日最高价，使用 BigDecimal 保证精度 */
    private BigDecimal high;

    /** 当日最低价，使用 BigDecimal 保证精度 */
    private BigDecimal low;

    /** 成交量（手数或股数，根据数据源约定） */
    private Long volume;

    /** 无参构造器，供 JdbcTemplate RowMapper 及序列化框架使用 */
    public StockPrice() {}

    /**
     * 获取主键 ID。
     *
     * @return 数据库自增主键
     */
    public Long getId() { return id; }

    /**
     * 设置主键 ID。
     *
     * @param id 数据库自增主键
     */
    public void setId(Long id) { this.id = id; }

    /**
     * 获取关联股票 ID。
     *
     * @return stock 表主键
     */
    public Long getStockId() { return stockId; }

    /**
     * 设置关联股票 ID。
     *
     * @param stockId stock 表主键
     */
    public void setStockId(Long stockId) { this.stockId = stockId; }

    /**
     * 获取交易日期。
     *
     * @return 交易日 LocalDate
     */
    public LocalDate getTradeDate() { return tradeDate; }

    /**
     * 设置交易日期。
     *
     * @param tradeDate 交易日 LocalDate
     */
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    /**
     * 获取开盘价。
     *
     * @return 开盘价
     */
    public BigDecimal getOpen() { return open; }

    /**
     * 设置开盘价。
     *
     * @param open 开盘价
     */
    public void setOpen(BigDecimal open) { this.open = open; }

    /**
     * 获取收盘价。
     *
     * @return 收盘价
     */
    public BigDecimal getClose() { return close; }

    /**
     * 设置收盘价。
     *
     * @param close 收盘价
     */
    public void setClose(BigDecimal close) { this.close = close; }

    /**
     * 获取当日最高价。
     *
     * @return 最高价
     */
    public BigDecimal getHigh() { return high; }

    /**
     * 设置当日最高价。
     *
     * @param high 最高价
     */
    public void setHigh(BigDecimal high) { this.high = high; }

    /**
     * 获取当日最低价。
     *
     * @return 最低价
     */
    public BigDecimal getLow() { return low; }

    /**
     * 设置当日最低价。
     *
     * @param low 最低价
     */
    public void setLow(BigDecimal low) { this.low = low; }

    /**
     * 获取成交量。
     *
     * @return 成交量（手数或股数）
     */
    public Long getVolume() { return volume; }

    /**
     * 设置成交量。
     *
     * @param volume 成交量（手数或股数）
     */
    public void setVolume(Long volume) { this.volume = volume; }
}
