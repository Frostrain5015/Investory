package com.investory.controller;

import com.investory.dao.PortfolioDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import com.investory.service.AuthService;
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
public class LoginController {

    @Autowired private AuthService authService;
    @Autowired private PortfolioDao portfolioDao;

    @GetMapping("/login")
    public String loginGet(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("userId") != null) return "redirect:/dashboard";
        return "login";
    }

    @PostMapping("/login")
    public String loginPost(@RequestParam String username,
                            @RequestParam String password,
                            HttpServletRequest req, Model model) {
        User user = authService.login(username, password);
        if (user == null) {
            model.addAttribute("error", "用户名或密码错误");
            return "login";
        }
        HttpSession session = req.getSession(true);
        session.setAttribute("userId",   user.getId());
        session.setAttribute("username", user.getUsername());

        List<Portfolio> portfolios = portfolioDao.findByUser(user.getId());
        if (!portfolios.isEmpty()) {
            session.setAttribute("portfolioId", portfolios.get(0).getId());
        }
        return "redirect:/dashboard";
    }
}
