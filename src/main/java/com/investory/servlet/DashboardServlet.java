package com.investory.servlet;

import com.investory.dao.PortfolioDao;
import com.investory.model.HoldingSnapshot;
import com.investory.model.Portfolio;
import com.investory.service.HoldingService;
import com.investory.service.PortfolioAnalysisService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.thymeleaf.context.WebContext;

import java.io.IOException;
import java.util.List;

@WebServlet("/dashboard")
public class DashboardServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long userId = getSessionUserId(req);
        try {
            List<Portfolio> portfolios = PortfolioDao.get().findByUser(userId);
            if (portfolios.isEmpty()) {
                resp.sendRedirect(req.getContextPath() + "/portfolio");
                return;
            }

            // Determine active portfolio (URL param overrides session)
            Long portfolioId = getSessionPortfolioId(req);
            String pidParam = req.getParameter("pid");
            if (pidParam != null) {
                portfolioId = Long.parseLong(pidParam);
                setSessionPortfolio(req, portfolioId);
            }
            if (portfolioId == null) {
                portfolioId = portfolios.get(0).getId();
                setSessionPortfolio(req, portfolioId);
            }

            Portfolio activePortfolio = PortfolioDao.get().findById(portfolioId);
            List<HoldingSnapshot> snapshots = HoldingService.get().getSnapshots(portfolioId);

            WebContext ctx = newCtx(req, resp);
            ctx.setVariable("portfolios",       portfolios);
            ctx.setVariable("activePortfolio",  activePortfolio);
            ctx.setVariable("snapshots",        snapshots);
            ctx.setVariable("totalMarketValue", PortfolioAnalysisService.get().totalMarketValue(snapshots));
            ctx.setVariable("totalInvested",    PortfolioAnalysisService.get().totalInvested(snapshots));
            ctx.setVariable("totalPnl",         PortfolioAnalysisService.get().totalUnrealizedPnl(snapshots));
            ctx.setVariable("totalReturnPct",   PortfolioAnalysisService.get().overallReturnPct(snapshots));

            render("dashboard", ctx, resp);
        } catch (Exception e) {
            resp.sendError(500, e.getMessage());
        }
    }
}
