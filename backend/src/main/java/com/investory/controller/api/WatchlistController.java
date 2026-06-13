package com.investory.controller.api;

import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.Stock;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class WatchlistController {

    private final StockDao stockDao = AppContext.get(StockDao.class);
    private final StockPriceDao stockPriceDao = AppContext.get(StockPriceDao.class);

    private long getUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && s.getAttribute("userId") != null ? (Long) s.getAttribute("userId") : 0;
    }

    public void handleGetWatchlist(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        List<Map<String, Object>> rows = jdbcQueryForList(
            "SELECT w.id, w.stock_id, s.symbol, s.name, s.market, s.currency, w.sort_order FROM watchlist w JOIN stocks s ON w.stock_id=s.id WHERE w.user_id=? ORDER BY w.sort_order, w.created_at DESC",
            userId);
        var today = java.time.LocalDate.now();
        var weekAgo = today.minusDays(7);
        for (var row : rows) {
            long stockId = ((Number) row.get("stock_id")).longValue();
            BigDecimal price = stockPriceDao.findLatestClose(stockId);
            if (price == null) price = BigDecimal.ZERO;
            row.put("price", price);

            var recent = stockPriceDao.findRange(stockId, weekAgo, today);
            if (recent.size() >= 2 && price.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal prevClose = recent.get(recent.size() - 2).getClose();
                if (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal change = price.subtract(prevClose);
                    row.put("changeToday", change);
                    row.put("changePctToday", change.divide(prevClose, 4, java.math.RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP));
                }
            }
            row.putIfAbsent("changeToday", BigDecimal.ZERO);
            row.putIfAbsent("changePctToday", BigDecimal.ZERO);
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(rows));
    }

    public void handleAdd(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long stockId = Long.parseLong(req.getParameter("stockId"));
        jdbcUpdate("INSERT IGNORE INTO watchlist (user_id, stock_id) VALUES (?, ?)", userId, stockId);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    public void handleRemove(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long stockId = Long.parseLong((String) req.getAttribute("stockId"));
        jdbcUpdate("DELETE FROM watchlist WHERE user_id=? AND stock_id=?", userId, stockId);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    public void handleReorder(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        // Read JSON body
        String jsonBody = new String(req.getReader().readAllBytes());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> order = com.google.gson.JsonParser.parseString(jsonBody).getAsJsonArray().asList().stream()
            .map(e -> {
                var obj = e.getAsJsonObject();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sortOrder", obj.get("sortOrder").getAsInt());
                m.put("id", obj.get("id").getAsLong());
                return m;
            })
            .collect(java.util.stream.Collectors.toList());
        for (var item : order) {
            jdbcUpdate("UPDATE watchlist SET sort_order=? WHERE id=? AND user_id=?",
                ((Number) item.get("sortOrder")).intValue(),
                ((Number) item.get("id")).longValue(),
                userId);
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    // ── JDBC helpers ─────────────────────────────────────────────────────

    private List<Map<String, Object>> jdbcQueryForList(String sql, Object... args) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= colCount; c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    result.add(row);
                }
            }
        }
        return result;
    }

    private int jdbcUpdate(String sql, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }
}
