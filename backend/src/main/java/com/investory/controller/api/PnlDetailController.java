package com.investory.controller.api;

import com.investory.server.AppContext;
import com.investory.server.SessionHelper;
import com.investory.service.PnlLedgerService;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.LocalDate;

public class PnlDetailController {

    private final PnlLedgerService pnlLedgerService = AppContext.get(PnlLedgerService.class);

    public void handleDaily(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = SessionHelper.getPortfolioId(req);
        String date = req.getParameter("date");
        LocalDate day = LocalDate.parse(date);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(pnlLedgerService.buildDetail(pid, date, day.minusDays(1), day)));
    }

    public void handleMonthly(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = SessionHelper.getPortfolioId(req);
        int year = Integer.parseInt(req.getParameter("year"));
        int month = Integer.parseInt(req.getParameter("month"));
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate today = LocalDate.now();
        if (end.isAfter(today)) end = today;
        String label = year + "-" + String.format("%02d", month);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(pnlLedgerService.buildDetail(pid, label, start.minusDays(1), end)));
    }
}
