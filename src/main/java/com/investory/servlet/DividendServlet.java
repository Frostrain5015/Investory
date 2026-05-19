package com.investory.servlet;

import com.investory.dao.DividendDao;
import com.investory.dao.HoldingDao;
import com.investory.model.Dividend;
import com.investory.model.Holding;
import com.investory.service.HoldingService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.thymeleaf.context.WebContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

@WebServlet("/dividends")
public class DividendServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long portfolioId = getSessionPortfolioId(req);
        if (portfolioId == null) { resp.sendRedirect(req.getContextPath() + "/portfolio"); return; }
        try {
            String view = req.getParameter("view");
            WebContext ctx = newCtx(req, resp);
            if ("add".equals(view)) {
                // Pass snapshots (have stockName + totalShares) to avoid Spring bean notation in template
                ctx.setVariable("snapshots", HoldingService.get().getSnapshots(portfolioId));
                render("add-dividend", ctx, resp);
            } else {
                ctx.setVariable("dividends", DividendDao.get().findByPortfolio(portfolioId));
                render("dividends", ctx, resp);
            }
        } catch (Exception e) {
            resp.sendError(500, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        Long portfolioId = getSessionPortfolioId(req);
        if (portfolioId == null) { resp.sendRedirect(req.getContextPath() + "/portfolio"); return; }
        try {
            String action = req.getParameter("action");
            if ("delete".equals(action)) {
                long divId = Long.parseLong(req.getParameter("id"));
                // Find stock_id before delete for rebuild
                var divList = DividendDao.get().findByPortfolio(portfolioId);
                divList.stream().filter(d -> d.getId() == divId).findFirst().ifPresent(d -> {
                    try {
                        DividendDao.get().delete(divId);
                        HoldingService.get().rebuildHolding(portfolioId, d.getStockId());
                    } catch (Exception ignored) {}
                });
            } else {
                long stockId = Long.parseLong(req.getParameter("stockId"));
                BigDecimal amountPerShare = new BigDecimal(req.getParameter("amountPerShare"));
                Holding h = HoldingDao.get().findByPortfolioAndStock(portfolioId, stockId);
                BigDecimal sharesHeld = h != null ? h.getTotalShares() : BigDecimal.ONE;
                BigDecimal total = amountPerShare.multiply(sharesHeld);
                LocalDate date = LocalDate.parse(req.getParameter("recordDate"));

                Dividend d = new Dividend();
                d.setPortfolioId(portfolioId);
                d.setStockId(stockId);
                d.setAmountPerShare(amountPerShare);
                d.setSharesHeld(sharesHeld);
                d.setTotalAmount(total);
                d.setRecordDate(date);
                DividendDao.get().insert(d);
                HoldingService.get().rebuildHolding(portfolioId, stockId);
            }
            resp.sendRedirect(req.getContextPath() + "/dividends");
        } catch (Exception e) {
            resp.sendError(500, e.getMessage());
        }
    }
}
