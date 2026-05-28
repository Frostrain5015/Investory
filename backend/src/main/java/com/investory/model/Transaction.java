package com.investory.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交易记录实体类。
 * <p>
 * 对应数据库 transaction 表，记录用户在某个投资组合中对某只股票执行的每一笔买卖操作。
 * 交易记录是持仓（Holding）计算的原始数据来源：每次新增、修改或删除交易后，
 * 业务层会重新聚合所有相关交易以更新持仓的平均成本、总股数等字段。
 * 冗余的 stockName、stockSymbol、stockMarket 字段避免了交易历史列表的联表查询。
 * </p>
 */
public class Transaction {

    /** 数据库自增主键 */
    private Long id;

    /** 所属投资组合 ID，外键引用 portfolio 表 */
    private Long portfolioId;

    /** 交易股票 ID，外键引用 stock 表 */
    private Long stockId;

    /** 股票名称（冗余字段），例如 "贵州茅台" */
    private String stockName;

    /** 股票 symbol（冗余字段），例如 "1.600519" */
    private String stockSymbol;

    /** 股票所属市场（冗余字段），例如 "SH" */
    private String stockMarket;

    /** 交易货币，例如 CNY / HKD / USD（与 Stock.currency 保持一致） */
    private String currency;

    /** 交易类型：BUY（买入）/ SELL（卖出） */
    private String type;         // BUY / SELL

    /** 交易股数，买入为正，卖出为正（方向由 type 字段区分） */
    private BigDecimal shares;

    /** 成交价格，单位为交易货币 */
    private BigDecimal price;

    /** 交易手续费，单位为交易货币（含佣金、印花税等所有费用） */
    private BigDecimal fee;

    /** 交易日期（成交日，T+0 即当天） */
    private LocalDate tradeDate;

    /** 交易备注，用户可选填，例如记录操作原因 */
    private String note;

    /** 记录创建时间，由数据库或业务层在插入时自动赋值 */
    private LocalDateTime createdAt;

    /** 无参构造器，供 JdbcTemplate RowMapper 及序列化框架使用 */
    public Transaction() {}

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
     * 获取所属投资组合 ID。
     *
     * @return portfolio 表主键
     */
    public Long getPortfolioId() { return portfolioId; }

    /**
     * 设置所属投资组合 ID。
     *
     * @param portfolioId portfolio 表主键
     */
    public void setPortfolioId(Long portfolioId) { this.portfolioId = portfolioId; }

    /**
     * 获取交易股票 ID。
     *
     * @return stock 表主键
     */
    public Long getStockId() { return stockId; }

    /**
     * 设置交易股票 ID。
     *
     * @param stockId stock 表主键
     */
    public void setStockId(Long stockId) { this.stockId = stockId; }

    /**
     * 获取股票名称（冗余字段）。
     *
     * @return 股票中英文名称
     */
    public String getStockName() { return stockName; }

    /**
     * 设置股票名称（冗余字段）。
     *
     * @param stockName 股票中英文名称
     */
    public void setStockName(String stockName) { this.stockName = stockName; }

    /**
     * 获取股票 symbol（冗余字段）。
     *
     * @return 形如 "1.600519" 的股票 symbol
     */
    public String getStockSymbol() { return stockSymbol; }

    /**
     * 设置股票 symbol（冗余字段）。
     *
     * @param stockSymbol 形如 "1.600519" 的股票 symbol
     */
    public void setStockSymbol(String stockSymbol) { this.stockSymbol = stockSymbol; }

    /**
     * 获取股票所属市场（冗余字段）。
     *
     * @return SH / SZ / HK / US 之一
     */
    public String getStockMarket() { return stockMarket; }

    /**
     * 设置股票所属市场（冗余字段）。
     *
     * @param stockMarket SH / SZ / HK / US 之一
     */
    public void setStockMarket(String stockMarket) { this.stockMarket = stockMarket; }

    /**
     * 获取交易货币。
     *
     * @return CNY / HKD / USD 之一
     */
    public String getCurrency() { return currency; }

    /**
     * 设置交易货币。
     *
     * @param currency CNY / HKD / USD 之一
     */
    public void setCurrency(String currency) { this.currency = currency; }

    /**
     * 获取交易类型。
     *
     * @return "BUY" 或 "SELL"
     */
    public String getType() { return type; }

    /**
     * 设置交易类型。
     *
     * @param type "BUY" 或 "SELL"
     */
    public void setType(String type) { this.type = type; }

    /**
     * 获取交易股数。
     *
     * @return 本次交易的股数（方向由 type 决定）
     */
    public BigDecimal getShares() { return shares; }

    /**
     * 设置交易股数。
     *
     * @param shares 本次交易的股数
     */
    public void setShares(BigDecimal shares) { this.shares = shares; }

    /**
     * 获取成交价格。
     *
     * @return 每股成交价格（交易货币）
     */
    public BigDecimal getPrice() { return price; }

    /**
     * 设置成交价格。
     *
     * @param price 每股成交价格（交易货币）
     */
    public void setPrice(BigDecimal price) { this.price = price; }

    /**
     * 获取交易手续费。
     *
     * @return 本次交易的总手续费（含所有费用）
     */
    public BigDecimal getFee() { return fee; }

    /**
     * 设置交易手续费。
     *
     * @param fee 本次交易的总手续费
     */
    public void setFee(BigDecimal fee) { this.fee = fee; }

    /**
     * 获取交易日期。
     *
     * @return 成交日 LocalDate
     */
    public LocalDate getTradeDate() { return tradeDate; }

    /**
     * 设置交易日期。
     *
     * @param tradeDate 成交日 LocalDate
     */
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    /**
     * 获取交易备注。
     *
     * @return 用户填写的备注信息，可为 null
     */
    public String getNote() { return note; }

    /**
     * 设置交易备注。
     *
     * @param note 用户填写的备注信息
     */
    public void setNote(String note) { this.note = note; }

    /**
     * 获取记录创建时间。
     *
     * @return 插入数据库时的时间戳
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * 设置记录创建时间。
     *
     * @param createdAt 插入数据库时的时间戳
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * 计算本次交易的总费用（含手续费）。
     * <p>
     * 计算方式：shares × price + fee
     * 该值用于在买入时计算新的平均持仓成本。
     * </p>
     *
     * @return 本次交易总成本（= 成交金额 + 手续费）
     */
    public BigDecimal getTotalCost() {
        // 成交金额 = 股数 × 成交价，再加上手续费得到总持仓成本
        return shares.multiply(price).add(fee);
    }
}
