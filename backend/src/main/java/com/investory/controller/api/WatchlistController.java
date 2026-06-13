package com.investory.controller.api;

import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.math.BigDecimal;
import java.sql.*;
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
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) {
            resp.getWriter().write("[]");
            return;
        }
        List<Map<String, Object>> rows;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT w.id, w.stock_id, s.symbol, s.name, s.market, s.currency, w.sort_order FROM watchlist w JOIN stocks s ON w.stock_id=s.id WHERE w.user_id=? ORDER BY w.sort_order, w.created_at DESC")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int colCount = rsmd.getColumnCount();
                rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(rsmd.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
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
        resp.getWriter().write(JsonUtil.toJson(rows));
    }

    public void handleAdd(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long stockId = Long.parseLong(req.getParameter("stockId"));
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO watchlist (user_id, stock_id) VALUES (?, ?)")) {
            ps.setObject(1, userId);
            ps.setObject(2, stockId);
            ps.executeUpdate();
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    public void handleRemove(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long stockId = Long.parseLong((String) req.getAttribute("stockId"));
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist WHERE user_id=? AND stock_id=?")) {
            ps.setObject(1, userId);
            ps.setObject(2, stockId);
            ps.executeUpdate();
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    public void handleReorder(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> order = (List<Map<String, Object>>) com.investory.util.JsonUtil.fromJson(
            new String(req.getReader().readAllBytes()), List.class);
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE watchlist SET sort_order=? WHERE id=? AND user_id=?")) {
                for (var item : order) {
                    ps.setInt(1, ((Number) item.get("sortOrder")).intValue());
                    ps.setLong(2, ((Number) item.get("id")).longValue());
                    ps.setLong(3, userId);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"status\":\"ok\"}");
    }
}
