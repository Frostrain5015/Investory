package com.investory.controller.api;

import com.investory.crawler.EastMoneyCrawler;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.HoldingService;
import com.investory.service.PortfolioAnalysisService;
import com.investory.service.PortfolioValueCalculator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired private PortfolioAnalysisService analysisService;
    @Autowired private EastMoneyCrawler crawler;
    @Autowired private PortfolioValueCalculator valueCalculator;

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
    public Map<String, String> setActivePortfolio(@PathVariable long id, HttpServletRequest req) {
        req.getSession().setAttribute("portfolioId", id);
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
        result.put("totalReturnPct",   analysisService.overallReturnPct(snapshots));
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
    public Map<String, Object> getTransactions(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactions", transactionDao.findByPortfolio(portfolioId));
        return result;
    }

    @PostMapping("/transactions")
    public Map<String, Object> createTransaction(
            @RequestParam long stockId, @RequestParam String type,
            @RequestParam BigDecimal shares, @RequestParam BigDecimal price,
            @RequestParam(required = false) String fee, @RequestParam String tradeDate,
            @RequestParam(required = false) String note, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;
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
        if (stock != null) new Thread(() -> crawler.fetchHistory(stock)).start();
        valueCalculator.backfillFrom(portfolioId, LocalDate.parse(tradeDate));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
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
        return result;
    }
}
