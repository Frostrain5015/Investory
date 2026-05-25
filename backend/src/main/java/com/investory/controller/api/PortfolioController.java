package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class PortfolioController {

    @Autowired private PortfolioDao portfolioDao;
    @Autowired private HoldingService holdingService;
    @Autowired private PortfolioAnalysisService analysisService;
    @Autowired private AuthService authService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    @GetMapping("/portfolios")
    public List<Portfolio> list(HttpServletRequest req) {
        Long userId = (Long) req.getSession().getAttribute("userId");
        return portfolioDao.findByUser(userId);
    }

    @PostMapping("/portfolios")
    public Map<String, Object> create(@RequestParam String name, HttpServletRequest req) {
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        Portfolio p = new Portfolio(); p.setUserId(userId); p.setName(name.trim());
        long id = portfolioDao.insert(p);
        s.setAttribute("portfolioId", id);
        return Map.of("id", id, "name", name.trim());
    }

    @PutMapping("/portfolios/{id}")
    public Map<String, String> update(@PathVariable long id, @RequestParam(required = false) String name,
                                       HttpServletRequest req) {
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        if (!portfolioDao.isOwner(id, userId)) return Map.of("error", "not your portfolio");
        if (name != null && !name.isBlank()) portfolioDao.updateName(id, name.trim());
        else s.setAttribute("portfolioId", id);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/portfolios/{id}")
    public Map<String, String> delete(@PathVariable long id, HttpServletRequest req) {
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        if (!portfolioDao.isOwner(id, userId)) return Map.of("error", "not your portfolio");
        portfolioDao.delete(id);
        List<Portfolio> remaining = portfolioDao.findByUser(userId);
        s.setAttribute("portfolioId", remaining.isEmpty() ? null : remaining.get(0).getId());
        return Map.of("status", "ok");
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(HttpServletRequest req) {
        long pid = getPortfolioId(req);
        if (pid == 0) return Map.of("error", "No portfolio");
        List<HoldingSnapshot> snaps = holdingService.getSnapshots(pid);
        BigDecimal holdingPnl = analysisService.totalUnrealizedPnl(snaps);
        BigDecimal realized = analysisService.totalRealizedPnl(pid);

        BigDecimal cash = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN c.currency='CNY' THEN c.amount ELSE c.amount / NULLIF(e.rate, 0) END), 0) FROM cash_balances c LEFT JOIN exchange_rates e ON c.currency=e.currency WHERE c.portfolio_id=?", BigDecimal.class, pid);
        cash = cash != null ? cash : BigDecimal.ZERO;

        BigDecimal todayPnl = BigDecimal.ZERO;
        for (HoldingSnapshot s : snaps) if (s.getChangeToday() != null) todayPnl = todayPnl.add(s.getChangeToday());

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
        r.put("cashByCurrency", jdbc.queryForList("SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", pid));
        r.put("totalReturnPct", analysisService.holdingReturnRate(totalMV, totalInvested, totalDiv));
        r.put("cumulativeReturnPct", analysisService.cumulativeReturnRate(cumulativePnl, totalInvested));
        r.put("todayPnl", todayPnl);
        BigDecimal prev = analysisService.totalMarketValue(snaps).subtract(todayPnl);
        r.put("todayPnlPct", prev.compareTo(BigDecimal.ZERO) != 0 ? todayPnl.divide(prev, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);

        List<Map<String, Object>> alloc = new ArrayList<>();
        for (HoldingSnapshot s : snaps) { Map<String, Object> a = new LinkedHashMap<>(); a.put("name", s.getStockName()); a.put("symbol", s.getStockSymbol()); a.put("value", s.getMarketValue()); a.put("currency", s.getCurrency() != null ? s.getCurrency() : "CNY"); alloc.add(a); }
        r.put("allocation", alloc);
        return r;
    }

    @GetMapping("/cash")
    public Map<String, Object> cash(HttpServletRequest req) {
        long pid = getPortfolioId(req);
        return Map.of("balances", jdbc.queryForList("SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", pid));
    }

    @PostMapping("/password")
    public Map<String, String> changePw(@RequestParam String oldPassword, @RequestParam String newPassword,
                                         HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null || s.getAttribute("userId") == null) return Map.of("error", "未登录");
        return authService.changePassword((Long) s.getAttribute("userId"), oldPassword, newPassword)
                ? Map.of("status", "ok") : Map.of("error", "原密码错误");
    }
}
