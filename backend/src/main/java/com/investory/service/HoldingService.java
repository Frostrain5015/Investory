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
 *   <li>为投资组合中的所有持仓生成带有实时/历史行情的快照视图（getSnapshots），
 *       快照含当前价格、市值、盈亏、日涨跌幅等前端展示所需字段</li>
 *   <li>加载并缓存汇率表，将多币种资产统一折算为人民币（CNY）进行展示</li>
 * </ul>
 *
 * <p>价格优先级：实时行情（RealtimeQuoteService）> T-1 收盘价 > 持仓均价（兜底）。
 */
public class HoldingService {

    private final TransactionDao transactionDao = AppContext.get(TransactionDao.class);
    private final DividendDao dividendDao = AppContext.get(DividendDao.class);
    private final HoldingDao holdingDao = AppContext.get(HoldingDao.class);
    private final StockDao stockDao = AppContext.get(StockDao.class);
    private final StockPriceDao stockPriceDao = AppContext.get(StockPriceDao.class);
    private final RealtimeQuoteService quoteService = AppContext.get(RealtimeQuoteService.class);
    private final CostCalculationService costCalcService = AppContext.get(CostCalculationService.class);

    /**
     * 重建指定投资组合中某只股票的持仓成本数据，并将结果持久化到数据库。
     *
     * <p>流程：
     * <ol>
     *   <li>从数据库读取该股票在该组合下的所有交易记录</li>
     *   <li>通过 {@link CostCalculationService#rebuild} 重新计算持仓成本</li>
     *   <li>查询该股票的累计分红并通过 {@link CostCalculationService#applyDividends} 更新摊薄成本</li>
     *   <li>调用 {@link HoldingDao#upsert} 插入或更新持仓记录</li>
     * </ol>
     *
     * @param portfolioId 投资组合 ID
     * @param stockId     股票 ID
     */
    public void rebuildHolding(long portfolioId, long stockId) {
        List<Transaction> txns = transactionDao.findByPortfolioAndStock(portfolioId, stockId);

        Holding h = costCalcService.rebuild(txns);
        h.setPortfolioId(portfolioId);
        h.setStockId(stockId);

        BigDecimal totalDiv = dividendDao.sumByPortfolioAndStock(portfolioId, stockId);
        costCalcService.applyDividends(h, totalDiv);

        holdingDao.upsert(h);
    }

    /**
     * 获取指定投资组合所有持仓的快照列表，包含实时/历史价格、市值、盈亏及日涨跌幅。
     *
     * <p>价格来源优先级：
     * <ol>
     *   <li>实时行情（盘中有效）</li>
     *   <li>T-1 收盘价（收盘后或无实时行情时兜底）</li>
     *   <li>持仓均价（完全无行情时的最终兜底，避免 NPE）</li>
     * </ol>
     *
     * <p>日涨跌逻辑：
     * <ul>
     *   <li>盘中（有实时价格）：当前实时价 vs T-1 收盘价</li>
     *   <li>收盘后（无实时价格）：T-1 收盘价 vs T-2 收盘价</li>
     * </ul>
     *
     * <p>所有价格与金额均会通过汇率折算为人民币（CNY）后存入快照，
     * 同时保留原始币种的 native* 字段供前端双币种展示使用。
     *
     * @param portfolioId 投资组合 ID
     * @return 该组合下所有持仓股票的快照列表；若持仓为空则返回空列表
     */
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
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT currency, rate FROM exchange_rates")) {
            while (rs.next()) {
                String curr = rs.getString("currency");
                BigDecimal rate = rs.getBigDecimal("rate");
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
