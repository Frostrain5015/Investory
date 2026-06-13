package com.investory.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 单页应用（SPA）入口控制器。
 *
 * <p>本控制器仅负责 SPA 路由托管，将所有前端路由指向 {@code index.html}，
 * 让 React Router 在客户端接管路由解析。认证全部通过 Frost ID OAuth 2.1 完成。</p>
 */
public class SpaController {

    /**
     * 为所有前端路由路径提供 React SPA 入口页面。
     */
    public void handleServeSpa(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream("static/index.html");
        if (is == null) {
            resp.sendError(404, "index.html not found");
            return;
        }
        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        is.close();
        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter().write(content);
    }

    /**
     * 处理用户登出请求。
     */
    public void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        resp.sendRedirect(req.getContextPath() + "/");
    }
}
