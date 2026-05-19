package com.investory.servlet.api;

import com.investory.dao.StockDao;
import com.investory.model.Stock;
import com.investory.util.JsonUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.*;

/** Returns stock search suggestions as JSON. */
@WebServlet("/api/stock/search")
public class StockSearchApiServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        String q = req.getParameter("q");
        if (q == null || q.isBlank()) {
            resp.getWriter().write("[]");
            return;
        }
        try {
            List<Stock> stocks = StockDao.get().search(q.trim());
            List<Map<String, String>> result = new ArrayList<>();
            for (Stock s : stocks) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id",     String.valueOf(s.getId()));
                m.put("symbol", s.getSymbol());
                m.put("name",   s.getName());
                m.put("market", s.getMarket());
                result.add(m);
            }
            resp.getWriter().write(JsonUtil.toJson(result));
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
