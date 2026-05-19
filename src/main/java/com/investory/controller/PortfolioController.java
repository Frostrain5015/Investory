package com.investory.controller;

import com.investory.dao.PortfolioDao;
import com.investory.model.Portfolio;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class PortfolioController {

    @Autowired private PortfolioDao portfolioDao;

    @GetMapping("/portfolio")
    public String portfolioGet(HttpServletRequest req, Model model) {
        Long userId = (Long) req.getSession().getAttribute("userId");
        model.addAttribute("portfolios", portfolioDao.findByUser(userId));
        return "portfolio";
    }

    @PostMapping("/portfolio")
    public String portfolioPost(@RequestParam String action,
                                @RequestParam(required = false) String name,
                                @RequestParam(required = false) Long id,
                                HttpServletRequest req) {
        HttpSession session = req.getSession();
        Long userId = (Long) session.getAttribute("userId");

        if ("create".equals(action) && name != null && !name.isBlank()) {
            Portfolio p = new Portfolio();
            p.setUserId(userId);
            p.setName(name.trim());
            long newId = portfolioDao.insert(p);
            session.setAttribute("portfolioId", newId);
        } else if ("delete".equals(action) && id != null) {
            portfolioDao.delete(id);
            List<Portfolio> remaining = portfolioDao.findByUser(userId);
            session.setAttribute("portfolioId", remaining.isEmpty() ? null : remaining.get(0).getId());
        }
        return "redirect:/portfolio";
    }
}
