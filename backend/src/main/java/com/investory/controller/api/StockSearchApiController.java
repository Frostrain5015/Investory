package com.investory.controller.api;

import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.Stock;
import com.investory.server.AppContext;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.math.BigDecimal;
import java.util.*;

public class StockSearchApiController {

    private final StockDao stockDao = AppContext.get(StockDao.class);
    private final StockPriceDao stockPriceDao = AppContext.get(StockPriceDao.class);

    public void handleSearch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String q = req.getParameter("q");
        if (q == null || q.isBlank()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
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
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }
}
