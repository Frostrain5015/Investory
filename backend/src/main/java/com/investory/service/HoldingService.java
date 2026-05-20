package com.investory.service;

import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class HoldingService {

    @Autowired private TransactionDao transactionDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;
    @Autowired private RealtimeQuoteService quoteService;
    @Autowired private CostCalculationService costCalcService;
    @Autowired private JdbcTemplate jdbc;

    public void rebuildHolding(long portfolioId, long stockId) {
        List<Transaction> txns = transactionDao.findByPortfolioAndStock(portfolioId, stockId);
        Holding h = costCalcService.rebuild(txns);
        h.setPortfolioId(portfolioId);
        h.setStockId(stockId);

        BigDecimal totalDiv = dividendDao.sumByPortfolioAndStock(portfolioId, stockId);
        costCalcService.applyDividends(h, totalDiv);

        holdingDao.upsert(h);
    }

    public List<HoldingSnapshot> getSnapshots(long portfolioId) {
        List<Holding> holdings = holdingDao.findByPortfolio(portfolioId);
        List<HoldingSnapshot> snapshots = new ArrayList<>();

        // Load exchange rates to CNY
        Map<String, BigDecimal> toCny = loadCnyRates();

        for (Holding h : holdings) {
            Stock stock = stockDao.findById(h.getStockId());
            if (stock == null) continue;

            BigDecimal rate = toCny.getOrDefault(stock.getCurrency(), BigDecimal.ONE);

            HoldingSnapshot snap = new HoldingSnapshot();
            snap.setPortfolioId(portfolioId);
            snap.setStockId(h.getStockId());
            snap.setStockSymbol(stock.getSymbol());
            snap.setStockName(stock.getName());
            snap.setMarket(stock.getMarket());
            snap.setCurrency(stock.getCurrency());
            snap.setTotalShares(h.getTotalShares());

            // Get price (before any conversion)
            BigDecimal price = quoteService.getPrice(stock);
            if (price == null) price = stockPriceDao.findLatestClose(h.getStockId());
            price = price != null ? price : h.getAvgCost();

            // Native values (original currency, before CNY conversion)
            snap.setNativePrice(price);
            snap.setNativeAvgCost(h.getAvgCost());
            snap.setNativeInvested(h.getTotalInvested());
            snap.setNativeMarketValue(price.multiply(h.getTotalShares()).setScale(2, RoundingMode.HALF_UP));
            BigDecimal nativePnl = price.subtract(h.getAvgCost()).multiply(h.getTotalShares()).setScale(2, RoundingMode.HALF_UP);
            snap.setNativeUnrealizedPnl(nativePnl);

            // Convert to CNY
            snap.setCurrentPrice(price.multiply(rate).setScale(4, RoundingMode.HALF_UP));
            snap.setAvgCost(h.getAvgCost().multiply(rate).setScale(4, RoundingMode.HALF_UP));
            snap.setDilutedCost(h.getDilutedCost().multiply(rate).setScale(4, RoundingMode.HALF_UP));
            snap.setTotalInvested(h.getTotalInvested().multiply(rate).setScale(2, RoundingMode.HALF_UP));
            snap.setTotalDividends(h.getTotalDividends().multiply(rate).setScale(2, RoundingMode.HALF_UP));

            java.time.LocalDate yesterday = java.time.LocalDate.now().minusDays(7);
            java.util.List<StockPrice> recent = stockPriceDao.findRange(h.getStockId(), yesterday, java.time.LocalDate.now());
            if (recent.size() >= 2 && price != null) {
                BigDecimal prevClose = recent.get(recent.size() - 2).getClose();
                if (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal changePerShare = price.subtract(prevClose);
                    BigDecimal changeValue = changePerShare.multiply(h.getTotalShares()).multiply(rate).setScale(2, RoundingMode.HALF_UP);
                    snap.setChangeToday(changeValue);
                    snap.setChangePctToday(changePerShare.divide(prevClose, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
                }
            }

            snapshots.add(snap);
        }
        return snapshots;
    }

    private volatile Map<String, BigDecimal> cachedRates;
    private volatile long ratesLoadedAt;

    private Map<String, BigDecimal> loadCnyRates() {
        if (cachedRates != null && System.currentTimeMillis() - ratesLoadedAt < 300_000) return cachedRates;
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
        cachedRates = rates;
        ratesLoadedAt = System.currentTimeMillis();
        return rates;
    }
}
