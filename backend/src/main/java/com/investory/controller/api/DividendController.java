package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class DividendController {

    @Autowired private DividendDao dividendDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private HoldingService holdingService;
    @Autowired private PortfolioValueCalculator valueCalculator;

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    @GetMapping("/dividends")
    public Map<String, Object> list(HttpServletRequest req) {
        return Map.of("dividends", dividendDao.findByPortfolio(getPortfolioId(req)));
    }

    @GetMapping("/dividends/{id}")
    public Map<String, Object> getOne(@PathVariable long id, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        Dividend d = dividendDao.findById(id);
        if (d == null || d.getPortfolioId() != pid) return Map.of("error", "Not found");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId()); m.put("stockId", d.getStockId()); m.put("stockName", d.getStockName());
        m.put("stockSymbol", d.getStockSymbol()); m.put("amountPerShare", d.getAmountPerShare());
        m.put("sharesHeld", d.getSharesHeld()); m.put("totalAmount", d.getTotalAmount());
        m.put("date", d.getRecordDate().toString());
        return m;
    }

    @PostMapping("/dividends")
    public Map<String, Object> create(@RequestParam long stockId, @RequestParam BigDecimal amountPerShare,
            @RequestParam String recordDate, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        Holding h = holdingDao.findByPortfolioAndStock(pid, stockId);
        if (h == null || h.getTotalShares().compareTo(BigDecimal.ZERO) <= 0)
            return Map.of("error", "该股票不在当前组合持仓中");
        BigDecimal sh = h != null ? h.getTotalShares() : BigDecimal.ONE;
        Dividend d = new Dividend(); d.setPortfolioId(pid); d.setStockId(stockId);
        d.setAmountPerShare(amountPerShare); d.setSharesHeld(sh);
        d.setTotalAmount(amountPerShare.multiply(sh)); d.setRecordDate(LocalDate.parse(recordDate));
        long id = dividendDao.insert(d);
        holdingService.rebuildHolding(pid, stockId);
        valueCalculator.backfillFrom(pid, LocalDate.parse(recordDate));
        return Map.of("id", id);
    }

    @PutMapping("/dividends/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestParam long stockId,
            @RequestParam BigDecimal amountPerShare, @RequestParam String recordDate, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        Dividend old = dividendDao.findById(id);
        if (old == null || old.getPortfolioId() != pid) return Map.of("error", "Not found");
        LocalDate oldDate = old.getRecordDate();
        Long oldStockId = old.getStockId();
        LocalDate newDate = LocalDate.parse(recordDate);
        Holding h = holdingDao.findByPortfolioAndStock(pid, stockId);
        BigDecimal sh = h != null ? h.getTotalShares() : old.getSharesHeld();
        Dividend d = new Dividend(); d.setId(id); d.setPortfolioId(pid); d.setStockId(stockId);
        d.setAmountPerShare(amountPerShare); d.setSharesHeld(sh);
        d.setTotalAmount(amountPerShare.multiply(sh)); d.setRecordDate(LocalDate.parse(recordDate));
        dividendDao.update(d);
        holdingService.rebuildHolding(pid, stockId);
        if (oldStockId != null && oldStockId > 0 && oldStockId.longValue() != stockId) {
            holdingService.rebuildHolding(pid, oldStockId);
        }
        LocalDate fromDate = oldDate != null && oldDate.isBefore(newDate) ? oldDate : newDate;
        valueCalculator.backfillFrom(pid, fromDate);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/dividends/{id}")
    public Map<String, String> delete(@PathVariable long id, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        Dividend d = dividendDao.findById(id);
        if (d != null && d.getPortfolioId() == pid) {
            dividendDao.delete(id);
            holdingService.rebuildHolding(pid, d.getStockId());
            if (d.getRecordDate() != null) valueCalculator.backfillFrom(pid, d.getRecordDate());
        }
        return Map.of("status", "ok");
    }
}
