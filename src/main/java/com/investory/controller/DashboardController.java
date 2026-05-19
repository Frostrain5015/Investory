package com.investory.controller;

import com.investory.dao.PortfolioDao;
import com.investory.model.HoldingSnapshot;
import com.investory.model.Portfolio;
import com.investory.service.HoldingService;
import com.investory.service.PortfolioAnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class DashboardController {

    @Autowired private PortfolioDao portfolioDao;
    @Autowired private HoldingService holdingService;
    @Autowired private PortfolioAnalysisService analysisService;

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) Long pid,
                            HttpServletRequest req, Model model) {
        HttpSession session = req.getSession();
        Long userId = (Long) session.getAttribute("userId");

        List<Portfolio> portfolios = portfolioDao.findByUser(userId);
        if (portfolios.isEmpty()) return "redirect:/portfolio";

        Long portfolioId = (Long) session.getAttribute("portfolioId");
        if (pid != null) {
            portfolioId = pid;
            session.setAttribute("portfolioId", portfolioId);
        }
        if (portfolioId == null) {
            portfolioId = portfolios.get(0).getId();
            session.setAttribute("portfolioId", portfolioId);
        }

        Portfolio activePortfolio = portfolioDao.findById(portfolioId);
        List<HoldingSnapshot> snapshots = holdingService.getSnapshots(portfolioId);

        model.addAttribute("portfolios",       portfolios);
        model.addAttribute("activePortfolio",  activePortfolio);
        model.addAttribute("snapshots",        snapshots);
        model.addAttribute("totalMarketValue", analysisService.totalMarketValue(snapshots));
        model.addAttribute("totalInvested",    analysisService.totalInvested(snapshots));
        model.addAttribute("totalPnl",         analysisService.totalUnrealizedPnl(snapshots));
        model.addAttribute("totalReturnPct",   analysisService.overallReturnPct(snapshots));
        return "dashboard";
    }
}
