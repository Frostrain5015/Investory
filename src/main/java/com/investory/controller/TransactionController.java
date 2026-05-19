package com.investory.controller;

import com.investory.crawler.EastMoneyCrawler;
import com.investory.dao.StockDao;
import com.investory.dao.TransactionDao;
import com.investory.model.Stock;
import com.investory.model.Transaction;
import com.investory.service.HoldingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Controller
public class TransactionController {

    @Autowired private StockDao stockDao;
    @Autowired private TransactionDao transactionDao;
    @Autowired private HoldingService holdingService;
    @Autowired private EastMoneyCrawler crawler;

    @GetMapping("/transactions")
    public String transactionsGet(@RequestParam(required = false) String view,
                                  HttpServletRequest req, Model model) {
        Long portfolioId = (Long) req.getSession().getAttribute("portfolioId");
        if (portfolioId == null) return "redirect:/portfolio";
        if ("add".equals(view)) {
            model.addAttribute("stocks", stockDao.findAll());
            return "add-transaction";
        }
        model.addAttribute("transactions", transactionDao.findByPortfolio(portfolioId));
        return "transactions";
    }

    @PostMapping("/transactions")
    public String transactionsPost(@RequestParam(required = false) String action,
                                   @RequestParam(required = false) Long id,
                                   @RequestParam(required = false) Long stockId,
                                   @RequestParam(required = false) String type,
                                   @RequestParam(required = false) BigDecimal shares,
                                   @RequestParam(required = false) BigDecimal price,
                                   @RequestParam(required = false) String fee,
                                   @RequestParam(required = false) String tradeDate,
                                   @RequestParam(required = false) String note,
                                   HttpServletRequest req) {
        Long portfolioId = (Long) req.getSession().getAttribute("portfolioId");
        if (portfolioId == null) return "redirect:/portfolio";

        if ("delete".equals(action) && id != null) {
            final long txnId = id;
            List<Transaction> txns = transactionDao.findByPortfolio(portfolioId);
            txns.stream().filter(t -> t.getId() == txnId).findFirst().ifPresent(old -> {
                transactionDao.delete(txnId);
                holdingService.rebuildHolding(portfolioId, old.getStockId());
            });
        } else if (stockId != null && type != null && shares != null && price != null && tradeDate != null) {
            Stock stock = stockDao.findById(stockId);
            if (stock != null) {
                BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;
                Transaction t = new Transaction();
                t.setPortfolioId(portfolioId);
                t.setStockId(stockId);
                t.setType(type);
                t.setShares(shares);
                t.setPrice(price);
                t.setFee(feeVal);
                t.setTradeDate(LocalDate.parse(tradeDate));
                t.setNote(note);
                transactionDao.insert(t);
                holdingService.rebuildHolding(portfolioId, stockId);
                new Thread(() -> crawler.fetchHistory(stock)).start();
            }
        }
        return "redirect:/transactions";
    }
}
