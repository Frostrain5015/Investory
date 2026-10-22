package com.investory.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** The application uses its signed-in browser/desktop session only. */
@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") instanceof Number id && id.longValue() > 0) return true;
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
        } else response.sendRedirect(request.getContextPath() + "/");
        return false;
    }
}
