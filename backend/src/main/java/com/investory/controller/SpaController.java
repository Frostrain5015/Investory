package com.investory.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 单页应用（SPA）入口控制器。
 */
public class SpaController {

    /**
     * Serve the SPA index.html for frontend routes.
     */
    public void handleServeSpa(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("static/index.html");
        if (is == null) {
            resp.setStatus(404);
            resp.getWriter().write("index.html not found");
            return;
        }
        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter().write(content);
    }

    /**
     * Handle logout - invalidate session and redirect to home.
     */
    public void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        resp.sendRedirect("/");
    }
}
