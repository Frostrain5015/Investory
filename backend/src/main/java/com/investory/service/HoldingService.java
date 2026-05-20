package com.investory.service;

import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class HoldingService {

    @Autowired private TransactionDao transactionDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;
    @Autowired private RealtimeQuoteService quoteService;
    @Autowired private CostCalculationService costCalcService;

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

        for (Holding h : holdings) {
            Stock stock = stockDao.findById(h.getStockId());
            if (stock == null) continue;

            HoldingSnapshot snap = new HoldingSnapshot();
            snap.setPortfolioId(portfolioId);
            snap.setStockId(h.getStockId());
            snap.setStockSymbol(stock.getSymbol());
            snap.setStockName(stock.getName());
            snap.setMarket(stock.getMarket());
            snap.setCurrency(stock.getCurrency());
            snap.setTotalShares(h.getTotalShares());
            snap.setAvgCost(h.getAvgCost());
            snap.setDilutedCost(h.getDilutedCost());
            snap.setTotalInvested(h.getTotalInvested());
            snap.setTotalDividends(h.getTotalDividends());

            // Try real-time price first, fall back to DB cache
            BigDecimal price = quoteService.getPrice(stock);
            if (price == null) price = stockPriceDao.findLatestClose(h.getStockId());
            snap.setCurrentPrice(price != null ? price : h.getAvgCost());

            // Today's change: (currentPrice - yesterdayClose)
            java.time.LocalDate yesterday = java.time.LocalDate.now().minusDays(7);
            java.util.List<StockPrice> recent = stockPriceDao.findRange(h.getStockId(), yesterday, java.time.LocalDate.now());
            if (recent.size() >= 2 && price != null) {
                BigDecimal prevClose = recent.get(recent.size() - 2).getClose();
                if (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal changePerShare = price.subtract(prevClose);
                    BigDecimal changeValue = changePerShare.multiply(h.getTotalShares()).setScale(2, java.math.RoundingMode.HALF_UP);
                    snap.setChangeToday(changeValue);
                    snap.setChangePctToday(changePerShare.divide(prevClose, 4, java.math.RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP));
                }
            }

            snapshots.add(snap);
        }
        return snapshots;
    }
}
