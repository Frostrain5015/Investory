package com.investory.servlet;

import com.investory.dao.*;
import com.investory.model.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.thymeleaf.context.WebContext;

import java.io.IOException;
import java.util.List;

@WebServlet("/stock")
public class StockDetailServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String symbol = req.getParameter("symbol");
        Long portfolioId = getSessionPortfolioId(req);
        if (symbol == null || portfolioId == null) {
            resp.sendRedirect(req.getContextPath() + "/dashboard");
            return;
        }
        try {
            Stock stock = StockDao.get().findBySymbol(symbol);
            if (stock == null) { resp.sendError(404, "Stock not found"); return; }

            Holding holding = HoldingDao.get().findByPortfolioAndStock(portfolioId, stock.getId());
            List<Transaction> transactions = TransactionDao.get()
                    .findByPortfolioAndStock(portfolioId, stock.getId());
            List<Dividend> dividends = DividendDao.get()
                    .findByPortfolioAndStock(portfolioId, stock.getId());

            WebContext ctx = newCtx(req, resp);
            ctx.setVariable("stock",        stock);
            ctx.setVariable("holding",      holding);
            ctx.setVariable("transactions", transactions);
            ctx.setVariable("dividends",    dividends);
            render("stock-detail", ctx, resp);
        } catch (Exception e) {
            resp.sendError(500, e.getMessage());
        }
    }
}
