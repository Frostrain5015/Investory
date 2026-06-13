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
import java.util.*;

public class PortfolioController {

    private final PortfolioDao portfolioDao = AppContext.get(PortfolioDao.class);
    private final HoldingService holdingService = AppContext.get(HoldingService.class);
    private final PortfolioAnalysisService analysisService = AppContext.get(PortfolioAnalysisService.class);
    private final AuthService authService = AppContext.get(AuthService.class);
    private final PortfolioValueCalculator valueCalculator = AppContext.get(PortfolioValueCalculator.class);

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    public void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        Long userId = (Long) req.getSession().getAttribute("userId");
        resp.getWriter().write(JsonUtil.toJson(portfolioDao.findByUser(userId)));
    }

    public void handleCreate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        String name = req.getParameter("name");
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        Portfolio p = new Portfolio(); p.setUserId(userId); p.setName(name.trim());
        long id = portfolioDao.insert(p);
        s.setAttribute("portfolioId", id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("name", name.trim());
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleUpdate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long id = Long.parseLong((String) req.getAttribute("id"));
        String name = req.getParameter("name");
        resp.setContentType("application/json;charset=UTF-8");
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        if (!portfolioDao.isOwner(id, userId)) {
            resp.getWriter().write("{\"error\":\"not your portfolio\"}");
            return;
        }
        if (name != null && !name.isBlank()) {
            portfolioDao.updateName(id, name.trim());
        } else {
            s.setAttribute("portfolioId", id);
        }
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    public void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long id = Long.parseLong((String) req.getAttribute("id"));
        resp.setContentType("application/json;charset=UTF-8");
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        if (!portfolioDao.isOwner(id, userId)) {
            resp.getWriter().write("{\"error\":\"not your portfolio\"}");
            return;
        }
        portfolioDao.delete(id);
        List<Portfolio> remaining = portfolioDao.findByUser(userId);
        s.setAttribute("portfolioId", remaining.isEmpty() ? null : remaining.get(0).getId());
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    public void handleDashboard(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (pid == 0) {
            resp.getWriter().write("{\"error\":\"No portfolio\"}");
            return;
        }
        List<HoldingSnapshot> snaps = holdingService.getSnapshots(pid);
        BigDecimal holdingPnl = analysisService.totalUnrealizedPnl(snaps);
        BigDecimal realized = analysisService.totalRealizedPnl(pid);

        BigDecimal cash;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(CASE WHEN c.currency='CNY' THEN c.amount ELSE c.amount / NULLIF(e.rate, 0) END), 0) FROM cash_balances c LEFT JOIN exchange_rates e ON c.currency=e.currency WHERE c.portfolio_id=?")) {
            ps.setLong(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                cash = rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
                if (cash == null) cash = BigDecimal.ZERO;
            }
        }

        DailyValue latestDaily = analysisService.getTodayValue(pid);
        BigDecimal todayPnl = latestDaily != null && latestDaily.getDailyPnl() != null
                ? latestDaily.getDailyPnl() : BigDecimal.ZERO;

        Map<String, Object> r = new LinkedHashMap<>();
        BigDecimal totalMV = analysisService.totalMarketValue(snaps);
        BigDecimal totalInvested = analysisService.totalInvested(snaps);
        BigDecimal totalDiv = analysisService.totalDividends(snaps);
        BigDecimal cumulativePnl = holdingPnl.add(realized);
        r.put("snapshots", snaps);
        r.put("totalMarketValue", totalMV);
        r.put("totalInvested", totalInvested);
        r.put("totalPnl", holdingPnl);
        r.put("realizedPnl", realized);
        r.put("cumulativePnl", cumulativePnl);
        r.put("cashBalance", cash);
        r.put("cashByCurrency", queryForList("SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", pid));
        r.put("totalReturnPct", analysisService.holdingReturnRate(totalMV, totalInvested, totalDiv));
        r.put("cumulativeReturnPct", analysisService.cumulativeReturnRate(cumulativePnl, totalInvested));
        r.put("todayPnl", todayPnl);
        BigDecimal prev = latestDaily != null && latestDaily.getTotalValue() != null
                ? latestDaily.getTotalValue().subtract(todayPnl) : totalMV.subtract(todayPnl);
        r.put("todayPnlPct", prev.compareTo(BigDecimal.ZERO) != 0 ? todayPnl.divide(prev, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
        List<Map<String, Object>> alloc = new ArrayList<>();
        for (HoldingSnapshot s : snaps) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("name", s.getStockName()); a.put("symbol", s.getStockSymbol());
            a.put("value", s.getMarketValue()); a.put("currency", s.getCurrency() != null ? s.getCurrency() : "CNY");
            alloc.add(a);
        }
        r.put("allocation", alloc);
        resp.getWriter().write(JsonUtil.toJson(r));
    }

    public void handleCash(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        resp.setContentType("application/json;charset=UTF-8");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balances", queryForList("SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", pid));
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleAdminBackfill(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        long portfolioId = Long.parseLong(req.getParameter("portfolioId"));
        String fromDate = req.getParameter("fromDate");
        Long userId = (Long) req.getSession().getAttribute("userId");
        if (userId == null) {
            resp.getWriter().write("{\"error\":\"Not logged in\"}");
            return;
        }
        java.time.LocalDate from = fromDate != null ? java.time.LocalDate.parse(fromDate) : java.time.LocalDate.now().minusYears(5);
        valueCalculator.backfillFrom(portfolioId, from);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok"); result.put("portfolioId", portfolioId); result.put("fromDate", from.toString());
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    private List<Map<String, Object>> queryForList(String sql, Object... params) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int colCount = rsmd.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(rsmd.getColumnLabel(i), rs.getObject(i));
                    }
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
        return results;
    }
}
