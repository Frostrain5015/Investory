package com.investory.service;

import com.investory.dao.*;
import com.investory.model.*;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds and queries holding snapshots enriched with live prices.
 */
public class HoldingService {

    private static final HoldingService INSTANCE = new HoldingService();
    public static HoldingService get() { return INSTANCE; }

    /** Recompute holding for one stock in a portfolio and persist it. */
    public void rebuildHolding(long portfolioId, long stockId) throws SQLException {
        List<Transaction> txns = TransactionDao.get().findByPortfolioAndStock(portfolioId, stockId);
        Holding h = CostCalculationService.get().rebuild(txns);
        h.setPortfolioId(portfolioId);
        h.setStockId(stockId);

        BigDecimal totalDiv = DividendDao.get().sumByPortfolioAndStock(portfolioId, stockId);
        CostCalculationService.get().applyDividends(h, totalDiv);

        HoldingDao.get().upsert(h);
    }

    /**
     * Return all holdings in a portfolio enriched with latest price and computed P&L.
     * Only positions with shares > 0 are included.
     */
    public List<HoldingSnapshot> getSnapshots(long portfolioId) throws SQLException {
        List<Holding> holdings = HoldingDao.get().findByPortfolio(portfolioId);
        List<HoldingSnapshot> snapshots = new ArrayList<>();

        for (Holding h : holdings) {
            Stock stock = StockDao.get().findById(h.getStockId());
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

            // Latest close price from stock_prices table
            BigDecimal price = StockPriceDao.get().findLatestClose(h.getStockId());
            snap.setCurrentPrice(price != null ? price : h.getAvgCost());

            snapshots.add(snap);
        }
        return snapshots;
    }
}
