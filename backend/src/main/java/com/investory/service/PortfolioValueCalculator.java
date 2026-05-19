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

    public void backfillFrom(long portfolioId, LocalDate fromDate) {
        backfillFrom(portfolioId, fromDate, 0, null, null);
    }

    /**
     * Calculate daily portfolio values. Falls back to tradePrice on the trade date
     * if no market price is available (e.g. when EastMoney API is unreachable).
     */
    public void backfillFrom(long portfolioId, LocalDate fromDate,
                              long tradedStockId, BigDecimal tradePrice, BigDecimal tradeShares) {
        LocalDate toDate = LocalDate.now();
        if (fromDate.isAfter(toDate)) return;

        List<Holding> holdings = holdingDao.findByPortfolio(portfolioId);
        if (holdings.isEmpty()) return;

        BigDecimal prevValue = null;
        LocalDate cursor = fromDate;
        while (!cursor.isAfter(toDate)) {
            BigDecimal totalValue = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;
            boolean hasPrice = false;

            for (Holding h : holdings) {
                BigDecimal close = null;
                List<StockPrice> prices = stockPriceDao.findRange(h.getStockId(), cursor, cursor);
                if (!prices.isEmpty()) close = prices.get(0).getClose();
                // Fallback: use trade price on the trade date if no market data
                if (close == null && tradePrice != null && cursor.equals(fromDate) && h.getStockId() == tradedStockId) {
                    close = tradePrice;
                }
                if (close != null) {
                    totalValue = totalValue.add(close.multiply(h.getTotalShares()));
                    totalCost = totalCost.add(h.getTotalInvested());
                    hasPrice = true;
                }
            }

            if (hasPrice) {
                BigDecimal dailyPnl;
                if (prevValue != null) {
                    dailyPnl = totalValue.subtract(prevValue);
                } else {
                    dailyPnl = totalValue.subtract(totalCost);
                }
                prevValue = totalValue;

                DailyValue dv = new DailyValue();
                dv.setPortfolioId(portfolioId);
                dv.setSnapshotDate(cursor);
                dv.setTotalValue(totalValue);
                dv.setTotalCost(totalCost);
                dv.setDailyPnl(dailyPnl);
                dailyDao.upsert(dv);
            }

            cursor = cursor.plusDays(1);
        }
        log.info("Backfilled daily values for portfolio " + portfolioId + " from " + fromDate + " to " + toDate);
    }
}
