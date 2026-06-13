package com.investory.controller.api;

import com.investory.service.PnlLedgerService;
import com.investory.server.AppContext;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDate;
import java.util.Map;

public class PnlDetailController {

    private final PnlLedgerService pnlLedgerService = AppContext.get(PnlLedgerService.class);

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    public void handleDaily(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        String date = req.getParameter("date");
        LocalDate day = LocalDate.parse(date);
        Map<String, Object> result = pnlLedgerService.buildDetail(pid, date, day.minusDays(1), day);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleMonthly(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        int year = Integer.parseInt(req.getParameter("year"));
        int month = Integer.parseInt(req.getParameter("month"));
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate today = LocalDate.now();
        if (end.isAfter(today)) end = today;
        String label = year + "-" + String.format("%02d", month);
        Map<String, Object> result = pnlLedgerService.buildDetail(pid, label, start.minusDays(1), end);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }
}
