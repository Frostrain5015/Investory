package com.investory.controller.api;

import com.investory.dao.StockDao;
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
    @Autowired private com.investory.crawler.RealtimeQuoteService quoteService;

    private long getUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && s.getAttribute("userId") != null ? (Long) s.getAttribute("userId") : 0;
    }

    @GetMapping
    public List<Map<String, Object>> getWatchlist(HttpServletRequest req, @RequestParam(defaultValue="true") boolean quotes) {
        long userId = getUserId(req);
        if (userId == 0) return List.of();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT w.id, w.stock_id, s.symbol, s.name, s.market, s.currency, w.sort_order FROM watchlist w JOIN stocks s ON w.stock_id=s.id WHERE w.user_id=? ORDER BY w.sort_order, w.created_at DESC", userId);
        if (!quotes) return rows;
        Map<Long,Stock> stocks = new HashMap<>();
        for (var row : rows) {
            long id = ((Number)row.get("stock_id")).longValue();
            Stock stock = stockDao.findById(id);
            if (stock != null) stocks.put(id, stock);
        }
        var priceQuotes = quoteService.getQuotes(new ArrayList<>(stocks.values()));
        for (var row : rows) {
            long id = ((Number)row.get("stock_id")).longValue();
            Stock stock = stocks.get(id);
            var quote = stock == null ? null : priceQuotes.get(stock.getSymbol());
            BigDecimal price = quote != null ? quote.price() : null;
            BigDecimal previous = quote != null ? quote.previousClose() : null;
            if (quote != null && quote.change() != null) previous = price.subtract(quote.change());
            BigDecimal change = price != null && previous != null ? price.subtract(previous) : null;
            BigDecimal pct = quote != null ? quote.changePct() : null;
            if (pct == null && change != null && previous.signum()>0)
                pct = change.multiply(BigDecimal.valueOf(100)).divide(previous, 2, java.math.RoundingMode.HALF_UP);
            row.put("price", price);
            row.put("changeToday", change);
            row.put("changePctToday", pct);
            row.put("priceLive", quote != null && quote.live());
            row.put("priceSource", quote != null ? quote.source() : "unavailable");
            row.put("priceTimestamp", quote != null ? quote.quoteTime() : null);
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
