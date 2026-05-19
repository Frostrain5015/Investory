package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.dao.HoldingDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.DailyValue;
import com.investory.model.Holding;
import com.investory.model.StockPrice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Logger;

@Service
public class PortfolioValueCalculator {

    private static final Logger log = Logger.getLogger(PortfolioValueCalculator.class.getName());

    @Autowired private HoldingDao holdingDao;
    @Autowired private StockPriceDao stockPriceDao;
    @Autowired private DailyPortfolioValueDao dailyDao;

    /**
     * Calculate and persist daily portfolio values from the given start date to today.
     * Called after a new transaction/dividend is added with a past trade date.
     */
    public void backfillFrom(long portfolioId, LocalDate fromDate) {
        LocalDate toDate = LocalDate.now();
        if (fromDate.isAfter(toDate)) return;

        List<Holding> holdings = holdingDao.findByPortfolio(portfolioId);
        if (holdings.isEmpty()) return;

        LocalDate cursor = fromDate;
        while (!cursor.isAfter(toDate)) {
            BigDecimal totalValue = BigDecimal.ZERO;
            boolean hasPrice = false;

            for (Holding h : holdings) {
                List<StockPrice> prices = stockPriceDao.findRange(h.getStockId(), cursor, cursor);
                if (!prices.isEmpty()) {
                    BigDecimal close = prices.get(0).getClose();
                    if (close != null) {
                        totalValue = totalValue.add(close.multiply(h.getTotalShares()));
                        hasPrice = true;
                    }
                }
            }

            if (hasPrice) {
                // Calculate total cost for this day
                BigDecimal totalCost = holdings.stream()
                        .map(Holding::getTotalInvested)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                DailyValue dv = new DailyValue();
                dv.setPortfolioId(portfolioId);
                dv.setSnapshotDate(cursor);
                dv.setTotalValue(totalValue);
                dv.setTotalCost(totalCost);
                dv.setDailyPnl(totalValue.subtract(totalCost));
                dailyDao.upsert(dv);
            }

            cursor = cursor.plusDays(1);
        }
        log.info("Backfilled daily values for portfolio " + portfolioId + " from " + fromDate + " to " + toDate);
    }
}
