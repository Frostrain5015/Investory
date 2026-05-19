package com.investory.controller;

import com.investory.dao.PortfolioDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import com.investory.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
public class SpaController {

    @Autowired private AuthService authService;
    @Autowired private PortfolioDao portfolioDao;

    // Serve the React SPA for all non-API GET routes
    @GetMapping(value = {"/", "/login", "/register", "/dashboard", "/portfolio",
        "/holdings", "/transactions", "/transactions/**", "/dividends", "/dividends/**",
        "/stock", "/pnl-calendar"})
    @ResponseBody
    public String serveSpa() throws IOException {
        Resource resource = new ClassPathResource("static/index.html");
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping("/login")
    @ResponseBody
    public String loginPost(@RequestParam String username, @RequestParam String password,
                            HttpServletRequest req) {
        User user = authService.login(username, password);
        if (user == null) return "error";
        HttpSession session = req.getSession(true);
        session.setAttribute("userId",   user.getId());
        session.setAttribute("username", user.getUsername());
        List<Portfolio> portfolios = portfolioDao.findByUser(user.getId());
        if (!portfolios.isEmpty()) session.setAttribute("portfolioId", portfolios.get(0).getId());
        return "ok";
    }

    @PostMapping("/register")
    @ResponseBody
    public String registerPost(@RequestParam String username, @RequestParam String password,
                               @RequestParam(required = false) String email) {
        String error = authService.register(username, password, email);
        return error != null ? error : "ok";
    }

    @GetMapping("/logout")
    public String logoutGet(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        return "redirect:/login";
    }
}
