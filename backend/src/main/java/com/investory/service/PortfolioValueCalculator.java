package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.dao.DividendDao;
import com.investory.dao.HoldingDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.DailyValue;
import com.investory.model.Dividend;
import com.investory.model.Holding;
import com.investory.model.StockPrice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Logger;

@Service
public class PortfolioValueCalculator {

    private static final Logger log = java.util.logging.Logger.getLogger(PortfolioValueCalculator.class.getName());

    @Autowired private HoldingDao holdingDao;
    @Autowired private StockPriceDao stockPriceDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private DailyPortfolioValueDao dailyDao;
    @Autowired private JdbcTemplate jdbc;

    public void backfillFrom(long portfolioId, LocalDate fromDate) {
        backfillFrom(portfolioId, fromDate, 0, null, null);
    }

    public void backfillFrom(long portfolioId, LocalDate fromDate,
                              long tradedStockId, BigDecimal tradePrice, BigDecimal tradeShares) {
        LocalDate toDate = LocalDate.now();
        if (fromDate.isAfter(toDate)) return;

        List<Holding> holdings = holdingDao.findByPortfolio(portfolioId);
        if (holdings.isEmpty() && !hasCashRecords(portfolioId)) return;

        // Pre-compute daily cash balances
        Map<LocalDate, BigDecimal> cashByDate = computeDailyCash(portfolioId, fromDate, toDate);

        BigDecimal prevStockValue = null;
        BigDecimal prevTotalValue = null;
        // Track last known price per stock for non-trading days
        Map<Long, BigDecimal> lastPriceByStock = new HashMap<>();
        LocalDate cursor = fromDate;
        while (!cursor.isAfter(toDate)) {
            BigDecimal stockValue = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;
            boolean hasPrice = false;

            for (Holding h : holdings) {
                BigDecimal close = null;
                List<StockPrice> prices = stockPriceDao.findRange(h.getStockId(), cursor, cursor);
                if (!prices.isEmpty()) close = prices.get(0).getClose();
                if (close == null && tradePrice != null && cursor.equals(fromDate) && h.getStockId() == tradedStockId) {
                    close = tradePrice;
                }
                // Carry forward last known price on non-trading days
                if (close == null) close = lastPriceByStock.get(h.getStockId());
                if (close != null) {
                    lastPriceByStock.put(h.getStockId(), close);
                    stockValue = stockValue.add(close.multiply(h.getTotalShares()));
                    totalCost = totalCost.add(h.getTotalInvested());
                    hasPrice = true;
                }
            }

            BigDecimal cashOnDay = cashByDate.getOrDefault(cursor, BigDecimal.ZERO);
            BigDecimal totalValue = stockValue.add(cashOnDay);

            if (hasPrice || cashOnDay.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dailyPnl;
                if (prevTotalValue != null && prevStockValue != null) {
                    dailyPnl = stockValue.subtract(prevStockValue);
                } else {
                    dailyPnl = stockValue.subtract(totalCost);
                }
                prevStockValue = stockValue;
                prevTotalValue = totalValue;

                List<Dividend> divs = dividendDao.findByPortfolio(portfolioId);
                BigDecimal divIncome = BigDecimal.ZERO;
                for (Dividend d : divs) {
                    if (cursor.equals(d.getRecordDate())) divIncome = divIncome.add(d.getTotalAmount());
                }
                dailyPnl = dailyPnl.add(divIncome);

                DailyValue dv = new DailyValue();
                dv.setPortfolioId(portfolioId);
                dv.setSnapshotDate(cursor);
                dv.setTotalValue(totalValue.add(divIncome));
                dv.setTotalCost(totalCost);
                dv.setDailyPnl(dailyPnl);
                dailyDao.upsert(dv);
            }

            cursor = cursor.plusDays(1);
        }
        log.info("Backfilled daily values for portfolio " + portfolioId + " from " + fromDate + " to " + toDate);
    }

    private boolean hasCashRecords(long portfolioId) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM (SELECT 1 FROM cash_balances WHERE portfolio_id=? AND amount>0 UNION ALL SELECT 1 FROM transactions WHERE portfolio_id=? AND type IN ('TRANSFER_IN','TRANSFER_OUT') LIMIT 1) t",
            Long.class, portfolioId, portfolioId);
        return count != null && count > 0;
    }

    private Map<LocalDate, BigDecimal> computeDailyCash(long portfolioId, LocalDate from, LocalDate to) {
        // All cash-affecting transactions (including before 'from' to build starting position)
        List<Map<String, Object>> txns = jdbc.queryForList(
            "SELECT trade_date, type, shares, price, currency, fee FROM transactions WHERE portfolio_id=? AND trade_date <= ? ORDER BY trade_date",
            portfolioId, to);

        TreeMap<LocalDate, BigDecimal> cashByDate = new TreeMap<>();
        BigDecimal cumulativeCash = BigDecimal.ZERO;
        for (Map<String, Object> t : txns) {
            String type = (String) t.get("type");
            LocalDate d = ((java.sql.Date) t.get("trade_date")).toLocalDate();
            BigDecimal shares = t.get("shares") != null ? (BigDecimal) t.get("shares") : BigDecimal.ZERO;
            BigDecimal price = t.get("price") != null ? (BigDecimal) t.get("price") : BigDecimal.ZERO;
            BigDecimal fee = t.get("fee") != null ? (BigDecimal) t.get("fee") : BigDecimal.ZERO;

            switch (type) {
                case "SELL": cumulativeCash = cumulativeCash.add(shares.multiply(price).subtract(fee)); break;
                case "BUY":  cumulativeCash = cumulativeCash.subtract(shares.multiply(price).add(fee)); break;
                case "TRANSFER_IN":  cumulativeCash = cumulativeCash.add(shares); break;
                case "TRANSFER_OUT": cumulativeCash = cumulativeCash.subtract(shares); break;
                // DIV goes to stock-side P&L, not cash
            }
            cashByDate.put(d, cumulativeCash);
        }

        // Fill forward: carry last known cash to each day
        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        BigDecimal running = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (cashByDate.floorEntry(d) != null) running = cashByDate.floorEntry(d).getValue();
            result.put(d, running);
        }
        return result;
    }
}
