package com.investory.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public final class SessionHelper {

    private SessionHelper() {}

    public static long getUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object uid = s.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    public static long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    public static boolean isAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && Boolean.TRUE.equals(s.getAttribute("isAdmin"));
    }

    public static boolean requireAuth(long userId, HttpServletResponse resp) {
        if (userId == 0) {
            try {
                resp.setStatus(401);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"error\":\"unauthorized\"}");
            } catch (Exception ignored) {}
            return false;
        }
        return true;
    }
}
