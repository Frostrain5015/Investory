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
 * <p>价格优先级：共享报价快照（含服务内保留报价）> 标记为成本估值的持仓均价。
 */
@Service
public class HoldingService {

    @Autowired private TransactionDao transactionDao;     // 交易记录 DAO
    @Autowired private DividendDao dividendDao;           // 分红记录 DAO
    @Autowired private HoldingDao holdingDao;             // 持仓 DAO
    @Autowired private StockDao stockDao;                 // 股票基本信息 DAO
    @Autowired private RealtimeQuoteService quoteService; // 实时行情服务
    @Autowired private CostCalculationService costCalcService; // 成本计算服务
    @Autowired private JdbcTemplate jdbc;                 // JDBC 模板，用于查询汇率

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
        // 第1步：查询该组合下该股票的所有交易记录（按时间升序）
        List<Transaction> txns = transactionDao.findByPortfolioAndStock(portfolioId, stockId);

        // 第2步：根据交易流水计算持仓成本指标
        Holding h = costCalcService.rebuild(txns);
        h.setPortfolioId(portfolioId);
        h.setStockId(stockId);

        // 第3步：查询累计分红金额，更新摊薄成本
        BigDecimal totalDiv = dividendDao.sumByPortfolioAndStock(portfolioId, stockId);
        costCalcService.applyDividends(h, totalDiv);

        // 第4步：持久化持仓（存在则更新，不存在则插入）
        holdingDao.upsert(h);
    }

    /**
     * 获取指定投资组合所有持仓的快照列表，包含实时/历史价格、市值、盈亏及日涨跌幅。
     *
     * <p>价格来源优先级：
     * <ol>
     *   <li>实时行情（盘中有效）</li>
     *   <li>报价服务中的有效保留报价</li>
     *   <li>持仓均价（完全无行情时的最终兜底，避免 NPE）</li>
     * </ol>
     *
     * <p>日涨跌逻辑：
     * <ul>
     *   <li>涨跌只使用同一报价携带的昨收、涨跌额和涨跌幅</li>
     *   <li>缺少报价或昨收时不从行情数据库补齐</li>
     * </ul>
     *
     * <p>所有价格与金额均会通过汇率折算为人民币（CNY）后存入快照，
     * 同时保留原始币种的 native* 字段供前端双币种展示使用。
     *
     * @param portfolioId 投资组合 ID
     * @return 该组合下所有持仓股票的快照列表；若持仓为空则返回空列表
     */
    public List<HoldingSnapshot> getSnapshots(long portfolioId) { return getSnapshots(portfolioId, true); }
    public List<HoldingSnapshot> getSnapshots(long portfolioId, boolean refreshQuotes) {
        // 第1步：查询该组合下全部持仓记录
        List<Holding> holdings = holdingDao.findByPortfolio(portfolioId);
        List<HoldingSnapshot> snapshots = new ArrayList<>();
        Map<Long,Stock> stocks = new HashMap<>();
        for (Holding holding : holdings) {
            Stock stock = stockDao.findById(holding.getStockId());
            if (stock != null) stocks.put(stock.getId(), stock);
        }
        var symbols = new ArrayList<>(stocks.values());
        Map<String,Quote> quotes = refreshQuotes ? quoteService.getQuotes(symbols) : quoteService.cachedQuotes(symbols);

        // Load exchange rates to CNY
        // 第2步：加载各币种到人民币的汇率（当日缓存，避免重复查库）
        Map<String, BigDecimal> toCny = loadCnyRates();

        for (Holding h : holdings) {
            // 第3步：查询股票基本信息（市场、币种等）
            Stock stock = stocks.get(h.getStockId());
            if (stock == null) continue; // 股票信息缺失，跳过该持仓

            BigDecimal rate = toCny.getOrDefault(stock.getCurrency(), BigDecimal.ONE); // 该股票对应的 CNY 换算率

            // 第4步：初始化快照对象，填充基础字段
            HoldingSnapshot snap = new HoldingSnapshot();
            snap.setPortfolioId(portfolioId);
            snap.setStockId(h.getStockId());
            snap.setStockSymbol(stock.getSymbol());
            snap.setStockName(stock.getName());
            snap.setMarket(stock.getMarket());
            snap.setCurrency(stock.getCurrency());
            snap.setTotalShares(h.getTotalShares());

            // QuoteService owns provider fallback and retained snapshots.
            // This presentation path never queries the historical price table.
            Quote quote = quotes.get(stock.getSymbol());
            boolean hasQuote = quote != null;
            BigDecimal price = hasQuote ? quote.price() : null;
            if (hasQuote) {
                snap.setPriceTimestamp(quote.quoteTime() == null ? null : quote.quoteTime().toString());
                snap.setPriceSource(quote.source());
                snap.setQuoteMetrics(quote.metrics());
                snap.setPriceLive(quote.live());
            }
            if (price == null) snap.setPriceSource("cost");
            // Preserve the existing cost-valuation fallback for aggregate totals.
            // It is explicitly marked and never used to invent today's movement.
            price = price != null ? price : h.getAvgCost();

            // Native values (original currency, before CNY conversion)
            // 第7步：存储原始币种数值（不转 CNY），供前端双币种展示
            snap.setNativePrice(price);
            snap.setNativeAvgCost(h.getAvgCost());
            snap.setNativeInvested(h.getTotalInvested());
            snap.setNativeMarketValue(price.multiply(h.getTotalShares()).setScale(2, RoundingMode.HALF_UP));
            // 未实现盈亏（原始币种）= (当前价 - 均价) × 持仓股数
            BigDecimal nativePnl = price.subtract(h.getAvgCost()).multiply(h.getTotalShares()).setScale(2, RoundingMode.HALF_UP);
            snap.setNativeUnrealizedPnl(nativePnl);

            // Convert to CNY
            // 第8步：将各字段折算为人民币写入快照
            snap.setCurrentPrice(price.multiply(rate).setScale(4, RoundingMode.HALF_UP));
            snap.setAvgCost(h.getAvgCost().multiply(rate).setScale(4, RoundingMode.HALF_UP));
            snap.setDilutedCost(h.getDilutedCost().multiply(rate).setScale(4, RoundingMode.HALF_UP));
            snap.setTotalInvested(h.getTotalInvested().multiply(rate).setScale(2, RoundingMode.HALF_UP));
            snap.setTotalDividends(h.getTotalDividends().multiply(rate).setScale(2, RoundingMode.HALF_UP));

            // Derive all movement from one provider snapshot, without mixing
            // a current API price with an unrelated historical database close.
            BigDecimal previous = hasQuote ? quote.previousClose() : null;
            BigDecimal change = hasQuote ? quote.change() : null;
            if (change == null && previous != null) change = price.subtract(previous);
            if (change != null) {
                snap.setNativeChangeToday(change);
                snap.setChangeToday(change.multiply(h.getTotalShares()).multiply(rate).setScale(2, RoundingMode.HALF_UP));
                if (previous == null) previous = price.subtract(change);
                if (previous.signum() > 0) {
                    snap.setChangePctToday(change.multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP));
                }
            }

            if (hasQuote && quote.changePct() != null) snap.setChangePctToday(quote.changePct());
            snapshots.add(snap);
        }
        return snapshots;
    }

    /** 当日已加载的汇率缓存（volatile 保证多线程可见性） */
    private volatile Map<String, BigDecimal> cachedRates;
    /** 汇率缓存对应的日期，用于判断是否需要刷新 */
    private volatile LocalDate ratesDate;

    /**
     * 从数据库加载各币种兑人民币的换算率，并以当天为粒度做内存缓存。
     *
     * <p>数据库 {@code exchange_rates} 表存储的是"1 CNY = N 外币"格式的汇率，
     * 因此换算率取倒数：{@code toCny = 1 / rate}，即"1 外币 = toCny CNY"。
     *
     * <p>CNY 本身汇率固定为 1，无需查库。查询异常时静默忽略，仍返回仅含 CNY 的默认 map。
     *
     * @return 以币种代码（如 "HKD"、"USD"）为键、对应 CNY 换算率为值的 Map
     */
    private Map<String, BigDecimal> loadCnyRates() {
        LocalDate today = LocalDate.now();
        // 若缓存有效（同一天），直接返回，避免重复查库
        if (cachedRates != null && today.equals(ratesDate)) return cachedRates;

        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("CNY", BigDecimal.ONE); // 人民币换算率固定为 1
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT currency, rate FROM exchange_rates");
            for (Map<String, Object> row : rows) {
                String curr = (String) row.get("currency");
                BigDecimal rate = (BigDecimal) row.get("rate");
                // 取倒数：数据库存"1 CNY 能换多少外币"，转为"1 外币 = 多少 CNY"
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                    rates.put(curr, BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception ignored) {} // 查询失败时静默处理，使用默认汇率 1
        cachedRates = rates;
        ratesDate = today;
        return rates;
    }
}
