package com.investory.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public class StockPrice {
    private Long id;
    private Long stockId;
    private LocalDate tradeDate;
    private BigDecimal open;
    private BigDecimal close;
    private BigDecimal high;
    private BigDecimal low;
    private Long volume;

    public StockPrice() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }
    public BigDecimal getClose() { return close; }
    public void setClose(BigDecimal close) { this.close = close; }
    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }
    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }
    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }
}
