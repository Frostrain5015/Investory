package com.investory.server;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Logger;

public class ErrorFilter implements Filter {
    private static final Logger log = Logger.getLogger(ErrorFilter.class.getName());

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(req, res);
        } catch (Exception e) {
            HttpServletResponse resp = (HttpServletResponse) res;
            if (resp.isCommitted()) return;
            String name = e.getClass().getSimpleName();
            log.warning("Unhandled: " + name + " - " + e.getMessage());
            resp.setStatus(500);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"服务器内部错误\"}");
        }
    }
}
