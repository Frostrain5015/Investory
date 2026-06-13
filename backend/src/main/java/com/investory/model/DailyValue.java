package com.investory.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 投资组合每日净值快照实体类。
 * <p>
 * 对应数据库 daily_value 表，记录某个投资组合在特定日期的总市值、总成本及当日盈亏。
 * 由定时任务在每个交易日收盘后计算并写入，为净值曲线图表、历史收益分析等功能提供数据支撑。
 * 每个 (portfolioId, snapshotDate) 组合唯一，重复计算时覆盖更新。
 * </p>
 */
public class DailyValue {

    /** 数据库自增主键 */
    private Long id;

    /** 所属投资组合 ID，外键引用 portfolio 表 */
    private Long portfolioId;

    /** 快照日期（交易日），该记录所代表的净值计算日期 */
    private LocalDate snapshotDate;

    /**
     * 当日收盘后的投资组合总市值（已换算为统一货币，默认 CNY）。
     * 计算方式：∑(各持仓股数 × 当日收盘价 × 汇率)
     */
    private BigDecimal totalValue;

    /**
     * 当日的投资组合累计总成本（已换算为统一货币，默认 CNY）。
     * 计算方式：∑(各持仓 totalInvested × 汇率)
     */
    private BigDecimal totalCost;

    /**
     * 当日盈亏（相较于前一交易日的市值变动）。
     * 计算方式：当日 totalValue - 前日 totalValue
     * 正值表示盈利，负值表示亏损。
     */
    private BigDecimal dailyPnl;

    /** 无参构造器，供 JdbcTemplate RowMapper 及序列化框架使用 */
    public DailyValue() {}

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
     * 获取快照日期。
     *
     * @return 净值计算日期 LocalDate
     */
    public LocalDate getSnapshotDate() { return snapshotDate; }

    /**
     * 设置快照日期。
     *
     * @param snapshotDate 净值计算日期 LocalDate
     */
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }

    /**
     * 获取当日收盘总市值。
     *
     * @return 统一货币的总市值
     */
    public BigDecimal getTotalValue() { return totalValue; }

    /**
     * 设置当日收盘总市值。
     *
     * @param totalValue 统一货币的总市值
     */
    public void setTotalValue(BigDecimal totalValue) { this.totalValue = totalValue; }

    /**
     * 获取当日累计总成本。
     *
     * @return 统一货币的总持仓成本
     */
    public BigDecimal getTotalCost() { return totalCost; }

    /**
     * 设置当日累计总成本。
     *
     * @param totalCost 统一货币的总持仓成本
     */
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }

    /**
     * 获取当日盈亏金额。
     *
     * @return 正值为盈利，负值为亏损
     */
    public BigDecimal getDailyPnl() { return dailyPnl; }

    /**
     * 设置当日盈亏金额。
     *
     * @param dailyPnl 正值为盈利，负值为亏损
     */
    public void setDailyPnl(BigDecimal dailyPnl) { this.dailyPnl = dailyPnl; }
}
