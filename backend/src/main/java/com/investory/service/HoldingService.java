package com.investory.service;

import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.model.Quote;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;

/**
 * 持仓服务
 *
 * <p>负责持仓数据的重建与快照生成，核心职责包括：
 * <ul>
 *   <li>根据最新交易流水重新计算并持久化某只股票的持仓成本（rebuildHolding）</li>
 *   <li>为投资组合中的所有持仓生成带有实时/历史行情的快照视图（getSnapshots）</li>
 * </ul>
 */
public class HoldingService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final TransactionDao transactionDao;
    private final DividendDao dividendDao;
    private final HoldingDao holdingDao;
    private final StockDao stockDao;
    private final StockPriceDao stockPriceDao;
    private final RealtimeQuoteService quoteService;
    private final CostCalculationService costCalcService;

    public HoldingService() {
        this.transactionDao = AppContext.get(TransactionDao.class);
        this.dividendDao = AppContext.get(DividendDao.class);
        this.holdingDao = AppContext.get(HoldingDao.class);
        this.stockDao = AppContext.get(StockDao.class);
        this.stockPriceDao = AppContext.get(StockPriceDao.class);
        this.quoteService = AppContext.get(RealtimeQuoteService.class);
        this.costCalcService = AppContext.get(CostCalculationService.class);
    }

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

            List<StockPrice> latestTwo = stockPriceDao.findLatestTwo(h.getStockId());
            StockPrice t1 = latestTwo.size() > 0 ? latestTwo.get(0) : null;
            StockPrice t2 = latestTwo.size() > 1 ? latestTwo.get(1) : null;

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

            snap.setNativePrice(price);
            snap.setNativeAvgCost(h.getAvgCost());
            snap.setNativeInvested(h.getTotalInvested());
            snap.setNativeMarketValue(price.multiply(h.getTotalShares()).setScale(2, RoundingMode.HALF_UP));
            BigDecimal nativePnl = price.subtract(h.getAvgCost()).multiply(h.getTotalShares()).setScale(2, RoundingMode.HALF_UP);
            snap.setNativeUnrealizedPnl(nativePnl);

            snap.setCurrentPrice(price.multiply(rate).setScale(4, RoundingMode.HALF_UP));
            snap.setAvgCost(h.getAvgCost().multiply(rate).setScale(4, RoundingMode.HALF_UP));
            snap.setDilutedCost(h.getDilutedCost().multiply(rate).setScale(4, RoundingMode.HALF_UP));
            snap.setTotalInvested(h.getTotalInvested().multiply(rate).setScale(2, RoundingMode.HALF_UP));
            snap.setTotalDividends(h.getTotalDividends().multiply(rate).setScale(2, RoundingMode.HALF_UP));

            BigDecimal currentForChange = hasLivePrice ? price : (t1 != null ? t1.getClose() : null);
            BigDecimal prevClose = hasLivePrice ? (t1 != null ? t1.getClose() : null)
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
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT currency, rate FROM exchange_rates")) {
            while (rs.next()) {
                String currency = rs.getString("currency");
                BigDecimal rate = rs.getBigDecimal("rate");
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                    rates.put(currency, BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception ignored) {}
        cachedRates = rates;
        ratesDate = today;
        return rates;
    }
}
