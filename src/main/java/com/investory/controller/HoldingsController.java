package com.investory.controller;

import com.investory.service.HoldingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HoldingsController {

    @Autowired private HoldingService holdingService;

    @GetMapping("/holdings")
    public String holdings(HttpServletRequest req, Model model) {
        Long portfolioId = (Long) req.getSession().getAttribute("portfolioId");
        if (portfolioId == null) return "redirect:/portfolio";
        model.addAttribute("snapshots", holdingService.getSnapshots(portfolioId));
        return "holdings";
    }
}
