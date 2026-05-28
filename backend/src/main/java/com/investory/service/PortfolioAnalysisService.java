package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.model.DailyValue;
import com.investory.model.HoldingSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 投资组合分析服务
 *
 * <p>负责对投资组合的整体财务指标进行汇总计算与查询，核心功能包括：
 * <ul>
 *   <li>聚合持仓快照数据：总市值、总投入、总分红、总未实现盈亏</li>
 *   <li>计算综合收益率：整体收益率、持仓收益率（含分红）、累计收益率</li>
 *   <li>查询每日组合净值历史</li>
 *   <li>计算已实现盈亏（卖出盈亏 + 历史分红）</li>
 *   <li>查询已平仓股票列表</li>
 * </ul>
 *
 * <p>所有金额均以人民币（CNY）为单位（由上游 {@link HoldingService} 完成币种转换）。
 */
@Service
public class PortfolioAnalysisService {

    @Autowired private DailyPortfolioValueDao dailyPortfolioValueDao; // 每日组合净值 DAO
    @Autowired private JdbcTemplate jdbc;                              // JDBC 模板，用于已实现盈亏等复杂查询

    /**
     * 计算投资组合持仓快照的总市值（CNY）。
     *
     * @param snapshots 持仓快照列表
     * @return 所有持仓市值之和；若列表为空则返回 0
     */
    public BigDecimal totalMarketValue(List<HoldingSnapshot> snapshots) {
        return snapshots.stream()
                .map(HoldingSnapshot::getMarketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 计算投资组合的累计已收分红总额（CNY）。
     *
     * <p>遍历快照列表，过滤掉 dividends 为 null 的项，求和。
     *
     * @param snapshots 持仓快照列表
     * @return 所有持仓累计分红之和；若列表为空则返回 0
     */
    public BigDecimal totalDividends(List<HoldingSnapshot> snapshots) {
        BigDecimal sum = BigDecimal.ZERO;
        for (HoldingSnapshot s : snapshots) {
            if (s.getTotalDividends() != null) sum = sum.add(s.getTotalDividends());
        }
        return sum;
    }

    /**
     * 计算投资组合的总投入成本（CNY）。
     *
     * @param snapshots 持仓快照列表
     * @return 所有持仓累计投入之和；若列表为空则返回 0
     */
    public BigDecimal totalInvested(List<HoldingSnapshot> snapshots) {
        return snapshots.stream()
                .map(HoldingSnapshot::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 计算投资组合当前持仓的总未实现盈亏（CNY）。
     *
     * <p>未实现盈亏 = 当前市值 - 持仓成本，尚未通过卖出操作锁定。
     *
     * @param snapshots 持仓快照列表
     * @return 所有持仓未实现盈亏之和；若列表为空则返回 0
     */
    public BigDecimal totalUnrealizedPnl(List<HoldingSnapshot> snapshots) {
        return snapshots.stream()
                .map(HoldingSnapshot::getUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 计算投资组合的整体收益率（%），即当前未实现盈亏相对于总投入的比率。
     *
     * <p>公式：{@code 收益率 = 总未实现盈亏 / 总投入 × 100}
     *
     * @param snapshots 持仓快照列表
     * @return 收益率百分比（保留 2 位小数）；总投入为 0 时返回 0
     */
    public BigDecimal overallReturnPct(List<HoldingSnapshot> snapshots) {
        BigDecimal invested = totalInvested(snapshots);
        if (invested.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return totalUnrealizedPnl(snapshots)
                .divide(invested, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 查询指定投资组合在日期范围内的每日净值历史记录。
     *
     * @param portfolioId 投资组合 ID
     * @param from        起始日期（含）
     * @param to          结束日期（含）
     * @return 按日期升序排列的每日净值列表
     */
    public List<DailyValue> getDailyValues(long portfolioId, LocalDate from, LocalDate to) {
        return dailyPortfolioValueDao.findRange(portfolioId, from, to);
    }

    /**
     * 获取指定投资组合最近一次每日快照（通常为今日或最近一个交易日）。
     *
     * <p>Today's P&L from the latest daily snapshot.
     *
     * @param portfolioId 投资组合 ID
     * @return 最新的 {@link DailyValue} 记录；若尚无记录则返回 {@code null}
     */
    public DailyValue getTodayValue(long portfolioId) {
        return dailyPortfolioValueDao.findLatest(portfolioId);
    }

    /**
     * 计算累计收益率（%）。
     *
     * <p>Cumulative return rate = cumulativePnl / totalInvested * 100
     * <p>公式：{@code 累计收益率 = 累计盈亏 / 总投入 × 100}
     *
     * @param cumulativePnl  累计盈亏金额（CNY）
     * @param totalInvested  总投入金额（CNY）
     * @return 累计收益率百分比（保留 2 位小数）；总投入为 0 时返回 0
     */
    public BigDecimal cumulativeReturnRate(BigDecimal cumulativePnl, BigDecimal totalInvested) {
        if (totalInvested.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return cumulativePnl.divide(totalInvested, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算持仓综合收益率（%），包含未实现盈亏和已收分红。
     *
     * <p>Holding-only return rate. Return = (MV + Div - Invested) / Invested * 100
     * <p>公式：{@code 持仓收益率 = (总市值 + 累计分红 - 总投入) / 总投入 × 100}
     * <p>将分红收益纳入计算，更真实地反映持仓的综合回报。
     *
     * @param totalMarketValue 当前持仓总市值（CNY）
     * @param totalInvested    总投入成本（CNY）
     * @param totalDividends   累计已收分红总额（CNY）
     * @return 持仓综合收益率百分比（保留 2 位小数）；总投入为 0 时返回 0
     */
    public BigDecimal holdingReturnRate(BigDecimal totalMarketValue, BigDecimal totalInvested, BigDecimal totalDividends) {
        if (totalInvested.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        // 综合收益 = 市值 + 分红 - 投入（未实现盈亏 + 分红收益）
        BigDecimal totalReturn = totalMarketValue.add(totalDividends).subtract(totalInvested);
        return totalReturn.divide(totalInvested, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算投资组合的已实现盈亏总额（CNY）。
     *
     * <p>Realized P&L from sells + all dividends ever received
     * <p>已实现盈亏来源：
     * <ol>
     *   <li>每只有过卖出操作的股票：卖出所得 - 按比例分摊的买入成本</li>
     *   <li>所有历史分红（已实际收到，与是否清仓无关）</li>
     * </ol>
     *
     * <p>分摊成本算法：
     * <pre>
     *   分摊比例 = 已卖出股数 / 总买入股数
     *   分摊成本 = 总买入成本 × 分摊比例
     *   单股卖出盈亏 = 卖出所得 - 分摊成本
     * </pre>
     *
     * @param portfolioId 投资组合 ID
     * @return 已实现盈亏总额（CNY），可为负值（亏损）
     */
    public BigDecimal totalRealizedPnl(long portfolioId) {
        // Per-stock: sell proceeds - allocated buy cost + dividends
        // 第1步：通过 SQL 按股票聚合买入数量、卖出数量、买入总成本、卖出所得及分红
        //        HAVING total_sold > 0 过滤出有卖出记录的股票
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT t.stock_id, " +
            "  COALESCE(SUM(CASE WHEN t.type='BUY' THEN t.shares ELSE 0 END),0) AS total_bought, " +
            "  COALESCE(SUM(CASE WHEN t.type='SELL' THEN t.shares ELSE 0 END),0) AS total_sold, " +
            "  COALESCE(SUM(CASE WHEN t.type='BUY' THEN t.shares*t.price+t.fee ELSE 0 END),0) AS buy_cost, " +
            "  COALESCE(SUM(CASE WHEN t.type='SELL' THEN t.shares*t.price-t.fee ELSE 0 END),0) AS sell_proceeds, " +
            "  COALESCE((SELECT SUM(d.total_amount) FROM dividends d WHERE d.portfolio_id=t.portfolio_id AND d.stock_id=t.stock_id),0) AS dividends " +
            "FROM transactions t WHERE t.portfolio_id=? AND t.type IN ('BUY','SELL') " +
            "GROUP BY t.stock_id HAVING total_sold > 0",
            portfolioId);

        BigDecimal realized = BigDecimal.ZERO; // 累计已实现盈亏

        for (Map<String, Object> row : rows) {
            BigDecimal totalBought   = (BigDecimal) row.get("total_bought");   // 历史累计买入总股数
            BigDecimal totalSold     = (BigDecimal) row.get("total_sold");     // 历史累计卖出总股数
            BigDecimal buyCost       = (BigDecimal) row.get("buy_cost");       // 历史累计买入总成本（含手续费）
            BigDecimal sellProceeds  = (BigDecimal) row.get("sell_proceeds");  // 历史累计卖出所得（扣手续费）
            BigDecimal dividends     = (BigDecimal) row.get("dividends");      // 历史累计分红总额

            // Allocated cost for sold shares
            // 第2步：计算已卖出股份对应的分摊买入成本
            //        ratio = 已卖出股数 / 总买入股数，表示卖出部分占全部买入的比例
            BigDecimal ratio = totalBought.compareTo(BigDecimal.ZERO) > 0
                ? totalSold.divide(totalBought, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal allocatedCost = buyCost.multiply(ratio); // 分摊到已卖出股份的买入成本

            // Realized on sells = proceeds - allocated cost
            // 第3步：卖出盈亏 = 卖出所得 - 分摊买入成本
            realized = realized.add(sellProceeds).subtract(allocatedCost);

            // All dividends are realized
            // 第4步：所有分红均视为已实现收益，累加
            realized = realized.add(dividends);
        }
        return realized;
    }

    /**
     * 查询投资组合中已完全平仓的股票列表（买入总量 = 卖出总量，且均 > 0）。
     *
     * <p>返回的每条记录包含：stock_id、symbol、name、market、total_bought、total_sold、
     * buy_cost、sell_proceeds、dividends，供前端"历史持仓"页面展示使用。
     *
     * @param portfolioId 投资组合 ID
     * @return 已平仓股票的原始数据列表，每条记录为列名→值的 Map；若无平仓记录则返回空列表
     */
    public List<Map<String, Object>> getClosedPositions(long portfolioId) {
        // HAVING total_bought > 0 AND total_bought = total_sold 确保是完全平仓（非部分卖出）
        return jdbc.queryForList(
            "SELECT t.stock_id, s.symbol, s.name, s.market, " +
            "  COALESCE(SUM(CASE WHEN t.type='BUY' THEN t.shares ELSE 0 END),0) AS total_bought, " +
            "  COALESCE(SUM(CASE WHEN t.type='SELL' THEN t.shares ELSE 0 END),0) AS total_sold, " +
            "  COALESCE(SUM(CASE WHEN t.type='BUY' THEN t.shares*t.price+t.fee ELSE 0 END),0) AS buy_cost, " +
            "  COALESCE(SUM(CASE WHEN t.type='SELL' THEN t.shares*t.price-t.fee ELSE 0 END),0) AS sell_proceeds, " +
            "  COALESCE((SELECT SUM(d.total_amount) FROM dividends d WHERE d.portfolio_id=t.portfolio_id AND d.stock_id=t.stock_id),0) AS dividends " +
            "FROM transactions t JOIN stocks s ON t.stock_id=s.id " +
            "WHERE t.portfolio_id=? AND t.type IN ('BUY','SELL') " +
            "GROUP BY t.stock_id, s.symbol, s.name, s.market " +
            "HAVING total_bought > 0 AND total_bought = total_sold",
            portfolioId);
    }
}
