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
import java.util.*;

/**
 * 组合（Portfolio）管理与仪表盘数据控制器
 */
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
        Long userId = (Long) req.getSession().getAttribute("userId");
        List<Portfolio> result = portfolioDao.findByUser(userId);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleCreate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        String name = req.getParameter("name");
        Portfolio p = new Portfolio(); p.setUserId(userId); p.setName(name.trim());
        long id = portfolioDao.insert(p);
        s.setAttribute("portfolioId", id);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("id", id, "name", name.trim())));
    }

    public void handleUpdate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        long id = Long.parseLong((String) req.getAttribute("id"));
        String name = req.getParameter("name");
        if (!portfolioDao.isOwner(id, userId)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not your portfolio")));
            return;
        }
        if (name != null && !name.isBlank()) {
            portfolioDao.updateName(id, name.trim());
        } else {
            s.setAttribute("portfolioId", id);
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    public void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        long id = Long.parseLong((String) req.getAttribute("id"));
        if (!portfolioDao.isOwner(id, userId)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not your portfolio")));
            return;
        }
        portfolioDao.delete(id);
        List<Portfolio> remaining = portfolioDao.findByUser(userId);
        s.setAttribute("portfolioId", remaining.isEmpty() ? null : remaining.get(0).getId());
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    public void handleDashboard(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        if (pid == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "No portfolio")));
            return;
        }
        List<HoldingSnapshot> snaps = holdingService.getSnapshots(pid);
        BigDecimal holdingPnl = analysisService.totalUnrealizedPnl(snaps);
        BigDecimal realized = analysisService.totalRealizedPnl(pid);

        BigDecimal cash = jdbcQueryForObject("SELECT COALESCE(SUM(CASE WHEN c.currency='CNY' THEN c.amount ELSE c.amount / NULLIF(e.rate, 0) END), 0) FROM cash_balances c LEFT JOIN exchange_rates e ON c.currency=e.currency WHERE c.portfolio_id=?", BigDecimal.class, pid);
        cash = cash != null ? cash : BigDecimal.ZERO;

        DailyValue latestDaily = analysisService.getTodayValue(pid);
        BigDecimal todayPnl = latestDaily != null && latestDaily.getDailyPnl() != null
                ? latestDaily.getDailyPnl()
                : BigDecimal.ZERO;

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
        r.put("cashByCurrency", jdbcQueryForList("SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", pid));
        r.put("totalReturnPct", analysisService.holdingReturnRate(totalMV, totalInvested, totalDiv));
        r.put("cumulativeReturnPct", analysisService.cumulativeReturnRate(cumulativePnl, totalInvested));
        r.put("todayPnl", todayPnl);
        BigDecimal prev = latestDaily != null && latestDaily.getTotalValue() != null
                ? latestDaily.getTotalValue().subtract(todayPnl)
                : totalMV.subtract(todayPnl);
        r.put("todayPnlPct", prev.compareTo(BigDecimal.ZERO) != 0 ? todayPnl.divide(prev, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
        List<Map<String, Object>> alloc = new ArrayList<>();
        for (HoldingSnapshot s : snaps) { Map<String, Object> a = new LinkedHashMap<>(); a.put("name", s.getStockName()); a.put("symbol", s.getStockSymbol()); a.put("value", s.getMarketValue()); a.put("currency", s.getCurrency() != null ? s.getCurrency() : "CNY"); alloc.add(a); }
        r.put("allocation", alloc);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(r));
    }

    public void handleCash(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        Map<String, Object> result = Map.of("balances", jdbcQueryForList("SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", pid));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleAdminBackfill(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Long userId = (Long) req.getSession().getAttribute("userId");
        if (userId == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "Not logged in")));
            return;
        }
        long portfolioId = Long.parseLong(req.getParameter("portfolioId"));
        String fromDate = req.getParameter("fromDate");
        java.time.LocalDate from = fromDate != null ? java.time.LocalDate.parse(fromDate)
                : java.time.LocalDate.now().minusYears(5);
        valueCalculator.backfillFrom(portfolioId, from);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok", "portfolioId", portfolioId, "fromDate", from.toString())));
    }

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

    private <T> T jdbcQueryForObject(String sql, Class<T> clazz, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return (T) rs.getObject(1);
            }
        }
        return null;
    }
}
