package com.investory.servlet;

import com.investory.model.HoldingSnapshot;
import com.investory.service.HoldingService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.thymeleaf.context.WebContext;

import java.io.IOException;
import java.util.List;

@WebServlet("/holdings")
public class HoldingsServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long portfolioId = getSessionPortfolioId(req);
        if (portfolioId == null) {
            resp.sendRedirect(req.getContextPath() + "/portfolio");
            return;
        }
        try {
            List<HoldingSnapshot> snapshots = HoldingService.get().getSnapshots(portfolioId);
            WebContext ctx = newCtx(req, resp);
            ctx.setVariable("snapshots", snapshots);
            render("holdings", ctx, resp);
        } catch (Exception e) {
            resp.sendError(500, e.getMessage());
        }
    }
}
