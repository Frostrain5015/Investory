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
            "SELECT w.id, w.stock_id, s.symbol, s.name, s.market, s.currency, w.sort_order FROM watchlist w JOIN stocks s ON w.stock_id=s.id WHERE w.user_id=? ORDER BY w.sort_order, w.created_at DESC", userId);
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

    @PutMapping("/reorder")
    public Map<String, Object> reorder(@RequestBody List<Map<String, Object>> order, HttpServletRequest req) {
        long userId = getUserId(req);
        for (var item : order) {
            jdbc.update("UPDATE watchlist SET sort_order=? WHERE id=? AND user_id=?",
                ((Number) item.get("sortOrder")).intValue(),
                ((Number) item.get("id")).longValue(),
                userId);
        }
        return Map.of("status", "ok");
    }
}
