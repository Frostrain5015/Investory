package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class TransactionController {

    @Autowired private TransactionDao transactionDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private StockDao stockDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private HoldingService holdingService;
    @Autowired private PortfolioValueCalculator valueCalculator;
    @Autowired private JdbcTemplate jdbc;

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    @GetMapping("/transactions")
    public List<Map<String, Object>> list(HttpServletRequest req) {
        long pid = getPortfolioId(req);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Transaction t : transactionDao.findByPortfolio(pid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId()); m.put("date", t.getTradeDate().toString());
            m.put("type", t.getType()); m.put("stockName", t.getStockName());
            m.put("stockSymbol", t.getStockSymbol()); m.put("stockMarket", t.getStockMarket());
            m.put("shares", t.getShares()); m.put("price", t.getPrice());
            m.put("fee", t.getFee()); m.put("note", t.getNote());
            list.add(m);
        }
        for (Dividend d : dividendDao.findByPortfolio(pid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId()); m.put("date", d.getRecordDate().toString());
            m.put("type", "DIV"); m.put("stockName", d.getStockName());
            m.put("stockSymbol", d.getStockSymbol()); m.put("amountPerShare", d.getAmountPerShare());
            m.put("sharesHeld", d.getSharesHeld()); m.put("totalAmount", d.getTotalAmount());
            list.add(m);
        }
        list.sort((a, b) -> ((String) b.get("date")).compareTo((String) a.get("date")));
        return list;
    }

    @GetMapping("/transactions/{id}")
    public Map<String, Object> getOne(@PathVariable long id, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        Transaction t = transactionDao.findById(id);
        if (t == null || t.getPortfolioId() != pid) return Map.of("error", "Not found");
        String cur = "CNY";
        if (t.getStockId() != null && t.getStockId() > 0) {
            Stock s = stockDao.findById(t.getStockId()); cur = s != null ? s.getCurrency() : "CNY";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId()); m.put("stockId", t.getStockId()); m.put("stockName", t.getStockName());
        m.put("stockSymbol", t.getStockSymbol()); m.put("stockMarket", t.getStockMarket());
        m.put("currency", cur); m.put("date", t.getTradeDate().toString()); m.put("type", t.getType());
        m.put("shares", t.getShares()); m.put("price", t.getPrice()); m.put("fee", t.getFee());
        m.put("note", t.getNote());
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/transactions")
    public ResponseEntity<Map<String, Object>> create(@RequestParam long stockId, @RequestParam String type,
            @RequestParam BigDecimal shares, @RequestParam BigDecimal price,
            @RequestParam(required = false) String fee, @RequestParam String tradeDate,
            @RequestParam(required = false, defaultValue = "CNY") String currency,
            @RequestParam(required = false) String note, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;
        if ("TRANSFER_IN".equals(type) || "TRANSFER_OUT".equals(type)) {
            BigDecimal amount = "TRANSFER_IN".equals(type) ? shares : shares.negate();
            jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, currency, amount, amount);
            Transaction t = buildTx(pid, null, type, shares, BigDecimal.ZERO, BigDecimal.ZERO, tradeDate, note);
            long id = transactionDao.insert(t);
            valueCalculator.backfillFrom(pid, LocalDate.parse(tradeDate));
            return ResponseEntity.ok(Map.of("id", id));
        }
        Stock stock = stockDao.findById(stockId);
        String cur = stock != null ? stock.getCurrency() : "CNY";
        BigDecimal cost = "BUY".equals(type) ? shares.multiply(price).add(feeVal) : BigDecimal.ZERO;
        if ("BUY".equals(type) && stock != null && !checkCash(pid, cur, cost))
            return ResponseEntity.badRequest().body(cashError(pid, cur, cost));
        applyCash(pid, cur, type, shares, price, feeVal);
        Transaction t = buildTx(pid, stockId, type, shares, price, feeVal, tradeDate, note);
        long id = transactionDao.insert(t);
        holdingService.rebuildHolding(pid, stockId);
        if (stock != null) valueCalculator.backfillFrom(pid, LocalDate.parse(tradeDate), stockId, price, shares);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/transactions/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id, @RequestParam long stockId,
            @RequestParam String type, @RequestParam BigDecimal shares, @RequestParam BigDecimal price,
            @RequestParam(required = false) String fee, @RequestParam String tradeDate,
            @RequestParam(required = false) String currency, @RequestParam(required = false) String note,
            HttpServletRequest req) {
        long pid = getPortfolioId(req);
        BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;
        Transaction old = transactionDao.findById(id);
        if (old == null || old.getPortfolioId() != pid) return ResponseEntity.badRequest().body(Map.of("error", "Not found"));
        reverseCash(pid, old);
        String cur = (currency != null && !currency.isBlank()) ? currency : (stockId > 0 ? getCur(stockId) : "CNY");
        if ("BUY".equals(type)) { BigDecimal c = shares.multiply(price).add(feeVal); if (!checkCash(pid, cur, c)) { applyCashDirect(pid, old); return ResponseEntity.badRequest().body(cashError(pid, cur, c)); } }
        if ("TRANSFER_OUT".equals(type) && !checkCash(pid, cur, shares)) { applyCashDirect(pid, old); return ResponseEntity.badRequest().body(cashError(pid, cur, shares)); }
        applyCash(pid, cur, type, shares, price, feeVal);
        Transaction t = buildTx(pid, stockId, type, shares, price, feeVal, tradeDate, note); t.setId(id);
        transactionDao.update(t);
        holdingService.rebuildHolding(pid, stockId);
        valueCalculator.backfillFrom(pid, LocalDate.parse(tradeDate));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @Transactional(rollbackFor = Exception.class)
    @DeleteMapping("/transactions/{id}")
    public Map<String, String> delete(@PathVariable long id, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        transactionDao.findByPortfolio(pid).stream().filter(t -> t.getId() == id).findFirst().ifPresent(old -> {
            reverseCash(pid, old);
            transactionDao.delete(id);
            if (old.getStockId() != null && old.getStockId() > 0) holdingService.rebuildHolding(pid, old.getStockId());
        });
        return Map.of("status", "ok");
    }

    // helpers
    private Transaction buildTx(long pid, Long sid, String type, BigDecimal sh, BigDecimal pr, BigDecimal fee, String date, String note) {
        Transaction t = new Transaction(); t.setPortfolioId(pid); t.setStockId(sid); t.setType(type);
        t.setShares(sh); t.setPrice(pr); t.setFee(fee); t.setTradeDate(LocalDate.parse(date)); t.setNote(note);
        return t;
    }
    private boolean checkCash(long pid, String cur, BigDecimal need) {
        List<BigDecimal> rows = jdbc.queryForList("SELECT amount FROM cash_balances WHERE portfolio_id=? AND currency=?", BigDecimal.class, pid, cur);
        BigDecimal bal = rows.isEmpty() ? BigDecimal.ZERO : rows.get(0); if (bal == null) bal = BigDecimal.ZERO;
        return bal.compareTo(need) >= 0;
    }
    private Map<String, Object> cashError(long pid, String cur, BigDecimal need) {
        List<BigDecimal> rows = jdbc.queryForList("SELECT amount FROM cash_balances WHERE portfolio_id=? AND currency=?", BigDecimal.class, pid, cur);
        BigDecimal bal = rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "INSUFFICIENT_CASH"); err.put("balance", bal); err.put("required", need); err.put("currency", cur);
        return err;
    }
    private void applyCash(long pid, String cur, String type, BigDecimal sh, BigDecimal pr, BigDecimal fee) {
        if ("BUY".equals(type)) jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", sh.multiply(pr).add(fee), pid, cur);
        else if ("SELL".equals(type)) jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, sh.multiply(pr).subtract(fee), sh.multiply(pr).subtract(fee));
        else if ("TRANSFER_IN".equals(type)) jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, sh, sh);
        else if ("TRANSFER_OUT".equals(type)) jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", sh, pid, cur);
    }
    private void reverseCash(long pid, Transaction old) {
        String cur = old.getCurrency(); if (cur == null && old.getStockId() != null && old.getStockId() > 0) cur = getCur(old.getStockId()); if (cur == null) cur = "CNY";
        if ("BUY".equals(old.getType())) jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, old.getShares().multiply(old.getPrice()).add(old.getFee()), old.getShares().multiply(old.getPrice()).add(old.getFee()));
        else if ("SELL".equals(old.getType())) jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", old.getShares().multiply(old.getPrice()).subtract(old.getFee()), pid, cur);
        else if ("TRANSFER_IN".equals(old.getType())) jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", old.getShares(), pid, cur);
        else if ("TRANSFER_OUT".equals(old.getType())) jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, old.getShares(), old.getShares());
    }
    private void applyCashDirect(long pid, Transaction t) {
        String cur = t.getCurrency(); if (cur == null && t.getStockId() != null && t.getStockId() > 0) cur = getCur(t.getStockId()); if (cur == null) cur = "CNY";
        applyCash(pid, cur, t.getType(), t.getShares(), t.getPrice(), t.getFee());
    }
    private String getCur(long sid) { Stock s = stockDao.findById(sid); return s != null ? s.getCurrency() : "CNY"; }
}
