package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.*;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;
import com.investory.service.*;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

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
        resp.setContentType("application/json;charset=UTF-8");
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
        resp.getWriter().write(JsonUtil.toJson(list));
    }

    public void handleGetOne(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        resp.setContentType("application/json;charset=UTF-8");
        Transaction t = transactionDao.findById(id);
        if (t == null || t.getPortfolioId() != pid) {
            resp.getWriter().write("{\"error\":\"Not found\"}");
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
        resp.getWriter().write(JsonUtil.toJson(m));
    }

    public void handleCreate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long stockId = Long.parseLong(req.getParameter("stockId"));
        String type = req.getParameter("type");
        BigDecimal shares = new BigDecimal(req.getParameter("shares"));
        BigDecimal price = new BigDecimal(req.getParameter("price"));
        String feeStr = req.getParameter("fee");
        String tradeDate = req.getParameter("tradeDate");
        String currency = req.getParameter("currency");
        String note = req.getParameter("note");
        BigDecimal feeVal = (feeStr != null && !feeStr.isBlank()) ? new BigDecimal(feeStr) : BigDecimal.ZERO;
        if (currency == null || currency.isBlank()) currency = "CNY";
        resp.setContentType("application/json;charset=UTF-8");

        Connection conn = DatabaseManager.getConnection();
        try {
            conn.setAutoCommit(false);

            if ("TRANSFER_IN".equals(type) || "TRANSFER_OUT".equals(type)) {
                if ("TRANSFER_OUT".equals(type) && !checkCash(conn, pid, currency, shares)) {
                    conn.rollback();
                    resp.getWriter().write(JsonUtil.toJson(cashError(conn, pid, currency, shares)));
                    return;
                }
                BigDecimal amount = "TRANSFER_IN".equals(type) ? shares : shares.negate();
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?")) {
                    ps.setLong(1, pid); ps.setString(2, currency); ps.setBigDecimal(3, amount); ps.setBigDecimal(4, amount);
                    ps.executeUpdate();
                }
                Transaction t = buildTx(pid, null, type, shares, BigDecimal.ZERO, BigDecimal.ZERO, tradeDate, note);
                t.setCurrency(currency);
                long id = transactionDao.insert(t);
                valueCalculator.backfillFrom(pid, LocalDate.parse(tradeDate));
                conn.commit();
                resp.getWriter().write("{\"id\":" + id + "}");
                return;
            }
            Stock stock = stockDao.findById(stockId);
            String cur = stock != null ? stock.getCurrency() : "CNY";
            BigDecimal cost = "BUY".equals(type) ? shares.multiply(price).add(feeVal) : BigDecimal.ZERO;
            if ("BUY".equals(type) && stock != null && !checkCash(conn, pid, cur, cost)) {
                conn.rollback();
                resp.getWriter().write(JsonUtil.toJson(cashError(conn, pid, cur, cost)));
                return;
            }
            applyCash(conn, pid, cur, type, shares, price, feeVal);
            Transaction t = buildTx(pid, stockId, type, shares, price, feeVal, tradeDate, note);
            t.setCurrency(cur);
            long id = transactionDao.insert(t);
            holdingService.rebuildHolding(pid, stockId);
            if (stock != null) valueCalculator.backfillFrom(pid, LocalDate.parse(tradeDate), stockId, price, shares);
            conn.commit();
            resp.getWriter().write("{\"id\":" + id + "}");
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            throw e;
        } finally {
            try { conn.setAutoCommit(true); conn.close(); } catch (Exception ignored) {}
        }
    }

    public void handleUpdate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        long stockId = Long.parseLong(req.getParameter("stockId"));
        String type = req.getParameter("type");
        BigDecimal shares = new BigDecimal(req.getParameter("shares"));
        BigDecimal price = new BigDecimal(req.getParameter("price"));
        String feeStr = req.getParameter("fee");
        String tradeDate = req.getParameter("tradeDate");
        String currencyStr = req.getParameter("currency");
        String note = req.getParameter("note");
        BigDecimal feeVal = (feeStr != null && !feeStr.isBlank()) ? new BigDecimal(feeStr) : BigDecimal.ZERO;
        resp.setContentType("application/json;charset=UTF-8");

        Transaction old = transactionDao.findById(id);
        if (old == null || old.getPortfolioId() != pid) {
            resp.getWriter().write("{\"error\":\"Not found\"}");
            return;
        }
        LocalDate oldTradeDate = old.getTradeDate();
        LocalDate newTradeDate = LocalDate.parse(tradeDate);
        Long oldStockId = old.getStockId();
        String cur = (currencyStr != null && !currencyStr.isBlank()) ? currencyStr : (stockId > 0 ? getCur(stockId) : "CNY");

        Connection conn = DatabaseManager.getConnection();
        try {
            conn.setAutoCommit(false);
            reverseCash(conn, pid, old);
            if ("BUY".equals(type)) {
                BigDecimal c = shares.multiply(price).add(feeVal);
                if (!checkCash(conn, pid, cur, c)) {
                    applyCash(conn, pid, cur, old.getType(), old.getShares(), old.getPrice(), old.getFee());
                    conn.commit();
                    resp.getWriter().write(JsonUtil.toJson(cashError(conn, pid, cur, c)));
                    return;
                }
            }
            if ("TRANSFER_OUT".equals(type) && !checkCash(conn, pid, cur, shares)) {
                applyCash(conn, pid, cur, old.getType(), old.getShares(), old.getPrice(), old.getFee());
                conn.commit();
                resp.getWriter().write(JsonUtil.toJson(cashError(conn, pid, cur, shares)));
                return;
            }
            applyCash(conn, pid, cur, type, shares, price, feeVal);
            Transaction t = buildTx(pid, stockId, type, shares, price, feeVal, tradeDate, note);
            t.setId(id); t.setCurrency(cur);
            transactionDao.update(t);
            if (stockId > 0) holdingService.rebuildHolding(pid, stockId);
            if (oldStockId != null && oldStockId > 0 && oldStockId.longValue() != stockId) {
                holdingService.rebuildHolding(pid, oldStockId);
            }
            LocalDate fromDate = oldTradeDate != null && oldTradeDate.isBefore(newTradeDate) ? oldTradeDate : newTradeDate;
            valueCalculator.backfillFrom(pid, fromDate);
            conn.commit();
            resp.getWriter().write("{\"status\":\"ok\"}");
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            throw e;
        } finally {
            try { conn.setAutoCommit(true); conn.close(); } catch (Exception ignored) {}
        }
    }

    public void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        resp.setContentType("application/json;charset=UTF-8");

        Connection conn = DatabaseManager.getConnection();
        try {
            conn.setAutoCommit(false);
            transactionDao.findByPortfolio(pid).stream().filter(t -> t.getId() == id).findFirst().ifPresent(old -> {
                try {
                    reverseCash(conn, pid, old);
                    transactionDao.delete(id);
                    if (old.getStockId() != null && old.getStockId() > 0) holdingService.rebuildHolding(pid, old.getStockId());
                    if (old.getTradeDate() != null) valueCalculator.backfillFrom(pid, old.getTradeDate());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            conn.commit();
            resp.getWriter().write("{\"status\":\"ok\"}");
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            throw e;
        } finally {
            try { conn.setAutoCommit(true); conn.close(); } catch (Exception ignored) {}
        }
    }

    // ── Private helpers ──────────────────────────────────────────────

    private Transaction buildTx(long pid, Long sid, String type, BigDecimal sh, BigDecimal pr, BigDecimal fee, String date, String note) {
        Transaction t = new Transaction(); t.setPortfolioId(pid); t.setStockId(sid); t.setType(type);
        t.setShares(sh); t.setPrice(pr); t.setFee(fee); t.setTradeDate(LocalDate.parse(date)); t.setNote(note);
        t.setCurrency("CNY");
        return t;
    }

    private boolean checkCash(Connection conn, long pid, String cur, BigDecimal need) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT amount FROM cash_balances WHERE portfolio_id=? AND currency=?")) {
            ps.setLong(1, pid); ps.setString(2, cur);
            try (ResultSet rs = ps.executeQuery()) {
                BigDecimal bal = rs.next() ? rs.getBigDecimal("amount") : BigDecimal.ZERO;
                if (bal == null) bal = BigDecimal.ZERO;
                return bal.compareTo(need) >= 0;
            }
        }
    }

    private Map<String, Object> cashError(Connection conn, long pid, String cur, BigDecimal need) {
        BigDecimal bal = BigDecimal.ZERO;
        try (PreparedStatement ps = conn.prepareStatement("SELECT amount FROM cash_balances WHERE portfolio_id=? AND currency=?")) {
            ps.setLong(1, pid); ps.setString(2, cur);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) bal = rs.getBigDecimal("amount");
                if (bal == null) bal = BigDecimal.ZERO;
            }
        } catch (Exception ignored) {}
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "INSUFFICIENT_CASH"); err.put("balance", bal); err.put("required", need); err.put("currency", cur);
        return err;
    }

    private void applyCash(Connection conn, long pid, String cur, String type, BigDecimal sh, BigDecimal pr, BigDecimal fee) throws SQLException {
        if ("BUY".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?")) {
                ps.setBigDecimal(1, sh.multiply(pr).add(fee)); ps.setLong(2, pid); ps.setString(3, cur);
                ps.executeUpdate();
            }
        } else if ("SELL".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?")) {
                BigDecimal amt = sh.multiply(pr).subtract(fee);
                ps.setLong(1, pid); ps.setString(2, cur); ps.setBigDecimal(3, amt); ps.setBigDecimal(4, amt);
                ps.executeUpdate();
            }
        } else if ("TRANSFER_IN".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?")) {
                ps.setLong(1, pid); ps.setString(2, cur); ps.setBigDecimal(3, sh); ps.setBigDecimal(4, sh);
                ps.executeUpdate();
            }
        } else if ("TRANSFER_OUT".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?")) {
                ps.setBigDecimal(1, sh); ps.setLong(2, pid); ps.setString(3, cur);
                ps.executeUpdate();
            }
        }
    }

    private void reverseCash(Connection conn, long pid, Transaction old) throws SQLException {
        String cur = old.getCurrency(); if (cur == null && old.getStockId() != null && old.getStockId() > 0) cur = getCur(old.getStockId()); if (cur == null) cur = "CNY";
        if ("BUY".equals(old.getType())) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?")) {
                BigDecimal amt = old.getShares().multiply(old.getPrice()).add(old.getFee());
                ps.setLong(1, pid); ps.setString(2, cur); ps.setBigDecimal(3, amt); ps.setBigDecimal(4, amt);
                ps.executeUpdate();
            }
        } else if ("SELL".equals(old.getType())) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?")) {
                ps.setBigDecimal(1, old.getShares().multiply(old.getPrice()).subtract(old.getFee())); ps.setLong(2, pid); ps.setString(3, cur);
                ps.executeUpdate();
            }
        } else if ("TRANSFER_IN".equals(old.getType())) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?")) {
                ps.setBigDecimal(1, old.getShares()); ps.setLong(2, pid); ps.setString(3, cur);
                ps.executeUpdate();
            }
        } else if ("TRANSFER_OUT".equals(old.getType())) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?")) {
                ps.setLong(1, pid); ps.setString(2, cur); ps.setBigDecimal(3, old.getShares()); ps.setBigDecimal(4, old.getShares());
                ps.executeUpdate();
            }
        }
    }

    private void applyCashDirect(Connection conn, long pid, Transaction t) throws SQLException {
        String cur = t.getCurrency(); if (cur == null && t.getStockId() != null && t.getStockId() > 0) cur = getCur(t.getStockId()); if (cur == null) cur = "CNY";
        applyCash(conn, pid, cur, t.getType(), t.getShares(), t.getPrice(), t.getFee());
    }

    private String getCur(long sid) { Stock s = stockDao.findById(sid); return s != null ? s.getCurrency() : "CNY"; }
}
