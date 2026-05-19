package com.investory.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAdvice {

    @ModelAttribute
    public void addGlobalAttributes(HttpServletRequest req, Model model) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            model.addAttribute("sessionUserId",       session.getAttribute("userId"));
            model.addAttribute("sessionUsername",     session.getAttribute("username"));
            model.addAttribute("sessionPortfolioId",  session.getAttribute("portfolioId"));
        }
        model.addAttribute("contextPath", req.getContextPath());
    }
}
