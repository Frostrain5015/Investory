package com.investory.service;

import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.model.Quote;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

            // Fetch T-1 and T-2 closes once; reused for both price fallback and change calculation
            List<StockPrice> latestTwo = stockPriceDao.findLatestTwo(h.getStockId());
            StockPrice t1 = latestTwo.size() > 0 ? latestTwo.get(0) : null;
            StockPrice t2 = latestTwo.size() > 1 ? latestTwo.get(1) : null;

            // Get price (before any conversion)
            Quote quote = quoteService.getQuote(stock);
            boolean hasLivePrice = quote != null;
            BigDecimal price = hasLivePrice ? quote.price() : null;
            if (hasLivePrice) {
                snap.setPriceTimestamp(quote.fetchedAt().toString());
            } else if (t1 != null) {
                price = t1.getClose();
                snap.setPriceTimestamp(t1.getTradeDate().toString());
            }
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

            // Today's change:
            // - Market open (live price available): live vs T-1 close
            // - Market closed (no live price):       T-1 close vs T-2 close
            BigDecimal currentForChange = hasLivePrice ? price : (t1 != null ? t1.getClose() : null);
            BigDecimal prevClose        = hasLivePrice ? (t1 != null ? t1.getClose() : null)
                                                       : (t2 != null ? t2.getClose() : null);
            if (currentForChange != null && prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePerShare = currentForChange.subtract(prevClose);
                snap.setChangeToday(changePerShare.multiply(h.getTotalShares()).multiply(rate).setScale(2, RoundingMode.HALF_UP));
                snap.setChangePctToday(changePerShare.divide(prevClose, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
            }

            snapshots.add(snap);
        }
        return snapshots;
    }

    private volatile Map<String, BigDecimal> cachedRates;
    private volatile LocalDate ratesDate;

    private Map<String, BigDecimal> loadCnyRates() {
        LocalDate today = LocalDate.now();
        if (cachedRates != null && today.equals(ratesDate)) return cachedRates;
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
        ratesDate = today;
        return rates;
    }
}
