package com.investory.controller.api;

import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.Stock;
import com.investory.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/stock")
public class StockSearchApiController {

    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public String search(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) return "[]";
        List<Stock> stocks = stockDao.search(q.trim());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Stock s : stocks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",       s.getId());
            m.put("symbol",   s.getSymbol());
            m.put("name",     s.getName());
            m.put("market",   s.getMarket());
            m.put("currency", s.getCurrency() != null ? s.getCurrency() : "CNY");
            BigDecimal price = stockPriceDao.findLatestClose(s.getId());
            m.put("price",    price != null ? price : BigDecimal.ZERO);
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }
}
