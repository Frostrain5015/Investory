package com.investory.controller.api;

import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.Stock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;

    private long getUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && s.getAttribute("userId") != null ? (Long) s.getAttribute("userId") : 0;
    }

    @GetMapping
    public List<Map<String, Object>> getWatchlist(HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return List.of();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT w.id, w.stock_id, s.symbol, s.name, s.market, s.currency FROM watchlist w JOIN stocks s ON w.stock_id=s.id WHERE w.user_id=? ORDER BY w.created_at DESC", userId);
        for (var row : rows) {
            BigDecimal price = stockPriceDao.findLatestClose(((Number) row.get("stock_id")).longValue());
            row.put("price", price != null ? price : BigDecimal.ZERO);
        }
        return rows;
    }

    @PostMapping
    public Map<String, Object> add(@RequestParam long stockId, HttpServletRequest req) {
        long userId = getUserId(req);
        jdbc.update("INSERT IGNORE INTO watchlist (user_id, stock_id) VALUES (?, ?)", userId, stockId);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/{stockId}")
    public Map<String, Object> remove(@PathVariable long stockId, HttpServletRequest req) {
        long userId = getUserId(req);
        jdbc.update("DELETE FROM watchlist WHERE user_id=? AND stock_id=?", userId, stockId);
        return Map.of("status", "ok");
    }
}
