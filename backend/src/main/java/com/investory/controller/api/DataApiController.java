package com.investory.controller.api;

import com.investory.crawler.EastMoneyCrawler;
import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.HoldingService;
import com.investory.service.PortfolioAnalysisService;
import com.investory.service.PortfolioValueCalculator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class DataApiController {

    @Autowired private PortfolioDao portfolioDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private HoldingService holdingService;
    @Autowired private TransactionDao transactionDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;
    @Autowired private PortfolioAnalysisService analysisService;
    @Autowired private EastMoneyCrawler crawler;
    @Autowired private RealtimeQuoteService quoteService;
    @Autowired private PortfolioValueCalculator valueCalculator;
    @Autowired private JdbcTemplate jdbc;

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return 0;
        Long id = (Long) session.getAttribute("portfolioId");
        return id != null ? id : 0;
    }

    // ── Portfolio ───────────────────────────────────────────────────────────

    @GetMapping("/portfolios")
    public List<Portfolio> getPortfolios(HttpServletRequest req) {
        HttpSession session = req.getSession();
        Long userId = (Long) session.getAttribute("userId");
        return portfolioDao.findByUser(userId);
    }

    @PostMapping("/portfolios")
    public Map<String, Object> createPortfolio(@RequestParam String name, HttpServletRequest req) {
        HttpSession session = req.getSession();
        Long userId = (Long) session.getAttribute("userId");
        Portfolio p = new Portfolio();
        p.setUserId(userId);
        p.setName(name.trim());
        long id = portfolioDao.insert(p);
        session.setAttribute("portfolioId", id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name.trim());
        return result;
    }

    @PutMapping("/portfolios/{id}")
    public Map<String, String> updatePortfolio(@PathVariable long id,
            @RequestParam(required = false) String name, HttpServletRequest req) {
        if (name != null && !name.isBlank()) {
            portfolioDao.updateName(id, name.trim());
        } else {
            req.getSession().setAttribute("portfolioId", id);
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return result;
    }

    @DeleteMapping("/portfolios/{id}")
    public Map<String, String> deletePortfolio(@PathVariable long id, HttpServletRequest req) {
        portfolioDao.delete(id);
        HttpSession session = req.getSession();
        Long userId = (Long) session.getAttribute("userId");
        List<Portfolio> remaining = portfolioDao.findByUser(userId);
        session.setAttribute("portfolioId", remaining.isEmpty() ? null : remaining.get(0).getId());
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return result;
    }

    // ── Dashboard ───────────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public Map<String, Object> getDashboard(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("error", "No portfolio");

        List<HoldingSnapshot> snapshots = holdingService.getSnapshots(portfolioId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshots",        snapshots);
        result.put("totalMarketValue", analysisService.totalMarketValue(snapshots));
        result.put("totalInvested",    analysisService.totalInvested(snapshots));
        result.put("totalPnl",         analysisService.totalUnrealizedPnl(snapshots));
        result.put("totalReturnPct",   analysisService.cashWeightedReturn(portfolioId));

        // Today's P&L from real-time snapshots (not backfill, which may carry forward)
        BigDecimal todayPnl = BigDecimal.ZERO;
        for (HoldingSnapshot s : snapshots) {
            if (s.getChangeToday() != null) todayPnl = todayPnl.add(s.getChangeToday());
        }
        result.put("todayPnl", todayPnl);
        BigDecimal prevValue = analysisService.totalMarketValue(snapshots).subtract(todayPnl);
        if (prevValue.compareTo(BigDecimal.ZERO) != 0) {
            result.put("todayPnlPct", todayPnl
                    .divide(prevValue, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new java.math.BigDecimal("100"))
                    .setScale(2, java.math.RoundingMode.HALF_UP));
        } else {
            result.put("todayPnlPct", java.math.BigDecimal.ZERO);
        }

        // Sum cash balances converted to CNY (CNY rate=1, others via exchange_rates)
        BigDecimal cashBalance = jdbc.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN c.currency='CNY' THEN c.amount ELSE c.amount / NULLIF(e.rate, 0) END), 0) FROM cash_balances c LEFT JOIN exchange_rates e ON c.currency = e.currency WHERE c.portfolio_id=?",
            BigDecimal.class, portfolioId);
        result.put("cashBalance", cashBalance != null ? cashBalance : BigDecimal.ZERO);

        // Allocation built from already-fetched snapshots (avoids double getSnapshots)
        List<Map<String, Object>> allocation = new ArrayList<>();
        for (HoldingSnapshot s : snapshots) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("name", s.getStockName());
            a.put("symbol", s.getStockSymbol());
            a.put("value", s.getMarketValue());
            a.put("currency", s.getCurrency() != null ? s.getCurrency() : "CNY");
            allocation.add(a);
        }
        result.put("allocation", allocation);
        return result;
    }

    // ── Holdings ────────────────────────────────────────────────────────────

    @GetMapping("/holdings")
    public Map<String, Object> getHoldings(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshots", holdingService.getSnapshots(portfolioId));
        return result;
    }

    // ── Transactions ────────────────────────────────────────────────────────

    @GetMapping("/transactions")
    public List<Map<String, Object>> getTransactions(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        List<Map<String, Object>> list = new ArrayList<>();

        // Merge transactions and dividends into one timeline
        for (Transaction t : transactionDao.findByPortfolio(portfolioId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",            t.getId());
            m.put("date",          t.getTradeDate().toString());
            m.put("type",          t.getType());
            m.put("stockName",     t.getStockName());
            m.put("stockSymbol",   t.getStockSymbol());
            m.put("shares",        t.getShares());
            m.put("price",         t.getPrice());
            m.put("fee",           t.getFee());
            m.put("note",          t.getNote());
            list.add(m);
        }

        for (Dividend d : dividendDao.findByPortfolio(portfolioId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",            d.getId());
            m.put("date",          d.getRecordDate().toString());
            m.put("type",          "DIV");
            m.put("stockName",     d.getStockName());
            m.put("stockSymbol",   d.getStockSymbol());
            m.put("amountPerShare", d.getAmountPerShare());
            m.put("sharesHeld",    d.getSharesHeld());
            m.put("totalAmount",   d.getTotalAmount());
            list.add(m);
        }

        list.sort((a, b) -> ((String) b.get("date")).compareTo((String) a.get("date")));
        return list;
    }

    @PostMapping("/transactions")
    public Map<String, Object> createTransaction(
            @RequestParam long stockId, @RequestParam String type,
            @RequestParam BigDecimal shares, @RequestParam BigDecimal price,
            @RequestParam(required = false) String fee, @RequestParam String tradeDate,
            @RequestParam(required = false, defaultValue = "CNY") String currency,
            @RequestParam(required = false) String note, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;

        // Cash transfers update cash_balances instead of stock holdings
        if ("TRANSFER_IN".equals(type) || "TRANSFER_OUT".equals(type)) {
            BigDecimal amount = "TRANSFER_IN".equals(type) ? shares : shares.negate();
            jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?",
                portfolioId, currency, amount, amount);
            Transaction t = new Transaction();
            t.setPortfolioId(portfolioId);
            t.setStockId(0L);
            t.setType(type);
            t.setShares(shares);
            t.setPrice(BigDecimal.ZERO);
            t.setFee(BigDecimal.ZERO);
            t.setTradeDate(LocalDate.parse(tradeDate));
            t.setNote(note);
            long id = transactionDao.insert(t);
            valueCalculator.backfillFrom(portfolioId, LocalDate.parse(tradeDate));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            return result;
        }

        Transaction t = new Transaction();
        t.setPortfolioId(portfolioId);
        t.setStockId(stockId);
        t.setType(type);
        t.setShares(shares);
        t.setPrice(price);
        t.setFee(feeVal);
        t.setTradeDate(LocalDate.parse(tradeDate));
        t.setNote(note);
        long id = transactionDao.insert(t);
        holdingService.rebuildHolding(portfolioId, stockId);
        Stock stock = stockDao.findById(stockId);
        if (stock != null) {
            valueCalculator.backfillFrom(portfolioId, LocalDate.parse(tradeDate), stockId, price, shares);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        return result;
    }

    @PutMapping("/transactions/{id}")
    public Map<String, String> updateTransaction(@PathVariable long id,
            @RequestParam long stockId, @RequestParam String type,
            @RequestParam BigDecimal shares, @RequestParam BigDecimal price,
            @RequestParam(required = false) String fee, @RequestParam String tradeDate,
            @RequestParam(required = false) String note, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;
        Transaction t = new Transaction();
        t.setId(id);
        t.setPortfolioId(portfolioId);
        t.setStockId(stockId);
        t.setType(type);
        t.setShares(shares);
        t.setPrice(price);
        t.setFee(feeVal);
        t.setTradeDate(LocalDate.parse(tradeDate));
        t.setNote(note);
        transactionDao.update(t);
        holdingService.rebuildHolding(portfolioId, stockId);
        valueCalculator.backfillFrom(portfolioId, LocalDate.parse(tradeDate));
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return result;
    }

    @DeleteMapping("/transactions/{id}")
    public Map<String, String> deleteTransaction(@PathVariable long id, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        List<Transaction> txns = transactionDao.findByPortfolio(portfolioId);
        txns.stream().filter(t -> t.getId() == id).findFirst().ifPresent(old -> {
            transactionDao.delete(id);
            holdingService.rebuildHolding(portfolioId, old.getStockId());
        });
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return result;
    }

    // ── Dividends ───────────────────────────────────────────────────────────

    @GetMapping("/dividends")
    public Map<String, Object> getDividends(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dividends", dividendDao.findByPortfolio(portfolioId));
        return result;
    }

    @PostMapping("/dividends")
    public Map<String, Object> createDividend(
            @RequestParam long stockId, @RequestParam BigDecimal amountPerShare,
            @RequestParam String recordDate, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        Holding h = holdingDao.findByPortfolioAndStock(portfolioId, stockId);
        BigDecimal sharesHeld = h != null ? h.getTotalShares() : BigDecimal.ONE;
        Dividend d = new Dividend();
        d.setPortfolioId(portfolioId);
        d.setStockId(stockId);
        d.setAmountPerShare(amountPerShare);
        d.setSharesHeld(sharesHeld);
        d.setTotalAmount(amountPerShare.multiply(sharesHeld));
        d.setRecordDate(LocalDate.parse(recordDate));
        long id = dividendDao.insert(d);
        holdingService.rebuildHolding(portfolioId, stockId);
        valueCalculator.backfillFrom(portfolioId, LocalDate.parse(recordDate));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        return result;
    }

    @DeleteMapping("/dividends/{id}")
    public Map<String, String> deleteDividend(@PathVariable long id, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        dividendDao.findByPortfolio(portfolioId).stream()
            .filter(d -> d.getId() == id).findFirst().ifPresent(d -> {
                dividendDao.delete(id);
                holdingService.rebuildHolding(portfolioId, d.getStockId());
            });
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return result;
    }

    // ── Stock detail ────────────────────────────────────────────────────────

    @GetMapping("/stocks/{symbol}")
    public Map<String, Object> getStockDetail(@PathVariable String symbol, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("error", "No portfolio");

        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return Map.of("error", "Stock not found");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stock",        stock);
        result.put("holding",      holdingDao.findByPortfolioAndStock(portfolioId, stock.getId()));
        result.put("transactions", transactionDao.findByPortfolioAndStock(portfolioId, stock.getId()));
        result.put("dividends",    dividendDao.findByPortfolioAndStock(portfolioId, stock.getId()));
        result.put("livePrice", quoteService.getPrice(stock));
        return result;
    }

    // ── Refresh ──────────────────────────────────────────────────────────

    @GetMapping("/quote/{symbol}")
    public Map<String, Object> getQuote(@PathVariable String symbol) {
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return Map.of("error", "Stock not found");
        BigDecimal price = quoteService.getPrice(stock);
        BigDecimal cached = stockPriceDao.findLatestClose(stock.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("price", price != null ? price : cached);
        result.put("live",  price != null);
        return result;
    }

    @PostMapping("/stocks/{symbol}/refresh")
    public Map<String, String> refreshStock(@PathVariable String symbol) {
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return Map.of("error", "Stock not found");
        quoteService.getPrice(stock); // fire-and-forget real-time fetch
        return Map.of("status", "ok");
    }

    @PostMapping("/portfolio/refresh")
    public Map<String, String> refreshPortfolio(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("error", "No portfolio");
        List<HoldingSnapshot> snapshots = holdingService.getSnapshots(portfolioId);
        new Thread(() -> {
            for (HoldingSnapshot snap : snapshots) {
                Stock stock = stockDao.findBySymbol(snap.getStockSymbol());
                if (stock != null) quoteService.getPrice(stock);
            }
        }).start();
        return Map.of("status", "ok", "count", String.valueOf(snapshots.size()));
    }
}
