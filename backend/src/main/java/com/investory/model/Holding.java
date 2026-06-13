package com.investory.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 持仓聚合实体类。
 * <p>
 * 对应数据库 holding 表，记录某个投资组合中某只股票的当前持仓状态，
 * 包括总股数、平均成本、稀释成本、累计投入及累计收到的分红总额。
 * 每次执行买卖交易（Transaction）后，业务层会重新计算并更新该记录。
 * 该实体是计算盈亏（HoldingSnapshot）的核心数据来源。
 * </p>
 */
public class Holding {

    /** 数据库自增主键 */
    private Long id;

    /** 所属投资组合 ID，外键引用 portfolio 表 */
    private Long portfolioId;

    /** 持仓股票 ID，外键引用 stock 表 */
    private Long stockId;

    /** 当前持有总股数（买入累计 - 卖出累计） */
    private BigDecimal totalShares;

    /**
     * 平均持仓成本（未考虑分红），单位为交易货币。
     * 计算方式：totalInvested / totalShares（仅含买入成本，不扣除分红）
     */
    private BigDecimal avgCost;

    /**
     * 稀释平均成本（扣除已收分红后的实际成本），单位为交易货币。
     * 计算方式：(totalInvested - totalDividends) / totalShares
     * 稀释成本越低，说明历史分红对成本的摊薄效果越显著。
     */
    private BigDecimal dilutedCost;

    /**
     * 历史累计投入金额（含手续费），单位为交易货币。
     * 随每笔买入交易累加，随每笔卖出交易按比例扣减。
     */
    private BigDecimal totalInvested;

    /**
     * 累计收到的分红总金额，单位为交易货币。
     * 每次录入分红记录（Dividend）后自动累加到此字段。
     */
    private BigDecimal totalDividends;

    /** 持仓最后更新时间，每次交易或分红操作后刷新 */
    private LocalDateTime updatedAt;

    /** 无参构造器，供 JdbcTemplate RowMapper 及序列化框架使用 */
    public Holding() {}

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
     * 获取持仓股票 ID。
     *
     * @return stock 表主键
     */
    public Long getStockId() { return stockId; }

    /**
     * 设置持仓股票 ID。
     *
     * @param stockId stock 表主键
     */
    public void setStockId(Long stockId) { this.stockId = stockId; }

    /**
     * 获取当前持有总股数。
     *
     * @return 净持有股数（买入 - 卖出）
     */
    public BigDecimal getTotalShares() { return totalShares; }

    /**
     * 设置当前持有总股数。
     *
     * @param totalShares 净持有股数
     */
    public void setTotalShares(BigDecimal totalShares) { this.totalShares = totalShares; }

    /**
     * 获取平均持仓成本（未扣除分红）。
     *
     * @return 平均买入成本（含手续费摊薄）
     */
    public BigDecimal getAvgCost() { return avgCost; }

    /**
     * 设置平均持仓成本。
     *
     * @param avgCost 平均买入成本
     */
    public void setAvgCost(BigDecimal avgCost) { this.avgCost = avgCost; }

    /**
     * 获取稀释平均成本（扣除分红后的实际成本）。
     *
     * @return 稀释后的每股持仓成本
     */
    public BigDecimal getDilutedCost() { return dilutedCost; }

    /**
     * 设置稀释平均成本。
     *
     * @param dilutedCost 稀释后的每股持仓成本
     */
    public void setDilutedCost(BigDecimal dilutedCost) { this.dilutedCost = dilutedCost; }

    /**
     * 获取历史累计投入金额（含手续费）。
     *
     * @return 累计买入总成本
     */
    public BigDecimal getTotalInvested() { return totalInvested; }

    /**
     * 设置历史累计投入金额。
     *
     * @param totalInvested 累计买入总成本
     */
    public void setTotalInvested(BigDecimal totalInvested) { this.totalInvested = totalInvested; }

    /**
     * 获取累计收到的分红总金额。
     *
     * @return 历史分红累计总额
     */
    public BigDecimal getTotalDividends() { return totalDividends; }

    /**
     * 设置累计收到的分红总金额。
     *
     * @param totalDividends 历史分红累计总额
     */
    public void setTotalDividends(BigDecimal totalDividends) { this.totalDividends = totalDividends; }

    /**
     * 获取持仓最后更新时间。
     *
     * @return 最后一次交易或分红操作的时间戳
     */
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /**
     * 设置持仓最后更新时间。
     *
     * @param updatedAt 最后一次操作的时间戳
     */
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
