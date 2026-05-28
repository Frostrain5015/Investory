package com.investory.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 单页应用（SPA）入口控制器。
 *
 * <p>本控制器仅负责 SPA 路由托管，将所有前端路由指向 {@code index.html}，
 * 让 React Router 在客户端接管路由解析。认证全部通过 Frost ID OAuth 2.1 完成。</p>
 */
@Controller
public class SpaController {

    /**
     * 为所有前端路由路径提供 React SPA 入口页面。
     */
    @GetMapping(value = {"/", "/login", "/register", "/market", "/watchlist", "/dashboard", "/portfolio",
        "/holdings", "/transactions", "/transactions/**", "/dividends", "/dividends/**",
        "/stock", "/pnl-calendar", "/admin"})
    @ResponseBody
    public String serveSpa() throws IOException {
        Resource resource = new ClassPathResource("static/index.html");
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 处理用户登出请求。
     */
    @GetMapping("/logout")
    public String logoutGet(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        return "redirect:/";
    }
}
