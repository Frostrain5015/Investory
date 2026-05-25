package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.dao.DividendDao;
import com.investory.dao.HoldingDao;
import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.DailyValue;
import com.investory.model.Dividend;
import com.investory.model.Holding;
import com.investory.model.Stock;
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
    @Autowired private StockDao stockDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private DailyPortfolioValueDao dailyDao;
    @Autowired private JdbcTemplate jdbc;

    private Map<String, BigDecimal> loadCnyRates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("CNY", BigDecimal.ONE);
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT currency, rate FROM exchange_rates");
            for (Map<String, Object> row : rows) {
                String curr = (String) row.get("currency");
                BigDecimal rate = (BigDecimal) row.get("rate");
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                    rates.put(curr, BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception ignored) {}
        return rates;
    }

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
        // Exchange rates & stock currencies for CNY conversion
        Map<String, BigDecimal> toCny = loadCnyRates();
        Map<Long, BigDecimal> stockCnyRate = new HashMap<>();
        for (Holding h : holdings) {
            Stock s = stockDao.findById(h.getStockId());
            stockCnyRate.put(h.getStockId(), toCny.getOrDefault(s != null ? s.getCurrency() : "CNY", BigDecimal.ONE));
        }

        // Track last known price per stock for non-trading days
        Map<Long, BigDecimal> lastPriceByStock = new HashMap<>();
        // Pre-load dividends by record date — avoid N+1 and convert to CNY
        Map<LocalDate, BigDecimal> divByRecordDate = new HashMap<>();
        for (Dividend d : dividendDao.findByPortfolio(portfolioId)) {
            Stock ds = stockDao.findById(d.getStockId());
            BigDecimal divRate = toCny.getOrDefault(ds != null ? ds.getCurrency() : "CNY", BigDecimal.ONE);
            divByRecordDate.merge(d.getRecordDate(), d.getTotalAmount().multiply(divRate), BigDecimal::add);
        }
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
                if (close == null) close = lastPriceByStock.get(h.getStockId());
                if (close != null) {
                    lastPriceByStock.put(h.getStockId(), close);
                    BigDecimal rate = stockCnyRate.getOrDefault(h.getStockId(), BigDecimal.ONE);
                    stockValue = stockValue.add(close.multiply(h.getTotalShares()).multiply(rate));
                    totalCost = totalCost.add(h.getTotalInvested().multiply(rate));
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

                BigDecimal divIncome = divByRecordDate.getOrDefault(cursor, BigDecimal.ZERO);
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
        // Load exchange rates to convert all cash to CNY
        Map<String, BigDecimal> toCny = new HashMap<>();
        toCny.put("CNY", BigDecimal.ONE);
        try {
            List<Map<String, Object>> rates = jdbc.queryForList("SELECT currency, rate FROM exchange_rates");
            for (Map<String, Object> r : rates) {
                String curr = (String) r.get("currency");
                BigDecimal rate = (BigDecimal) r.get("rate");
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                    toCny.put(curr, BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception ignored) {}

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
            String cur = (String) t.getOrDefault("currency", "CNY");
            BigDecimal rate = toCny.getOrDefault(cur, BigDecimal.ONE);

            switch (type) {
                case "SELL": cumulativeCash = cumulativeCash.add(shares.multiply(price).subtract(fee).multiply(rate)); break;
                case "BUY":  cumulativeCash = cumulativeCash.subtract(shares.multiply(price).add(fee).multiply(rate)); break;
                case "TRANSFER_IN":  cumulativeCash = cumulativeCash.add(shares.multiply(rate)); break;
                case "TRANSFER_OUT": cumulativeCash = cumulativeCash.subtract(shares.multiply(rate)); break;
            }
            cashByDate.put(d, cumulativeCash);
        }

        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        BigDecimal running = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (cashByDate.floorEntry(d) != null) running = cashByDate.floorEntry(d).getValue();
            result.put(d, running);
        }
        return result;
    }
}
