package com.investory.controller.api;

import com.investory.dao.StockDao;
import com.investory.crawler.RealtimeQuoteService;
import com.investory.model.Stock;
import com.investory.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/stock")
public class StockSearchApiController {

    @Autowired private StockDao stockDao;
    @Autowired private RealtimeQuoteService quoteService;

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public String search(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) return "[]";
        List<Stock> stocks = stockDao.search(q.trim());
        var quotes = quoteService.getQuotes(stocks);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Stock s : stocks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",       s.getId());
            m.put("symbol",   s.getSymbol());
            m.put("name",     s.getName());
            m.put("market",   s.getMarket());
            m.put("currency", s.getCurrency() != null ? s.getCurrency() : "CNY");
            var quote = quotes.get(s.getSymbol());
            m.put("price", quote != null ? quote.price() : null);
            m.put("priceLive", quote != null && quote.live());
            m.put("priceSource", quote != null ? quote.source() : "unavailable");
            m.put("priceTimestamp", quote != null && quote.quoteTime() != null ? quote.quoteTime().toString() : null);
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }
}
