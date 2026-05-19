package com.investory.model;

public class Stock {
    private Long id;
    private String symbol;   // e.g. "1.600519", "116.00700", "105.AAPL"
    private String name;
    private String market;   // SH / SZ / HK / US
    private String currency; // CNY / HKD / USD

    public Stock() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
