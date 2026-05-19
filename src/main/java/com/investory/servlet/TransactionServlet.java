package com.investory.servlet;

import com.investory.dao.StockDao;
import com.investory.dao.TransactionDao;
import com.investory.model.Stock;
import com.investory.model.Transaction;
import com.investory.service.HoldingService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.thymeleaf.context.WebContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@WebServlet("/transactions")
public class TransactionServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long portfolioId = getSessionPortfolioId(req);
        if (portfolioId == null) { resp.sendRedirect(req.getContextPath() + "/portfolio"); return; }
        try {
            // Show add form or list
            String view = req.getParameter("view");
            WebContext ctx = newCtx(req, resp);
            if ("add".equals(view)) {
                ctx.setVariable("stocks", StockDao.get().findAll());
                render("add-transaction", ctx, resp);
            } else {
                ctx.setVariable("transactions", TransactionDao.get().findByPortfolio(portfolioId));
                render("transactions", ctx, resp);
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
                long txnId = Long.parseLong(req.getParameter("id"));
                Transaction old = TransactionDao.get().findByPortfolio(portfolioId)
                        .stream().filter(t -> t.getId() == txnId).findFirst().orElse(null);
                TransactionDao.get().delete(txnId);
                if (old != null) HoldingService.get().rebuildHolding(portfolioId, old.getStockId());
            } else {
                // Create transaction
                long stockId   = Long.parseLong(req.getParameter("stockId"));
                String type    = req.getParameter("type");
                BigDecimal shares = new BigDecimal(req.getParameter("shares"));
                BigDecimal price  = new BigDecimal(req.getParameter("price"));
                String feeStr  = req.getParameter("fee");
                BigDecimal fee = (feeStr != null && !feeStr.isBlank()) ? new BigDecimal(feeStr) : BigDecimal.ZERO;
                LocalDate date = LocalDate.parse(req.getParameter("tradeDate"));
                String note    = req.getParameter("note");

                // Auto-add stock to portfolio holdings if needed
                Stock stock = StockDao.get().findById(stockId);
                if (stock != null) {
                    Transaction t = new Transaction();
                    t.setPortfolioId(portfolioId);
                    t.setStockId(stockId);
                    t.setType(type);
                    t.setShares(shares);
                    t.setPrice(price);
                    t.setFee(fee);
                    t.setTradeDate(date);
                    t.setNote(note);
                    TransactionDao.get().insert(t);
                    HoldingService.get().rebuildHolding(portfolioId, stockId);

                    // Trigger history fetch if first transaction for this stock
                    new Thread(() -> crawler.fetchHistory(stock)).start();
                }
            }
            resp.sendRedirect(req.getContextPath() + "/transactions");
        } catch (Exception e) {
            resp.sendError(500, e.getMessage());
        }
    }

    private static final com.investory.crawler.EastMoneyCrawler crawler =
            com.investory.crawler.EastMoneyCrawler.get();
}
