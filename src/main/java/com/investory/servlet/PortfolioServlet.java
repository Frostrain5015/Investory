package com.investory.servlet;

import com.investory.dao.PortfolioDao;
import com.investory.model.Portfolio;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.thymeleaf.context.WebContext;

import java.io.IOException;
import java.util.List;

@WebServlet("/portfolio")
public class PortfolioServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long userId = getSessionUserId(req);
        try {
            List<Portfolio> portfolios = PortfolioDao.get().findByUser(userId);
            WebContext ctx = newCtx(req, resp);
            ctx.setVariable("portfolios", portfolios);
            render("portfolio", ctx, resp);
        } catch (Exception e) {
            resp.sendError(500, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        Long userId = getSessionUserId(req);
        String action = req.getParameter("action");
        try {
            if ("create".equals(action)) {
                String name = req.getParameter("name");
                if (name != null && !name.isBlank()) {
                    Portfolio p = new Portfolio();
                    p.setUserId(userId);
                    p.setName(name.trim());
                    long newId = PortfolioDao.get().insert(p);
                    setSessionPortfolio(req, newId);
                }
            } else if ("delete".equals(action)) {
                long id = Long.parseLong(req.getParameter("id"));
                PortfolioDao.get().delete(id);
                // Reset session portfolio
                List<Portfolio> remaining = PortfolioDao.get().findByUser(userId);
                setSessionPortfolio(req, remaining.isEmpty() ? null : remaining.get(0).getId());
            }
            resp.sendRedirect(req.getContextPath() + "/portfolio");
        } catch (Exception e) {
            resp.sendError(500, e.getMessage());
        }
    }
}
