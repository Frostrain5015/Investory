package com.investory.controller.api;

import com.investory.dao.StockDao;
import com.investory.model.Stock;
import com.investory.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/stock")
public class StockSearchApiController {

    @Autowired private StockDao stockDao;

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public String search(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) return "[]";
        List<Stock> stocks = stockDao.search(q.trim());
        List<Map<String, String>> result = new ArrayList<>();
        for (Stock s : stocks) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id",     String.valueOf(s.getId()));
            m.put("symbol", s.getSymbol());
            m.put("name",   s.getName());
            m.put("market", s.getMarket());
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }
}
