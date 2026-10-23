package com.investory.marketdata;

import com.investory.crawler.RealtimeQuoteService;
import com.investory.model.Quote;
import com.investory.model.Stock;
import java.util.*;

/** Metadata comes from the business database; prices come from the shared quote gateway. */
public final class MarketRows {
    private MarketRows() { }
    public static Map<String, Quote> quotes(List<Map<String, Object>> rows, RealtimeQuoteService gateway, boolean cachedOnly) {
        List<Stock> stocks = rows.stream().map(row -> {
            Stock stock = new Stock();
            stock.setSymbol(Objects.toString(row.get("symbol"), ""));
            stock.setMarket(Objects.toString(row.get("market"), ""));
            return stock;
        }).toList();
        return cachedOnly ? gateway.cachedQuotes(stocks) : gateway.getQuotes(stocks);
    }
}
