package com.investory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Holding + live market data, computed by HoldingService.
 * Used on the dashboard and holdings pages.
 */
public class HoldingSnapshot {
    private Long portfolioId;
    private Long stockId;
    private String stockSymbol;
    private String stockName;
    private String market;
    private String currency;

    private BigDecimal totalShares;
    private BigDecimal avgCost;
    private BigDecimal dilutedCost;
    private BigDecimal totalInvested;
    private BigDecimal totalDividends;

    private BigDecimal currentPrice;
    private BigDecimal changeToday;
    private BigDecimal changePctToday;

    public HoldingSnapshot() {}

    public BigDecimal getMarketValue() {
        if (currentPrice == null || totalShares == null) return BigDecimal.ZERO;
        return currentPrice.multiply(totalShares).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getUnrealizedPnl() {
        if (currentPrice == null || avgCost == null || totalShares == null) return BigDecimal.ZERO;
        return currentPrice.subtract(avgCost).multiply(totalShares).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getUnrealizedPnlPct() {
        if (totalInvested == null || totalInvested.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return getUnrealizedPnl().divide(totalInvested, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getDilutedPnl() {
        if (currentPrice == null || dilutedCost == null || totalShares == null) return BigDecimal.ZERO;
        return currentPrice.subtract(dilutedCost).multiply(totalShares).setScale(2, RoundingMode.HALF_UP);
    }

    // ── getters/setters ────────────────────────────────────────────────────────

    public Long getPortfolioId() { return portfolioId; }
    public void setPortfolioId(Long portfolioId) { this.portfolioId = portfolioId; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public String getStockSymbol() { return stockSymbol; }
    public void setStockSymbol(String stockSymbol) { this.stockSymbol = stockSymbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getTotalShares() { return totalShares; }
    public void setTotalShares(BigDecimal totalShares) { this.totalShares = totalShares; }
    public BigDecimal getAvgCost() { return avgCost; }
    public void setAvgCost(BigDecimal avgCost) { this.avgCost = avgCost; }
    public BigDecimal getDilutedCost() { return dilutedCost; }
    public void setDilutedCost(BigDecimal dilutedCost) { this.dilutedCost = dilutedCost; }
    public BigDecimal getTotalInvested() { return totalInvested; }
    public void setTotalInvested(BigDecimal totalInvested) { this.totalInvested = totalInvested; }
    public BigDecimal getTotalDividends() { return totalDividends; }
    public void setTotalDividends(BigDecimal totalDividends) { this.totalDividends = totalDividends; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }
    public BigDecimal getChangeToday() { return changeToday; }
    public void setChangeToday(BigDecimal changeToday) { this.changeToday = changeToday; }
    public BigDecimal getChangePctToday() { return changePctToday; }
    public void setChangePctToday(BigDecimal changePctToday) { this.changePctToday = changePctToday; }
}
