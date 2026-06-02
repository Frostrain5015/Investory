package com.investory.controller.api;

import com.investory.service.PnlLedgerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PnlDetailController {

    @Autowired private PnlLedgerService pnlLedgerService;

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    @GetMapping("/daily-detail")
    public Map<String, Object> daily(@RequestParam String date, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        LocalDate day = LocalDate.parse(date);
        return pnlLedgerService.buildDetail(pid, date, day.minusDays(1), day);
    }

    @GetMapping("/monthly-detail")
    public Map<String, Object> monthly(@RequestParam int year, @RequestParam int month, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate today = LocalDate.now();
        if (end.isAfter(today)) end = today;
        String label = year + "-" + String.format("%02d", month);
        return pnlLedgerService.buildDetail(pid, label, start.minusDays(1), end);
    }
}
