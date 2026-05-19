package com.investory.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Holding {
    private Long id;
    private Long portfolioId;
    private Long stockId;
    private BigDecimal totalShares;
    private BigDecimal avgCost;
    private BigDecimal dilutedCost;
    private BigDecimal totalInvested;
    private BigDecimal totalDividends;
    private LocalDateTime updatedAt;

    public Holding() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPortfolioId() { return portfolioId; }
    public void setPortfolioId(Long portfolioId) { this.portfolioId = portfolioId; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
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
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
