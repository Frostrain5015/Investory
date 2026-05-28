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

/**
 * 投资组合每日净值计算器
 *
 * <p>负责计算并持久化投资组合在指定日期区间内的每日净值快照（{@link DailyValue}），
 * 核心功能为"回填"（backfill）历史每日净值：
 * <ul>
 *   <li>从指定起始日期逐日遍历至今，计算当日持仓市值、现金余额、每日盈亏</li>
 *   <li>自动处理无行情数据的非交易日：沿用最近一次已知价格</li>
 *   <li>将每日分红收益并入当日盈亏，更真实地反映当日总回报</li>
 *   <li>所有多币种资产统一折算为人民币（CNY）后计算</li>
 * </ul>
 *
 * <p>典型使用场景：用户新增或修改交易记录后，从交易日起重新计算后续所有日期的净值，
 * 保证历史净值曲线的准确性。
 */
@Service
public class PortfolioValueCalculator {

    private static final Logger log = java.util.logging.Logger.getLogger(PortfolioValueCalculator.class.getName());

    @Autowired private HoldingDao holdingDao;                       // 持仓 DAO
    @Autowired private StockPriceDao stockPriceDao;                 // 历史收盘价 DAO
    @Autowired private StockDao stockDao;                           // 股票基本信息 DAO
    @Autowired private DividendDao dividendDao;                     // 分红记录 DAO
    @Autowired private DailyPortfolioValueDao dailyDao;             // 每日净值 DAO
    @Autowired private JdbcTemplate jdbc;                           // JDBC 模板，用于汇率及现金流查询

    /**
     * 从数据库加载各币种兑人民币的换算率。
     *
     * <p>数据库 {@code exchange_rates} 表存储"1 CNY = N 外币"，
     * 因此换算率取倒数：{@code toCny = 1 / rate}（即"1 外币 = toCny CNY"）。
     * CNY 自身固定为 1，查询异常时静默忽略。
     *
     * @return 以币种代码为键、对应 CNY 换算率为值的 Map（始终包含 "CNY"→1）
     */
    private Map<String, BigDecimal> loadCnyRates() {
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
        } catch (Exception ignored) {} // 查询失败时静默处理，保留默认汇率
        return rates;
    }

    /**
     * 从指定日期起回填投资组合的每日净值（简化入口，不传交易价格参数）。
     *
     * <p>适用于非交易触发的场景（如定时任务补算历史数据）。
     *
     * @param portfolioId 投资组合 ID
     * @param fromDate    回填起始日期（含）
     */
    public void backfillFrom(long portfolioId, LocalDate fromDate) {
        backfillFrom(portfolioId, fromDate, 0, null, null);
    }

    /**
     * 从指定日期起回填投资组合的每日净值，支持传入当日交易价格作为价格兜底。
     *
     * <p>当交易发生在今天（即 fromDate = today）而收盘价尚未写入数据库时，
     * {@code tradePrice} 可用作当日该股票的价格来源，避免净值出现空洞。
     *
     * <p>核心算法（逐日遍历）：
     * <ol>
     *   <li>预计算各持仓的 CNY 换算率，避免每日重复查库</li>
     *   <li>预加载全部分红记录并按到账日期汇总，避免循环内 N+1 查询</li>
     *   <li>维护"最近已知价格"缓存（{@code lastPriceByStock}），在无行情日沿用上一个已知价格</li>
     *   <li>每日盈亏：第一日 = 当日市值 - 总持仓成本；后续日 = 当日市值 - 前日市值（价差法）</li>
     *   <li>当日若有分红到账，将分红金额叠加到当日盈亏中</li>
     *   <li>通过 {@link DailyPortfolioValueDao#upsert} 将结果插入或覆盖数据库</li>
     * </ol>
     *
     * @param portfolioId   投资组合 ID
     * @param fromDate      回填起始日期（含）
     * @param tradedStockId 触发本次回填的股票 ID（用于价格兜底，无则传 0）
     * @param tradePrice    触发交易的成交价格（用于当日价格兜底，无则传 null）
     * @param tradeShares   触发交易的成交股数（当前未使用，保留扩展）
     */
    public void backfillFrom(long portfolioId, LocalDate fromDate,
                              long tradedStockId, BigDecimal tradePrice, BigDecimal tradeShares) {
        LocalDate toDate = LocalDate.now();
        if (fromDate.isAfter(toDate)) return; // 起始日期在未来，无需回填

        // 第1步：查询当前持仓列表；若无持仓且无现金记录，则无需计算直接返回
        List<Holding> holdings = holdingDao.findByPortfolio(portfolioId);
        if (holdings.isEmpty() && !hasCashRecords(portfolioId)) return;

        // 第2步：预计算各日的现金余额（SELL 回笼、BUY 支出、转入/转出的累计净现金）
        Map<LocalDate, BigDecimal> cashByDate = computeDailyCash(portfolioId, fromDate, toDate);

        BigDecimal prevStockValue = null; // 前一个有效日的持仓市值，用于计算当日涨跌
        BigDecimal prevTotalValue = null; // 前一个有效日的组合总值（市值 + 现金）

        // 第3步：加载汇率，并为每只持仓预先绑定其对应的 CNY 换算率
        // Exchange rates & stock currencies for CNY conversion
        Map<String, BigDecimal> toCny = loadCnyRates();
        Map<Long, BigDecimal> stockCnyRate = new HashMap<>(); // stockId → CNY 换算率
        for (Holding h : holdings) {
            Stock s = stockDao.findById(h.getStockId());
            stockCnyRate.put(h.getStockId(), toCny.getOrDefault(s != null ? s.getCurrency() : "CNY", BigDecimal.ONE));
        }

        // Track last known price per stock for non-trading days
        // 第4步：初始化"最近已知价格"缓存，用于在无行情日沿用前一个已知价格，避免净值断点
        Map<Long, BigDecimal> lastPriceByStock = new HashMap<>();

        // Pre-load dividends by record date — avoid N+1 and convert to CNY
        // 第5步：预加载全部分红记录，按到账日期聚合并转换为 CNY，避免逐日查库（N+1 问题）
        Map<LocalDate, BigDecimal> divByRecordDate = new HashMap<>(); // 到账日期 → 当日分红总额（CNY）
        for (Dividend d : dividendDao.findByPortfolio(portfolioId)) {
            Stock ds = stockDao.findById(d.getStockId());
            BigDecimal divRate = toCny.getOrDefault(ds != null ? ds.getCurrency() : "CNY", BigDecimal.ONE);
            // 同一到账日可能有多个持仓的分红，使用 merge 累加
            divByRecordDate.merge(d.getRecordDate(), d.getTotalAmount().multiply(divRate), BigDecimal::add);
        }

        // 第6步：从 fromDate 到 toDate 逐日遍历，计算每日净值
        LocalDate cursor = fromDate;
        while (!cursor.isAfter(toDate)) {
            BigDecimal stockValue = BigDecimal.ZERO; // 当日所有持仓的总市值（CNY）
            BigDecimal totalCost  = BigDecimal.ZERO; // 当日所有持仓的总成本（CNY，用于第一日盈亏基准）
            boolean hasPrice = false;                // 当日是否有任何持仓获取到价格

            for (Holding h : holdings) {
                BigDecimal close = null;

                // 优先从历史收盘价表查当日收盘价
                List<StockPrice> prices = stockPriceDao.findRange(h.getStockId(), cursor, cursor);
                if (!prices.isEmpty()) close = prices.get(0).getClose();

                // 若当日无收盘价且为起始日，使用交易成交价兜底（适用于今日刚成交尚无收盘价的情况）
                if (close == null && tradePrice != null && cursor.equals(fromDate) && h.getStockId() == tradedStockId) {
                    close = tradePrice;
                }

                // 若仍无价格，沿用最近一次已知价格（非交易日/数据缺口兜底）
                if (close == null) close = lastPriceByStock.get(h.getStockId());

                if (close != null) {
                    lastPriceByStock.put(h.getStockId(), close); // 更新最近已知价格缓存
                    BigDecimal rate = stockCnyRate.getOrDefault(h.getStockId(), BigDecimal.ONE);
                    stockValue = stockValue.add(close.multiply(h.getTotalShares()).multiply(rate)); // 当日该股市值（CNY）
                    totalCost  = totalCost.add(h.getTotalInvested().multiply(rate));                // 该股持仓成本（CNY）
                    hasPrice = true;
                }
            }

            // 第7步：取当日现金余额（含历史买卖净现金及转入/转出）
            BigDecimal cashOnDay  = cashByDate.getOrDefault(cursor, BigDecimal.ZERO); // 当日现金余额（CNY）
            BigDecimal totalValue = stockValue.add(cashOnDay);                         // 当日组合总值 = 持仓市值 + 现金

            if (hasPrice || cashOnDay.compareTo(BigDecimal.ZERO) > 0) {
                // 第8步：计算当日盈亏
                // 第一日（无前日数据）：盈亏 = 市值 - 总成本（绝对盈亏）
                // 后续日：盈亏 = 当日市值 - 前日市值（价差法，更准确反映单日涨跌）
                BigDecimal dailyPnl;
                if (prevTotalValue != null && prevStockValue != null) {
                    dailyPnl = stockValue.subtract(prevStockValue); // 价差法：当日市值变化
                } else {
                    dailyPnl = stockValue.subtract(totalCost); // 首日：相对持仓成本的浮盈浮亏
                }
                prevStockValue = stockValue;
                prevTotalValue = totalValue;

                // 第9步：若当日有分红到账，将分红叠加到当日盈亏（分红亦是当日收益的一部分）
                BigDecimal divIncome = divByRecordDate.getOrDefault(cursor, BigDecimal.ZERO); // 当日分红收入（CNY）
                dailyPnl = dailyPnl.add(divIncome);

                // 第10步：构建 DailyValue 并写入数据库（存在则覆盖，不存在则插入）
                DailyValue dv = new DailyValue();
                dv.setPortfolioId(portfolioId);
                dv.setSnapshotDate(cursor);
                dv.setTotalValue(totalValue.add(divIncome)); // 总值含当日分红
                dv.setTotalCost(totalCost);
                dv.setDailyPnl(dailyPnl);
                dailyDao.upsert(dv);
            }

            cursor = cursor.plusDays(1); // 移动到下一天
        }
        log.info("Backfilled daily values for portfolio " + portfolioId + " from " + fromDate + " to " + toDate);
    }

    /**
     * 检查指定投资组合是否存在任何现金相关记录（现金余额或资金划转交易）。
     *
     * <p>用于 {@link #backfillFrom} 的前置判断：若既无持仓又无现金记录，
     * 则无需执行任何净值计算，直接跳过。
     *
     * @param portfolioId 投资组合 ID
     * @return {@code true} 表示存在现金余额或资金划转记录；否则返回 {@code false}
     */
    private boolean hasCashRecords(long portfolioId) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM (SELECT 1 FROM cash_balances WHERE portfolio_id=? AND amount>0 UNION ALL SELECT 1 FROM transactions WHERE portfolio_id=? AND type IN ('TRANSFER_IN','TRANSFER_OUT') LIMIT 1) t",
            Long.class, portfolioId, portfolioId);
        return count != null && count > 0;
    }

    /**
     * 计算指定投资组合在日期区间内每天的累计现金余额（CNY）。
     *
     * <p>现金余额由以下交易类型累积形成：
     * <ul>
     *   <li>SELL：卖出回笼现金（+），扣除手续费</li>
     *   <li>BUY：买入支出现金（-），含手续费</li>
     *   <li>TRANSFER_IN：资金转入（+）</li>
     *   <li>TRANSFER_OUT：资金转出（-）</li>
     * </ul>
     *
     * <p>算法：
     * <ol>
     *   <li>按交易日期升序读取所有交易，逐笔累加计算"交易发生日"的累计现金余额，
     *       存入 {@code TreeMap}（有序，便于范围查询）</li>
     *   <li>对 [from, to] 区间内每一天，用 {@code floorEntry} 取该日期之前最近一次
     *       有交易的现金余额，填充无交易日的现金（现金余额在无操作时保持不变）</li>
     * </ol>
     *
     * <p>所有金额均通过汇率折算为人民币（CNY）。
     *
     * @param portfolioId 投资组合 ID
     * @param from        区间起始日期（含）
     * @param to          区间结束日期（含）
     * @return 以日期为键、当日现金余额（CNY）为值的有序 Map，覆盖 [from, to] 内每一天
     */
    private Map<LocalDate, BigDecimal> computeDailyCash(long portfolioId, LocalDate from, LocalDate to) {
        // 第1步：加载汇率，将各币种现金转换为 CNY
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

        // 第2步：按日期升序读取截至 toDate 的所有交易记录（含 SELL/BUY/TRANSFER_IN/TRANSFER_OUT）
        List<Map<String, Object>> txns = jdbc.queryForList(
            "SELECT trade_date, type, shares, price, currency, fee FROM transactions WHERE portfolio_id=? AND trade_date <= ? ORDER BY trade_date",
            portfolioId, to);

        // 第3步：逐笔累加，记录每个"有交易日"的累计现金余额
        TreeMap<LocalDate, BigDecimal> cashByDate = new TreeMap<>(); // 有交易发生日的累计现金余额（有序）
        BigDecimal cumulativeCash = BigDecimal.ZERO;                  // 从账户开立至今的累计净现金
        for (Map<String, Object> t : txns) {
            String type   = (String) t.get("type");
            LocalDate d   = ((java.sql.Date) t.get("trade_date")).toLocalDate();
            BigDecimal shares = t.get("shares") != null ? (BigDecimal) t.get("shares") : BigDecimal.ZERO;
            BigDecimal price  = t.get("price")  != null ? (BigDecimal) t.get("price")  : BigDecimal.ZERO;
            BigDecimal fee    = t.get("fee")    != null ? (BigDecimal) t.get("fee")    : BigDecimal.ZERO;
            String cur        = (String) t.getOrDefault("currency", "CNY");
            BigDecimal rate   = toCny.getOrDefault(cur, BigDecimal.ONE); // 该交易币种的 CNY 换算率

            switch (type) {
                case "SELL":         cumulativeCash = cumulativeCash.add(shares.multiply(price).subtract(fee).multiply(rate)); break; // 卖出回笼净现金
                case "BUY":          cumulativeCash = cumulativeCash.subtract(shares.multiply(price).add(fee).multiply(rate)); break; // 买入支出现金
                case "TRANSFER_IN":  cumulativeCash = cumulativeCash.add(shares.multiply(rate));                               break; // 资金转入（shares 字段存转入金额）
                case "TRANSFER_OUT": cumulativeCash = cumulativeCash.subtract(shares.multiply(rate));                          break; // 资金转出
            }
            cashByDate.put(d, cumulativeCash); // 记录该日的最新累计现金余额（同日多笔交易取最后值）
        }

        // 第4步：对 [from, to] 内每一天，用 floorEntry 填充无交易日的现金余额（现金不变原则）
        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        BigDecimal running = BigDecimal.ZERO; // 当前向前传播的现金余额
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            // floorEntry 取"不晚于 d 的最大日期"对应的现金余额
            if (cashByDate.floorEntry(d) != null) running = cashByDate.floorEntry(d).getValue();
            result.put(d, running);
        }
        return result;
    }
}
