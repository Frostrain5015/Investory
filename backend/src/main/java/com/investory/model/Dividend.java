package com.investory.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股息/分红记录实体类。
 * <p>
 * 对应数据库 dividend 表，记录某只股票在特定权益登记日（recordDate）向持仓账户
 * 派发的股息详情，包括每股派息金额、持有股数及累计到账总金额。
 * 分红总额会被纳入 HoldingSnapshot 的稀释成本（dilutedCost）计算，
 * 以反映真实的持仓盈亏情况。
 * </p>
 */
public class Dividend {

    /** 数据库自增主键 */
    private Long id;

    /** 所属投资组合 ID，外键引用 portfolio 表 */
    private Long portfolioId;

    /** 派息股票 ID，外键引用 stock 表 */
    private Long stockId;

    /** 派息股票名称（冗余字段，避免联表查询），例如 "贵州茅台" */
    private String stockName;

    /** 派息股票 symbol（冗余字段），例如 "1.600519" */
    private String stockSymbol;

    /** 每股派息金额，单位与股票交易货币一致 */
    private BigDecimal amountPerShare;

    /** 权益登记日时的持有股数（快照值） */
    private BigDecimal sharesHeld;

    /**
     * 本次分红到账总金额。
     * 计算方式：totalAmount = amountPerShare × sharesHeld
     */
    private BigDecimal totalAmount;

    /** 权益登记日（股权登记日，非派息日），用于与持仓时间对齐 */
    private LocalDate recordDate;

    /** 记录创建时间，由数据库或业务层在插入时自动赋值 */
    private LocalDateTime createdAt;

    /** 无参构造器，供 JdbcTemplate RowMapper 及序列化框架使用 */
    public Dividend() {}

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
     * 获取派息股票 ID。
     *
     * @return stock 表主键
     */
    public Long getStockId() { return stockId; }

    /**
     * 设置派息股票 ID。
     *
     * @param stockId stock 表主键
     */
    public void setStockId(Long stockId) { this.stockId = stockId; }

    /**
     * 获取派息股票名称（冗余字段）。
     *
     * @return 股票中英文名称
     */
    public String getStockName() { return stockName; }

    /**
     * 设置派息股票名称（冗余字段）。
     *
     * @param stockName 股票中英文名称
     */
    public void setStockName(String stockName) { this.stockName = stockName; }

    /**
     * 获取派息股票 symbol（冗余字段）。
     *
     * @return 形如 "1.600519" 的股票 symbol
     */
    public String getStockSymbol() { return stockSymbol; }

    /**
     * 设置派息股票 symbol（冗余字段）。
     *
     * @param stockSymbol 形如 "1.600519" 的股票 symbol
     */
    public void setStockSymbol(String stockSymbol) { this.stockSymbol = stockSymbol; }

    /**
     * 获取每股派息金额。
     *
     * @return 每股派息金额
     */
    public BigDecimal getAmountPerShare() { return amountPerShare; }

    /**
     * 设置每股派息金额。
     *
     * @param amountPerShare 每股派息金额
     */
    public void setAmountPerShare(BigDecimal amountPerShare) { this.amountPerShare = amountPerShare; }

    /**
     * 获取权益登记日时的持有股数。
     *
     * @return 持有股数快照值
     */
    public BigDecimal getSharesHeld() { return sharesHeld; }

    /**
     * 设置权益登记日时的持有股数。
     *
     * @param sharesHeld 持有股数快照值
     */
    public void setSharesHeld(BigDecimal sharesHeld) { this.sharesHeld = sharesHeld; }

    /**
     * 获取本次分红到账总金额（= 每股派息 × 持有股数）。
     *
     * @return 分红总金额
     */
    public BigDecimal getTotalAmount() { return totalAmount; }

    /**
     * 设置本次分红到账总金额。
     *
     * @param totalAmount 分红总金额
     */
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    /**
     * 获取权益登记日。
     *
     * @return 股权登记日 LocalDate
     */
    public LocalDate getRecordDate() { return recordDate; }

    /**
     * 设置权益登记日。
     *
     * @param recordDate 股权登记日 LocalDate
     */
    public void setRecordDate(LocalDate recordDate) { this.recordDate = recordDate; }

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
}
