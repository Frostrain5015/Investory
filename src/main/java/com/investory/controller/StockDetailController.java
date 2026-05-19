package com.investory.controller;

import com.investory.dao.DividendDao;
import com.investory.dao.HoldingDao;
import com.investory.dao.StockDao;
import com.investory.dao.TransactionDao;
import com.investory.model.Dividend;
import com.investory.model.Holding;
import com.investory.model.Stock;
import com.investory.model.Transaction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class StockDetailController {

    @Autowired private StockDao stockDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private TransactionDao transactionDao;
    @Autowired private DividendDao dividendDao;

    @GetMapping("/stock")
    public String stockDetail(@RequestParam(required = false) String symbol,
                              HttpServletRequest req, Model model) {
        Long portfolioId = (Long) req.getSession().getAttribute("portfolioId");
        if (symbol == null || portfolioId == null) return "redirect:/dashboard";

        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return "redirect:/dashboard";

        Holding holding = holdingDao.findByPortfolioAndStock(portfolioId, stock.getId());
        List<Transaction> transactions = transactionDao.findByPortfolioAndStock(portfolioId, stock.getId());
        List<Dividend> dividends = dividendDao.findByPortfolioAndStock(portfolioId, stock.getId());

        model.addAttribute("stock",        stock);
        model.addAttribute("holding",      holding);
        model.addAttribute("transactions", transactions);
        model.addAttribute("dividends",    dividends);
        return "stock-detail";
    }
}
