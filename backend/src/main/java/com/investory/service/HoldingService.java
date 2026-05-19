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

            snapshots.add(snap);
        }
        return snapshots;
    }
}
