package com.investory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 持仓快照实体类（非数据库表，仅用于业务计算和前端响应）。
 * <p>
 * 由 HoldingService 将 {@link Holding} 的持仓静态数据与实时行情（{@link Quote}）
 * 合并计算后构建，包含当前市值、浮动盈亏、今日涨跌等动态指标。
 * 同时提供原始货币（native）版本的各项数值，以支持多货币组合的前端展示。
 * 该对象不持久化到数据库，仅在接口响应时序列化为 JSON。
 * </p>
 */
public class HoldingSnapshot {

    /** 所属投资组合 ID */
    private Long portfolioId;

    /** 持仓股票 ID */
    private Long stockId;

    /** 股票 symbol，例如 "1.600519" */
    private String stockSymbol;

    /** 股票名称，例如 "贵州茅台" */
    private String stockName;

    /** 所属交易市场：SH / SZ / HK / US */
    private String market;

    /** 股票原始交易货币：CNY / HKD / USD */
    private String currency;

    /** 当前持有总股数 */
    private BigDecimal totalShares;

    /**
     * 平均持仓成本（未扣除分红，已换算为统一展示货币）。
     * 计算方式：totalInvested / totalShares
     */
    private BigDecimal avgCost;

    /**
     * 稀释平均成本（扣除分红后的实际成本，已换算为统一展示货币）。
     * 计算方式：(totalInvested - totalDividends) / totalShares
     */
    private BigDecimal dilutedCost;

    /** 历史累计投入金额（已换算为统一展示货币） */
    private BigDecimal totalInvested;

    /** 累计收到的分红总金额（已换算为统一展示货币） */
    private BigDecimal totalDividends;

    /**
     * 当前实时价格（已换算为统一展示货币，默认 CNY）。
     * 港股、美股持仓的价格会通过汇率转换后赋值到此字段。
     */
    private BigDecimal currentPrice;

    // 以下 native* 字段均为换算前的原始货币数值，用于前端在原始货币列中展示
    /** 原始货币的实时价格（未换算汇率） */
    private BigDecimal nativePrice;

    /** 原始货币的平均持仓成本（未换算汇率） */
    private BigDecimal nativeAvgCost;

    /** 原始货币的累计投入金额（未换算汇率） */
    private BigDecimal nativeInvested;

    /** 原始货币的当前市值（未换算汇率） */
    private BigDecimal nativeMarketValue;

    /** 原始货币的浮动盈亏（未换算汇率） */
    private BigDecimal nativeUnrealizedPnl;

    /** 今日价格变动金额（已换算为统一展示货币，正值上涨，负值下跌） */
    private BigDecimal changeToday;

    /** 今日涨跌幅百分比（如 2.35 表示涨幅 2.35%） */
    private BigDecimal changePctToday;

    /** 实时价格的抓取时间戳，ISO-8601 UTC 格式，例如 "2026-05-28T09:30:00Z" */
    private String priceTimestamp; // ISO-8601 UTC instant when the live price was fetched

    /** 无参构造器，供序列化框架及测试使用 */
    public HoldingSnapshot() {}

    /**
     * 计算当前持仓市值（已换算为统一展示货币）。
     * <p>
     * 计算方式：currentPrice × totalShares，结果保留 2 位小数（四舍五入）。
     * 若 currentPrice 或 totalShares 为 null，则返回 {@link BigDecimal#ZERO}。
     * </p>
     *
     * @return 当前市值，单位为统一展示货币
     */
    public BigDecimal getMarketValue() {
        if (currentPrice == null || totalShares == null) return BigDecimal.ZERO;
        // 当前价格 × 持仓股数，保留 2 位小数
        return currentPrice.multiply(totalShares).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算浮动盈亏（已换算为统一展示货币，含分红收益）。
     * <p>
     * 计算方式：(currentPrice - avgCost) × totalShares + totalDividends
     * 结果保留 2 位小数（四舍五入）。
     * 若关键字段为 null，则返回 {@link BigDecimal#ZERO}。
     * </p>
     *
     * @return 未实现盈亏（含已收分红），正值为盈利，负值为亏损
     */
    public BigDecimal getUnrealizedPnl() {
        if (currentPrice == null || avgCost == null || totalShares == null) return BigDecimal.ZERO;
        // 价差盈亏 = (现价 - 平均成本) × 持仓股数
        BigDecimal pnl = currentPrice.subtract(avgCost).multiply(totalShares);
        // 将历史分红总额计入盈亏（分红视为成本摊薄的等效收益）
        if (totalDividends != null) pnl = pnl.add(totalDividends);
        return pnl.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算浮动盈亏百分比（含分红收益）。
     * <p>
     * 计算方式：getUnrealizedPnl() / totalInvested × 100，结果保留 2 位小数。
     * 若 totalInvested 为 null 或零，则返回 {@link BigDecimal#ZERO} 以避免除零异常。
     * </p>
     *
     * @return 浮动盈亏率（百分比形式，如 15.23 表示盈利 15.23%）
     */
    public BigDecimal getUnrealizedPnlPct() {
        if (totalInvested == null || totalInvested.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        // 盈亏率 = 浮动盈亏 / 累计投入 × 100，保留 4 位中间精度后再保留 2 位结果
        return getUnrealizedPnl().divide(totalInvested, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算稀释盈亏（以稀释成本为基准，已换算为统一展示货币）。
     * <p>
     * 计算方式：(currentPrice - dilutedCost) × totalShares，结果保留 2 位小数。
     * 稀释盈亏反映了扣除历史分红后的真实持仓盈亏水平。
     * 若关键字段为 null，则返回 {@link BigDecimal#ZERO}。
     * </p>
     *
     * @return 以稀释成本计算的未实现盈亏
     */
    public BigDecimal getDilutedPnl() {
        if (currentPrice == null || dilutedCost == null || totalShares == null) return BigDecimal.ZERO;
        // 稀释盈亏 = (现价 - 稀释成本) × 持仓股数
        return currentPrice.subtract(dilutedCost).multiply(totalShares).setScale(2, RoundingMode.HALF_UP);
    }

    // ── getters/setters ────────────────────────────────────────────────────────

    /** @return 所属投资组合 ID */
    public Long getPortfolioId() { return portfolioId; }
    /** @param portfolioId 所属投资组合 ID */
    public void setPortfolioId(Long portfolioId) { this.portfolioId = portfolioId; }

    /** @return 持仓股票 ID */
    public Long getStockId() { return stockId; }
    /** @param stockId 持仓股票 ID */
    public void setStockId(Long stockId) { this.stockId = stockId; }

    /** @return 股票 symbol */
    public String getStockSymbol() { return stockSymbol; }
    /** @param stockSymbol 股票 symbol */
    public void setStockSymbol(String stockSymbol) { this.stockSymbol = stockSymbol; }

    /** @return 股票名称 */
    public String getStockName() { return stockName; }
    /** @param stockName 股票名称 */
    public void setStockName(String stockName) { this.stockName = stockName; }

    /** @return 所属交易市场 */
    public String getMarket() { return market; }
    /** @param market 所属交易市场 */
    public void setMarket(String market) { this.market = market; }

    /** @return 股票原始交易货币 */
    public String getCurrency() { return currency; }
    /** @param currency 股票原始交易货币 */
    public void setCurrency(String currency) { this.currency = currency; }

    /** @return 当前持有总股数 */
    public BigDecimal getTotalShares() { return totalShares; }
    /** @param totalShares 当前持有总股数 */
    public void setTotalShares(BigDecimal totalShares) { this.totalShares = totalShares; }

    /** @return 平均持仓成本（统一货币，未扣分红） */
    public BigDecimal getAvgCost() { return avgCost; }
    /** @param avgCost 平均持仓成本 */
    public void setAvgCost(BigDecimal avgCost) { this.avgCost = avgCost; }

    /** @return 稀释平均成本（统一货币，已扣分红） */
    public BigDecimal getDilutedCost() { return dilutedCost; }
    /** @param dilutedCost 稀释平均成本 */
    public void setDilutedCost(BigDecimal dilutedCost) { this.dilutedCost = dilutedCost; }

    /** @return 历史累计投入金额（统一货币） */
    public BigDecimal getTotalInvested() { return totalInvested; }
    /** @param totalInvested 历史累计投入金额 */
    public void setTotalInvested(BigDecimal totalInvested) { this.totalInvested = totalInvested; }

    /** @return 累计收到的分红总金额（统一货币） */
    public BigDecimal getTotalDividends() { return totalDividends; }
    /** @param totalDividends 累计分红总金额 */
    public void setTotalDividends(BigDecimal totalDividends) { this.totalDividends = totalDividends; }

    /** @return 当前实时价格（统一展示货币，已换算汇率） */
    public BigDecimal getCurrentPrice() { return currentPrice; }
    /** @param currentPrice 当前实时价格（统一货币） */
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }

    /** @return 原始货币实时价格（未换算汇率） */
    public BigDecimal getNativePrice() { return nativePrice; }
    /** @param nativePrice 原始货币实时价格 */
    public void setNativePrice(BigDecimal nativePrice) { this.nativePrice = nativePrice; }

    /** @return 原始货币平均持仓成本 */
    public BigDecimal getNativeAvgCost() { return nativeAvgCost; }
    /** @param nativeAvgCost 原始货币平均持仓成本 */
    public void setNativeAvgCost(BigDecimal nativeAvgCost) { this.nativeAvgCost = nativeAvgCost; }

    /** @return 原始货币累计投入金额 */
    public BigDecimal getNativeInvested() { return nativeInvested; }
    /** @param nativeInvested 原始货币累计投入金额 */
    public void setNativeInvested(BigDecimal nativeInvested) { this.nativeInvested = nativeInvested; }

    /** @return 原始货币当前市值 */
    public BigDecimal getNativeMarketValue() { return nativeMarketValue; }
    /** @param nativeMarketValue 原始货币当前市值 */
    public void setNativeMarketValue(BigDecimal nativeMarketValue) { this.nativeMarketValue = nativeMarketValue; }

    /** @return 原始货币浮动盈亏 */
    public BigDecimal getNativeUnrealizedPnl() { return nativeUnrealizedPnl; }
    /** @param nativeUnrealizedPnl 原始货币浮动盈亏 */
    public void setNativeUnrealizedPnl(BigDecimal nativeUnrealizedPnl) { this.nativeUnrealizedPnl = nativeUnrealizedPnl; }

    /** @return 今日价格变动金额（统一货币） */
    public BigDecimal getChangeToday() { return changeToday; }
    /** @param changeToday 今日价格变动金额 */
    public void setChangeToday(BigDecimal changeToday) { this.changeToday = changeToday; }

    /** @return 今日涨跌幅百分比 */
    public BigDecimal getChangePctToday() { return changePctToday; }
    /** @param changePctToday 今日涨跌幅百分比 */
    public void setChangePctToday(BigDecimal changePctToday) { this.changePctToday = changePctToday; }

    /** @return 实时价格抓取时间戳（ISO-8601 UTC） */
    public String getPriceTimestamp() { return priceTimestamp; }
    /** @param priceTimestamp 实时价格抓取时间戳 */
    public void setPriceTimestamp(String priceTimestamp) { this.priceTimestamp = priceTimestamp; }
}
