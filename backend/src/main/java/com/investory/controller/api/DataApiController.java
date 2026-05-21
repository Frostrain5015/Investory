package com.investory.controller.api;

import com.investory.crawler.EastMoneyCrawler;
import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.model.Quote;
import com.investory.service.AuthService;
import com.investory.service.HoldingService;
import com.investory.service.PortfolioAnalysisService;
import com.investory.service.PortfolioValueCalculator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
    @Autowired private AuthService authService;
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
        BigDecimal holdingPnl = analysisService.totalUnrealizedPnl(snapshots);
        BigDecimal realizedPnl = analysisService.totalRealizedPnl(portfolioId);
        result.put("totalPnl",      holdingPnl);
        result.put("realizedPnl",   realizedPnl);
        result.put("cumulativePnl", holdingPnl.add(realizedPnl));

        // Sum cash balances converted to CNY (CNY rate=1, others via exchange_rates)
        BigDecimal cashBalance = jdbc.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN c.currency='CNY' THEN c.amount ELSE c.amount / NULLIF(e.rate, 0) END), 0) FROM cash_balances c LEFT JOIN exchange_rates e ON c.currency = e.currency WHERE c.portfolio_id=?",
            BigDecimal.class, portfolioId);
        cashBalance = cashBalance != null ? cashBalance : BigDecimal.ZERO;
        result.put("cashBalance", cashBalance);
        List<Map<String, Object>> cashByCurrency = jdbc.queryForList(
            "SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", portfolioId);
        result.put("cashByCurrency", cashByCurrency);

        result.put("totalReturnPct",   analysisService.cashWeightedReturn(portfolioId,
            analysisService.totalMarketValue(snapshots),
            analysisService.totalInvested(snapshots),
            cashBalance,
            analysisService.totalDividends(snapshots)));

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

    // ── Cash balances ────────────────────────────────────────────────────────

    @GetMapping("/cash")
    public Map<String, Object> getCash(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        List<Map<String, Object>> balances = jdbc.queryForList(
            "SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", portfolioId);
        return Map.of("balances", balances);
    }

    // ── Password ─────────────────────────────────────────────────────────

    @PostMapping("/password")
    public Map<String, String> changePassword(@RequestParam String oldPassword,
            @RequestParam String newPassword, HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userId") == null)
            return Map.of("error", "未登录");
        long userId = (Long) session.getAttribute("userId");
        boolean ok = authService.changePassword(userId, oldPassword, newPassword);
        return ok ? Map.of("status", "ok") : Map.of("error", "原密码错误");
    }

    // ── Closed positions ──────────────────────────────────────────────────

    @GetMapping("/closed-positions")
    public List<Map<String, Object>> getClosedPositions(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        return analysisService.getClosedPositions(portfolioId);
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
            m.put("stockMarket",   t.getStockMarket());
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
    public ResponseEntity<Map<String, Object>> createTransaction(
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
            t.setStockId(null);
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
            return ResponseEntity.ok(result);
        }

        Stock stock = stockDao.findById(stockId);
        String cur = stock != null ? stock.getCurrency() : "CNY";
        BigDecimal cost = BigDecimal.ZERO;

        if ("BUY".equals(type)) cost = shares.multiply(price).add(feeVal);

        // Guard: block BUY if cash balance is insufficient
        if ("BUY".equals(type) && stock != null) {
            List<BigDecimal> rows = jdbc.queryForList(
                "SELECT amount FROM cash_balances WHERE portfolio_id=? AND currency=?", BigDecimal.class, portfolioId, cur);
            BigDecimal balance = rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
            if (balance == null) balance = BigDecimal.ZERO;
            if (balance.compareTo(cost) < 0) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "INSUFFICIENT_CASH");
                err.put("balance", balance);
                err.put("required", cost);
                err.put("currency", cur);
                return ResponseEntity.badRequest().body(err);
            }
        }

        // Deduct/add to cash_balances for BUY/SELL
        if ("BUY".equals(type)) {
            jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?",
                cost, portfolioId, cur);
        } else if ("SELL".equals(type)) {
            BigDecimal proceeds = shares.multiply(price).subtract(feeVal);
            jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?",
                portfolioId, cur, proceeds, proceeds);
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
        if (stock != null) {
            valueCalculator.backfillFrom(portfolioId, LocalDate.parse(tradeDate), stockId, price, shares);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        return ResponseEntity.ok(result);
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
            // Reverse cash balance changes
            String type = old.getType();
            // Get currency: use transaction's currency field, or look up from stock
            String cur = old.getCurrency();
            if (cur == null && old.getStockId() != null && old.getStockId() > 0) {
                cur = getStockCurrency(old.getStockId());
            }
            if (cur == null) cur = "CNY";

            if ("BUY".equals(type)) {
                BigDecimal cost = old.getShares().multiply(old.getPrice()).add(old.getFee());
                jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?",
                    portfolioId, cur, cost, cost);
            } else if ("SELL".equals(type)) {
                BigDecimal proceeds = old.getShares().multiply(old.getPrice()).subtract(old.getFee());
                jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?",
                    proceeds, portfolioId, cur);
            } else if ("TRANSFER_IN".equals(type)) {
                jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?",
                    old.getShares(), portfolioId, cur);
            } else if ("TRANSFER_OUT".equals(type)) {
                jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?",
                    portfolioId, cur, old.getShares(), old.getShares());
            }
            transactionDao.delete(id);
            if (old.getStockId() != null && old.getStockId() > 0) {
                holdingService.rebuildHolding(portfolioId, old.getStockId());
            }
        });
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return result;
    }

    private String getStockCurrency(long stockId) {
        Stock s = stockDao.findById(stockId);
        return s != null ? s.getCurrency() : "CNY";
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
        Quote liveQuote = quoteService.getQuote(stock);
        result.put("livePrice", liveQuote != null ? liveQuote.price() : null);
        if (liveQuote != null) {
            result.put("livePriceTs", liveQuote.fetchedAt().toString());
        } else {
            StockPrice latest = stockPriceDao.findLatest(stock.getId());
            result.put("livePriceTs", latest != null ? latest.getTradeDate().toString() : null);
        }
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

    // ── Daily detail ─────────────────────────────────────────────────────────

    @GetMapping("/daily-detail")
    public Map<String, Object> getDailyDetail(@RequestParam String date, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        LocalDate day = LocalDate.parse(date);
        LocalDate prevDay = day.minusDays(1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);

        // Load exchange rates
        Map<String, java.math.BigDecimal> toCny = new java.util.HashMap<>();
        toCny.put("CNY", java.math.BigDecimal.ONE);
        try {
            List<Map<String, Object>> rates = jdbc.queryForList("SELECT currency, rate FROM exchange_rates");
            for (Map<String, Object> r : rates) {
                String curr = (String) r.get("currency");
                java.math.BigDecimal rate = (java.math.BigDecimal) r.get("rate");
                if (rate != null && rate.compareTo(java.math.BigDecimal.ZERO) > 0)
                    toCny.put(curr, java.math.BigDecimal.ONE.divide(rate, 8, java.math.RoundingMode.HALF_UP));
            }
        } catch (Exception ignored) {}

        // Compute per-stock shares held on `day` (accumulate BUY/SELL up to and including day)
        List<Map<String, Object>> allTxns = jdbc.queryForList(
            "SELECT stock_id, type, shares FROM transactions WHERE portfolio_id=? AND type IN ('BUY','SELL') AND trade_date <= ? ORDER BY trade_date",
            portfolioId, java.sql.Date.valueOf(day));
        Map<Long, java.math.BigDecimal> sharesMap = new java.util.LinkedHashMap<>();
        for (Map<String, Object> t : allTxns) {
            long sid = ((Number) t.get("stock_id")).longValue();
            java.math.BigDecimal shares = (java.math.BigDecimal) t.get("shares");
            if (shares == null) continue;
            java.math.BigDecimal cur = sharesMap.getOrDefault(sid, java.math.BigDecimal.ZERO);
            sharesMap.put(sid, "BUY".equals(t.get("type")) ? cur.add(shares) : cur.subtract(shares));
        }

        // Per-holding P&L = (close_day - close_prev) * shares * fxRate
        List<Map<String, Object>> holdings = new ArrayList<>();
        java.math.BigDecimal totalPnl = java.math.BigDecimal.ZERO;
        for (Map.Entry<Long, java.math.BigDecimal> entry : sharesMap.entrySet()) {
            long sid = entry.getKey();
            java.math.BigDecimal sh = entry.getValue();
            if (sh.compareTo(java.math.BigDecimal.ZERO) <= 0) continue;
            Stock stock = stockDao.findById(sid);
            if (stock == null) continue;
            java.math.BigDecimal rate = toCny.getOrDefault(stock.getCurrency(), java.math.BigDecimal.ONE);
            List<StockPrice> todayPrices = stockPriceDao.findRange(sid, day, day);
            List<StockPrice> prevPrices = stockPriceDao.findRange(sid, prevDay, prevDay);
            if (todayPrices.isEmpty() || prevPrices.isEmpty()) continue;
            java.math.BigDecimal closeToday = todayPrices.get(0).getClose();
            java.math.BigDecimal closePrev  = prevPrices.get(0).getClose();
            if (closeToday == null || closePrev == null) continue;
            java.math.BigDecimal pnl = closeToday.subtract(closePrev).multiply(sh).multiply(rate)
                .setScale(2, java.math.RoundingMode.HALF_UP);
            java.math.BigDecimal pct = closePrev.compareTo(java.math.BigDecimal.ZERO) > 0
                ? closeToday.subtract(closePrev).divide(closePrev, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new java.math.BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO;
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("stockName", stock.getName());
            h.put("symbol", stock.getSymbol());
            h.put("pnl", pnl);
            h.put("priceChange", pct);
            holdings.add(h);
            totalPnl = totalPnl.add(pnl);
        }
        result.put("totalPnl", totalPnl.setScale(2, java.math.RoundingMode.HALF_UP));
        result.put("holdings", holdings);

        // Transactions on that day
        List<Map<String, Object>> txns = jdbc.queryForList("""
            SELECT t.type, s.name AS stockName, t.shares, t.price
            FROM transactions t LEFT JOIN stocks s ON t.stock_id = s.id
            WHERE t.portfolio_id=? AND t.trade_date=?
            """, portfolioId, java.sql.Date.valueOf(day));
        result.put("transactions", txns);
        return result;
    }

    @GetMapping("/monthly-detail")
    public Map<String, Object> getMonthlyDetail(@RequestParam int year, @RequestParam int month, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.withDayOfMonth(startOfMonth.lengthOfMonth());
        LocalDate endOfPrevMonth = startOfMonth.minusDays(1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("month", month);

        // Exchange rates
        Map<String, java.math.BigDecimal> toCny = new java.util.HashMap<>();
        toCny.put("CNY", java.math.BigDecimal.ONE);
        try {
            List<Map<String, Object>> rates = jdbc.queryForList("SELECT currency, rate FROM exchange_rates");
            for (Map<String, Object> r : rates) {
                String curr = (String) r.get("currency");
                java.math.BigDecimal rate = (java.math.BigDecimal) r.get("rate");
                if (rate != null && rate.compareTo(java.math.BigDecimal.ZERO) > 0)
                    toCny.put(curr, java.math.BigDecimal.ONE.divide(rate, 8, java.math.RoundingMode.HALF_UP));
            }
        } catch (Exception ignored) {}

        // Shares held at start of month (transactions up to end of prev month)
        List<Map<String, Object>> allTxns = jdbc.queryForList(
            "SELECT stock_id, type, shares FROM transactions WHERE portfolio_id=? AND type IN ('BUY','SELL') AND trade_date <= ? ORDER BY trade_date",
            portfolioId, java.sql.Date.valueOf(endOfPrevMonth));
        Map<Long, java.math.BigDecimal> sharesMap = new java.util.LinkedHashMap<>();
        for (Map<String, Object> t : allTxns) {
            long sid = ((Number) t.get("stock_id")).longValue();
            java.math.BigDecimal shares = (java.math.BigDecimal) t.get("shares");
            if (shares == null) continue;
            java.math.BigDecimal cur = sharesMap.getOrDefault(sid, java.math.BigDecimal.ZERO);
            sharesMap.put(sid, "BUY".equals(t.get("type")) ? cur.add(shares) : cur.subtract(shares));
        }

        // Per-holding monthly P&L: (lastClose_thisMonth - lastClose_prevMonth) * shares * fxRate
        List<Map<String, Object>> holdings = new ArrayList<>();
        java.math.BigDecimal totalPnl = java.math.BigDecimal.ZERO;
        for (Map.Entry<Long, java.math.BigDecimal> entry : sharesMap.entrySet()) {
            long sid = entry.getKey();
            java.math.BigDecimal sh = entry.getValue();
            if (sh.compareTo(java.math.BigDecimal.ZERO) <= 0) continue;
            Stock stock = stockDao.findById(sid);
            if (stock == null) continue;
            java.math.BigDecimal rate = toCny.getOrDefault(stock.getCurrency(), java.math.BigDecimal.ONE);
            List<StockPrice> prevPrices = stockPriceDao.findRange(sid, endOfPrevMonth.minusDays(30), endOfPrevMonth);
            if (prevPrices.isEmpty()) continue;
            java.math.BigDecimal prevClose = prevPrices.get(prevPrices.size() - 1).getClose();
            List<StockPrice> thisPrices = stockPriceDao.findRange(sid, startOfMonth, endOfMonth);
            if (thisPrices.isEmpty()) continue;
            java.math.BigDecimal thisClose = thisPrices.get(thisPrices.size() - 1).getClose();
            if (prevClose == null || thisClose == null) continue;
            java.math.BigDecimal pnl = thisClose.subtract(prevClose).multiply(sh).multiply(rate)
                .setScale(2, java.math.RoundingMode.HALF_UP);
            java.math.BigDecimal pct = prevClose.compareTo(java.math.BigDecimal.ZERO) > 0
                ? thisClose.subtract(prevClose).divide(prevClose, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new java.math.BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO;
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("stockName", stock.getName());
            h.put("symbol", stock.getSymbol());
            h.put("pnl", pnl);
            h.put("priceChange", pct);
            holdings.add(h);
            totalPnl = totalPnl.add(pnl);
        }
        result.put("totalPnl", totalPnl.setScale(2, java.math.RoundingMode.HALF_UP));
        result.put("holdings", holdings);

        // Transactions for the month
        List<Map<String, Object>> txns = jdbc.queryForList("""
            SELECT t.type, s.name AS stockName, t.shares, t.price
            FROM transactions t LEFT JOIN stocks s ON t.stock_id = s.id
            WHERE t.portfolio_id=? AND t.trade_date BETWEEN ? AND ?
            """, portfolioId, java.sql.Date.valueOf(startOfMonth), java.sql.Date.valueOf(endOfMonth));
        result.put("transactions", txns);
        return result;
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
