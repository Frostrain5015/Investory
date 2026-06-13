package com.investory.server;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class CorsFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse resp = (HttpServletResponse) res;
        HttpServletRequest reqq = (HttpServletRequest) req;
        resp.setHeader("Access-Control-Allow-Origin", reqq.getHeader("Origin"));
        resp.setHeader("Access-Control-Allow-Methods", "*");
        resp.setHeader("Access-Control-Allow-Headers", "*");
        resp.setHeader("Access-Control-Allow-Credentials", "true");
        if ("OPTIONS".equalsIgnoreCase(reqq.getMethod())) {
            resp.setStatus(200);
            return;
        }
        chain.doFilter(req, res);
    }
}
