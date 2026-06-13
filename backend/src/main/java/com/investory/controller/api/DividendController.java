package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.*;
import com.investory.server.AppContext;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public class DividendController {

    private final DividendDao dividendDao = AppContext.get(DividendDao.class);
    private final HoldingDao holdingDao = AppContext.get(HoldingDao.class);
    private final HoldingService holdingService = AppContext.get(HoldingService.class);
    private final PortfolioValueCalculator valueCalculator = AppContext.get(PortfolioValueCalculator.class);

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    public void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> result = Map.of("dividends", dividendDao.findByPortfolio(getPortfolioId(req)));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleGetOne(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        Dividend d = dividendDao.findById(id);
        if (d == null || d.getPortfolioId() != pid) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "Not found")));
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId()); m.put("stockId", d.getStockId()); m.put("stockName", d.getStockName());
        m.put("stockSymbol", d.getStockSymbol()); m.put("amountPerShare", d.getAmountPerShare());
        m.put("sharesHeld", d.getSharesHeld()); m.put("totalAmount", d.getTotalAmount());
        m.put("date", d.getRecordDate().toString());
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(m));
    }

    public void handleCreate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long stockId = Long.parseLong(req.getParameter("stockId"));
        BigDecimal amountPerShare = new BigDecimal(req.getParameter("amountPerShare"));
        String recordDate = req.getParameter("recordDate");
        Holding h = holdingDao.findByPortfolioAndStock(pid, stockId);
        if (h == null || h.getTotalShares().compareTo(BigDecimal.ZERO) <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "该股票不在当前组合持仓中")));
            return;
        }
        BigDecimal sh = h != null ? h.getTotalShares() : BigDecimal.ONE;
        Dividend d = new Dividend(); d.setPortfolioId(pid); d.setStockId(stockId);
        d.setAmountPerShare(amountPerShare); d.setSharesHeld(sh);
        d.setTotalAmount(amountPerShare.multiply(sh)); d.setRecordDate(LocalDate.parse(recordDate));
        long id = dividendDao.insert(d);
        holdingService.rebuildHolding(pid, stockId);
        valueCalculator.backfillFrom(pid, LocalDate.parse(recordDate));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("id", id)));
    }

    public void handleUpdate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        long stockId = Long.parseLong(req.getParameter("stockId"));
        BigDecimal amountPerShare = new BigDecimal(req.getParameter("amountPerShare"));
        String recordDate = req.getParameter("recordDate");
        Dividend old = dividendDao.findById(id);
        if (old == null || old.getPortfolioId() != pid) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "Not found")));
            return;
        }
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
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    public void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        Dividend d = dividendDao.findById(id);
        if (d != null && d.getPortfolioId() == pid) {
            dividendDao.delete(id);
            holdingService.rebuildHolding(pid, d.getStockId());
            if (d.getRecordDate() != null) valueCalculator.backfillFrom(pid, d.getRecordDate());
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }
}
