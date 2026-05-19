package com.investory.servlet;

import com.investory.util.AppConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;

public abstract class BaseServlet extends HttpServlet {

    protected void render(String template, WebContext ctx, HttpServletResponse response) throws IOException {
        TemplateEngine engine = (TemplateEngine) getServletContext().getAttribute(AppConfig.TEMPLATE_ENGINE);
        response.setContentType("text/html;charset=UTF-8");
        engine.process(template, ctx, response.getWriter());
    }

    protected WebContext newCtx(HttpServletRequest req, HttpServletResponse resp) {
        JakartaServletWebApplication app =
                (JakartaServletWebApplication) getServletContext().getAttribute(AppConfig.THYMELEAF_APP);
        IWebExchange exchange = app.buildExchange(req, resp);
        WebContext ctx = new WebContext(exchange, req.getLocale());
        // Expose session user info to all templates
        HttpSession session = req.getSession(false);
        if (session != null) {
            ctx.setVariable("sessionUserId",   session.getAttribute("userId"));
            ctx.setVariable("sessionUsername", session.getAttribute("username"));
            Long pid = (Long) session.getAttribute("portfolioId");
            ctx.setVariable("sessionPortfolioId", pid);
        }
        ctx.setVariable("contextPath", req.getContextPath());
        return ctx;
    }

    protected Long getSessionUserId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null ? (Long) session.getAttribute("userId") : null;
    }

    protected Long getSessionPortfolioId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null ? (Long) session.getAttribute("portfolioId") : null;
    }

    protected void setSessionPortfolio(HttpServletRequest req, Long portfolioId) {
        req.getSession(true).setAttribute("portfolioId", portfolioId);
    }
}
