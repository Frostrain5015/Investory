package com.investory.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.thymeleaf.context.WebContext;

import java.io.IOException;
import java.time.Year;

@WebServlet("/pnl-calendar")
public class PnlCalendarServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long portfolioId = getSessionPortfolioId(req);
        if (portfolioId == null) { resp.sendRedirect(req.getContextPath() + "/portfolio"); return; }
        String yearParam = req.getParameter("year");
        int year = yearParam != null ? Integer.parseInt(yearParam) : Year.now().getValue();
        WebContext ctx = newCtx(req, resp);
        ctx.setVariable("year", year);
        ctx.setVariable("prevYear", year - 1);
        ctx.setVariable("nextYear", year + 1);
        render("pnl-calendar", ctx, resp);
    }
}
