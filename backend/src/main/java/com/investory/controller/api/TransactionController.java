package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.*;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;

/**
 * 交易记录管理控制器
 *
 * <p>负责模块：交易流水（买入/卖出/资金划转）与股息的增删改查，
 *   同时维护现金余额（cash_balances）的变更，确保每笔操作后账户余额准确。
 * <p>API 基础路径：/api
 */
public class TransactionController {

    private final TransactionDao transactionDao = AppContext.get(TransactionDao.class);
    private final DividendDao dividendDao = AppContext.get(DividendDao.class);
    private final StockDao stockDao = AppContext.get(StockDao.class);
    private final HoldingDao holdingDao = AppContext.get(HoldingDao.class);
    private final HoldingService holdingService = AppContext.get(HoldingService.class);
    private final PortfolioValueCalculator valueCalculator = AppContext.get(PortfolioValueCalculator.class);

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    public void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
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
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(list));
    }

    public void handleGetOne(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        Transaction t = transactionDao.findById(id);
        if (t == null || t.getPortfolioId() != pid) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "Not found")));
            return;
        }
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
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(m));
    }

    @SuppressWarnings("unchecked")
    public void handleCreate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long stockId = Long.parseLong(req.getParameter("stockId"));
        String type = req.getParameter("type");
        BigDecimal shares = new BigDecimal(req.getParameter("shares"));
        BigDecimal price = new BigDecimal(req.getParameter("price"));
        String fee = req.getParameter("fee");
        String tradeDate = req.getParameter("tradeDate");
        String currency = req.getParameter("currency");
        String note = req.getParameter("note");

        BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;
        if (currency == null || currency.isBlank()) currency = "CNY";

        if ("TRANSFER_IN".equals(type) || "TRANSFER_OUT".equals(type)) {
            if ("TRANSFER_OUT".equals(type) && !checkCash(pid, currency, shares)) {
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(JsonUtil.toJson(cashError(pid, currency, shares)));
                return;
            }
            BigDecimal amount = "TRANSFER_IN".equals(type) ? shares : shares.negate();
            jdbcUpdate("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, currency, amount, amount);
            Transaction t = buildTx(pid, null, type, shares, BigDecimal.ZERO, BigDecimal.ZERO, tradeDate, note);
            t.setCurrency(currency);
            long id = transactionDao.insert(t);
            valueCalculator.backfillFrom(pid, LocalDate.parse(tradeDate));
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("id", id)));
            return;
        }
        Stock stock = stockDao.findById(stockId);
        String cur = stock != null ? stock.getCurrency() : "CNY";
        BigDecimal cost = "BUY".equals(type) ? shares.multiply(price).add(feeVal) : BigDecimal.ZERO;
        if ("BUY".equals(type) && stock != null && !checkCash(pid, cur, cost)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(cashError(pid, cur, cost)));
            return;
        }
        applyCash(pid, cur, type, shares, price, feeVal);
        Transaction t = buildTx(pid, stockId, type, shares, price, feeVal, tradeDate, note);
        t.setCurrency(cur);
        long id = transactionDao.insert(t);
        holdingService.rebuildHolding(pid, stockId);
        if (stock != null) valueCalculator.backfillFrom(pid, LocalDate.parse(tradeDate), stockId, price, shares);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("id", id)));
    }

    @SuppressWarnings("unchecked")
    public void handleUpdate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        long stockId = Long.parseLong(req.getParameter("stockId"));
        String type = req.getParameter("type");
        BigDecimal shares = new BigDecimal(req.getParameter("shares"));
        BigDecimal price = new BigDecimal(req.getParameter("price"));
        String fee = req.getParameter("fee");
        String tradeDate = req.getParameter("tradeDate");
        String currency = req.getParameter("currency");
        String note = req.getParameter("note");

        BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;
        Transaction old = transactionDao.findById(id);
        if (old == null || old.getPortfolioId() != pid) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "Not found")));
            return;
        }
        LocalDate oldTradeDate = old.getTradeDate();
        LocalDate newTradeDate = LocalDate.parse(tradeDate);
        Long oldStockId = old.getStockId();
        reverseCash(pid, old);
        String cur = (currency != null && !currency.isBlank()) ? currency : (stockId > 0 ? getCur(stockId) : "CNY");
        if ("BUY".equals(type)) { BigDecimal c = shares.multiply(price).add(feeVal); if (!checkCash(pid, cur, c)) { applyCashDirect(pid, old); resp.setContentType("application/json;charset=UTF-8"); resp.getWriter().write(JsonUtil.toJson(cashError(pid, cur, c))); return; } }
        if ("TRANSFER_OUT".equals(type) && !checkCash(pid, cur, shares)) { applyCashDirect(pid, old); resp.setContentType("application/json;charset=UTF-8"); resp.getWriter().write(JsonUtil.toJson(cashError(pid, cur, shares))); return; }
        applyCash(pid, cur, type, shares, price, feeVal);
        Transaction t = buildTx(pid, stockId, type, shares, price, feeVal, tradeDate, note); t.setId(id); t.setCurrency(cur);
        transactionDao.update(t);
        if (stockId > 0) holdingService.rebuildHolding(pid, stockId);
        if (oldStockId != null && oldStockId > 0 && oldStockId.longValue() != stockId) {
            holdingService.rebuildHolding(pid, oldStockId);
        }
        LocalDate fromDate = oldTradeDate != null && oldTradeDate.isBefore(newTradeDate) ? oldTradeDate : newTradeDate;
        valueCalculator.backfillFrom(pid, fromDate);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    public void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        transactionDao.findByPortfolio(pid).stream().filter(t -> t.getId() == id).findFirst().ifPresent(old -> {
            try {
                reverseCash(pid, old);
                transactionDao.delete(id);
                if (old.getStockId() != null && old.getStockId() > 0) holdingService.rebuildHolding(pid, old.getStockId());
                if (old.getTradeDate() != null) valueCalculator.backfillFrom(pid, old.getTradeDate());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    // ── Private helpers ──

    private Transaction buildTx(long pid, Long sid, String type, BigDecimal sh, BigDecimal pr, BigDecimal fee, String date, String note) {
        Transaction t = new Transaction(); t.setPortfolioId(pid); t.setStockId(sid); t.setType(type);
        t.setShares(sh); t.setPrice(pr); t.setFee(fee); t.setTradeDate(LocalDate.parse(date)); t.setNote(note);
        t.setCurrency("CNY");
        return t;
    }

    private boolean checkCash(long pid, String cur, BigDecimal need) throws Exception {
        List<BigDecimal> rows = jdbcQueryForListSingle("SELECT amount FROM cash_balances WHERE portfolio_id=? AND currency=?", BigDecimal.class, pid, cur);
        BigDecimal bal = rows.isEmpty() ? BigDecimal.ZERO : rows.get(0); if (bal == null) bal = BigDecimal.ZERO;
        return bal.compareTo(need) >= 0;
    }

    private Map<String, Object> cashError(long pid, String cur, BigDecimal need) throws Exception {
        List<BigDecimal> rows = jdbcQueryForListSingle("SELECT amount FROM cash_balances WHERE portfolio_id=? AND currency=?", BigDecimal.class, pid, cur);
        BigDecimal bal = rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "INSUFFICIENT_CASH"); err.put("balance", bal); err.put("required", need); err.put("currency", cur);
        return err;
    }

    private void applyCash(long pid, String cur, String type, BigDecimal sh, BigDecimal pr, BigDecimal fee) throws Exception {
        if ("BUY".equals(type)) jdbcUpdate("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", sh.multiply(pr).add(fee), pid, cur);
        else if ("SELL".equals(type)) jdbcUpdate("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, sh.multiply(pr).subtract(fee), sh.multiply(pr).subtract(fee));
        else if ("TRANSFER_IN".equals(type)) jdbcUpdate("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, sh, sh);
        else if ("TRANSFER_OUT".equals(type)) jdbcUpdate("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", sh, pid, cur);
    }

    private void reverseCash(long pid, Transaction old) throws Exception {
        String cur = old.getCurrency(); if (cur == null && old.getStockId() != null && old.getStockId() > 0) cur = getCur(old.getStockId()); if (cur == null) cur = "CNY";
        if ("BUY".equals(old.getType())) jdbcUpdate("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, old.getShares().multiply(old.getPrice()).add(old.getFee()), old.getShares().multiply(old.getPrice()).add(old.getFee()));
        else if ("SELL".equals(old.getType())) jdbcUpdate("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", old.getShares().multiply(old.getPrice()).subtract(old.getFee()), pid, cur);
        else if ("TRANSFER_IN".equals(old.getType())) jdbcUpdate("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", old.getShares(), pid, cur);
        else if ("TRANSFER_OUT".equals(old.getType())) jdbcUpdate("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, old.getShares(), old.getShares());
    }

    private void applyCashDirect(long pid, Transaction t) throws Exception {
        String cur = t.getCurrency(); if (cur == null && t.getStockId() != null && t.getStockId() > 0) cur = getCur(t.getStockId()); if (cur == null) cur = "CNY";
        applyCash(pid, cur, t.getType(), t.getShares(), t.getPrice(), t.getFee());
    }

    private String getCur(long sid) { Stock s = stockDao.findById(sid); return s != null ? s.getCurrency() : "CNY"; }

    // ── JDBC helpers ─────────────────────────────────────────────────────

    private List<Map<String, Object>> jdbcQueryForList(String sql, Object... args) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= colCount; c++) row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    result.add(row);
                }
            }
        }
        return result;
    }

    private <T> List<T> jdbcQueryForListSingle(String sql, Class<T> clazz, Object... args) throws Exception {
        List<T> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add((T) rs.getObject(1));
                }
            }
        }
        return result;
    }

    private int jdbcUpdate(String sql, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }
}
