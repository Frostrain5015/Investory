package com.investory.controller;

import com.investory.dao.DividendDao;
import com.investory.dao.HoldingDao;
import com.investory.model.Dividend;
import com.investory.model.Holding;
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

@Controller
public class DividendController {

    @Autowired private DividendDao dividendDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private HoldingService holdingService;

    @GetMapping("/dividends")
    public String dividendsGet(@RequestParam(required = false) String view,
                               HttpServletRequest req, Model model) {
        Long portfolioId = (Long) req.getSession().getAttribute("portfolioId");
        if (portfolioId == null) return "redirect:/portfolio";
        if ("add".equals(view)) {
            model.addAttribute("snapshots", holdingService.getSnapshots(portfolioId));
            return "add-dividend";
        }
        model.addAttribute("dividends", dividendDao.findByPortfolio(portfolioId));
        return "dividends";
    }

    @PostMapping("/dividends")
    public String dividendsPost(@RequestParam(required = false) String action,
                                @RequestParam(required = false) Long id,
                                @RequestParam(required = false) Long stockId,
                                @RequestParam(required = false) BigDecimal amountPerShare,
                                @RequestParam(required = false) String recordDate,
                                HttpServletRequest req) {
        Long portfolioId = (Long) req.getSession().getAttribute("portfolioId");
        if (portfolioId == null) return "redirect:/portfolio";

        if ("delete".equals(action) && id != null) {
            final long divId = id;
            dividendDao.findByPortfolio(portfolioId).stream()
                .filter(d -> d.getId() == divId).findFirst().ifPresent(d -> {
                    dividendDao.delete(divId);
                    holdingService.rebuildHolding(portfolioId, d.getStockId());
                });
        } else if (stockId != null && amountPerShare != null && recordDate != null) {
            Holding h = holdingDao.findByPortfolioAndStock(portfolioId, stockId);
            BigDecimal sharesHeld = h != null ? h.getTotalShares() : BigDecimal.ONE;
            BigDecimal total = amountPerShare.multiply(sharesHeld);

            Dividend d = new Dividend();
            d.setPortfolioId(portfolioId);
            d.setStockId(stockId);
            d.setAmountPerShare(amountPerShare);
            d.setSharesHeld(sharesHeld);
            d.setTotalAmount(total);
            d.setRecordDate(LocalDate.parse(recordDate));
            dividendDao.insert(d);
            holdingService.rebuildHolding(portfolioId, stockId);
        }
        return "redirect:/dividends";
    }
}
