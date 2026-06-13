package com.investory.server;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;

public class AuthFilter implements Filter {
    private static final String[] EXCLUDED = {"/login", "/register", "/logout", "/error",
        "/api/session", "/api/stock/search", "/api/market/indices", "/api/market/exchange-rates",
        "/api/market/news", "/api/oauth/"};

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        HttpServletResponse resp = (HttpServletResponse) res;
        String uri = r.getRequestURI();
        String ctx = r.getContextPath();
        String path = uri.substring(ctx.length());

        // Always allow static assets
        if (path.startsWith("/assets/") || path.endsWith(".js") || path.endsWith(".css")
                || path.endsWith(".json") || path.endsWith(".svg") || path.endsWith(".ico")) {
            chain.doFilter(req, res);
            return;
        }
        // Check excluded paths
        for (String e : EXCLUDED) {
            if (path.equals(e) || path.startsWith(e)) {
                chain.doFilter(req, res);
                return;
            }
        }

        HttpSession session = r.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            chain.doFilter(req, res);
            return;
        }

        // API -> 401 JSON, SPA routes -> redirect
        if (path.startsWith("/api/")) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"unauthorized\"}");
        } else {
            // Admin login page, SPA routes etc. - let through for frontend routing
            chain.doFilter(req, res);
        }
    }
}
