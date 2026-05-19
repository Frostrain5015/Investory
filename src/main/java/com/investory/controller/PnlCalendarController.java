package com.investory.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Year;

@Controller
public class PnlCalendarController {

    @GetMapping("/pnl-calendar")
    public String pnlCalendar(@RequestParam(required = false) Integer year,
                              HttpServletRequest req, Model model) {
        Long portfolioId = (Long) req.getSession().getAttribute("portfolioId");
        if (portfolioId == null) return "redirect:/portfolio";
        int y = year != null ? year : Year.now().getValue();
        model.addAttribute("year",     y);
        model.addAttribute("prevYear", y - 1);
        model.addAttribute("nextYear", y + 1);
        return "pnl-calendar";
    }
}
