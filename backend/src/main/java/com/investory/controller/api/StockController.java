package com.investory.controller.api;

import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.model.Quote;
import com.investory.service.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class StockController {

    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private HoldingService holdingService;
    @Autowired private TransactionDao transactionDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private RealtimeQuoteService quoteService;
    @Autowired private PortfolioAnalysisService analysisService;

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    @GetMapping("/holdings")
    public Map<String, Object> holdings(HttpServletRequest req) {
        return Map.of("snapshots", holdingService.getSnapshots(getPortfolioId(req)));
    }

    @GetMapping("/closed-positions")
    public List<Map<String, Object>> closedPositions(HttpServletRequest req) {
        return analysisService.getClosedPositions(getPortfolioId(req));
    }

    @GetMapping("/stocks/{symbol}")
    public Map<String, Object> detail(@PathVariable String symbol, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        if (pid == 0) return Map.of("error", "No portfolio");
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return Map.of("error", "Stock not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stock", stock); r.put("holding", holdingDao.findByPortfolioAndStock(pid, stock.getId()));
        r.put("transactions", transactionDao.findByPortfolioAndStock(pid, stock.getId()));
        r.put("dividends", dividendDao.findByPortfolioAndStock(pid, stock.getId()));
        Quote q = quoteService.getQuote(stock);
        r.put("livePrice", q != null ? q.price() : null);
        r.put("livePriceTs", q != null ? q.fetchedAt().toString() : (stockPriceDao.findLatest(stock.getId()) != null ? stockPriceDao.findLatest(stock.getId()).getTradeDate().toString() : null));
        return r;
    }

    @GetMapping("/quote/{symbol}")
    public Map<String, Object> quote(@PathVariable String symbol) {
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return Map.of("error", "Stock not found");
        BigDecimal price = quoteService.getPrice(stock);
        BigDecimal cached = stockPriceDao.findLatestClose(stock.getId());
        return Map.of("symbol", symbol, "price", price != null ? price : cached, "live", price != null);
    }

    @PostMapping("/stocks/{symbol}/refresh")
    public Map<String, String> refresh(@PathVariable String symbol) {
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return Map.of("error", "Stock not found");
        quoteService.getPrice(stock);
        return Map.of("status", "ok");
    }

    @PostMapping("/portfolio/refresh")
    public Map<String, String> refreshPortfolio(HttpServletRequest req) {
        long pid = getPortfolioId(req);
        if (pid == 0) return Map.of("error", "No portfolio");
        List<HoldingSnapshot> snaps = holdingService.getSnapshots(pid);
        for (HoldingSnapshot snap : snaps) {
            Stock s = stockDao.findBySymbol(snap.getStockSymbol());
            if (s != null) quoteService.getPrice(s);
        }
        return Map.of("status", "ok", "count", String.valueOf(snaps.size()));
    }
}
