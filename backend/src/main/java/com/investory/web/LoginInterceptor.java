package com.investory.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("userId") != null) return true;

        // API requests: return 401 JSON instead of redirect
        if (req.getRequestURI().startsWith(req.getContextPath() + "/api/")) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"unauthorized\"}");
            return false;
        }
        resp.sendRedirect(req.getContextPath() + "/");
        return false;
    }
}
