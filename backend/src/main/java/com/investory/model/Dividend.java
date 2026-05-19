package com.investory.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class Dividend {
    private Long id;
    private Long portfolioId;
    private Long stockId;
    private String stockName;
    private String stockSymbol;
    private BigDecimal amountPerShare;
    private BigDecimal sharesHeld;
    private BigDecimal totalAmount;
    private LocalDate recordDate;
    private LocalDateTime createdAt;

    public Dividend() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPortfolioId() { return portfolioId; }
    public void setPortfolioId(Long portfolioId) { this.portfolioId = portfolioId; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getStockSymbol() { return stockSymbol; }
    public void setStockSymbol(String stockSymbol) { this.stockSymbol = stockSymbol; }
    public BigDecimal getAmountPerShare() { return amountPerShare; }
    public void setAmountPerShare(BigDecimal amountPerShare) { this.amountPerShare = amountPerShare; }
    public BigDecimal getSharesHeld() { return sharesHeld; }
    public void setSharesHeld(BigDecimal sharesHeld) { this.sharesHeld = sharesHeld; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public LocalDate getRecordDate() { return recordDate; }
    public void setRecordDate(LocalDate recordDate) { this.recordDate = recordDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
