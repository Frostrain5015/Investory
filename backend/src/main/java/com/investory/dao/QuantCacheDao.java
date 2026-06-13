package com.investory.dao;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 量化指标缓存数据访问对象（DAO）。
 *
 * <p>操作数据表：
 * <ul>
 *   <li>{@code stock_metric_cache}（单股量化指标缓存表）：存储每只股票的百分位、Beta、
 *       波动率、最大回撤等计算结果，由后台定时任务定期刷新。</li>
 *   <li>{@code portfolio_scenario_cache}（组合情景压测缓存表）：存储历史危机情景
 *      （如 2008 年金融危机、2020 年疫情暴跌等）下组合的模拟盈亏。</li>
 *   <li>{@code portfolio_risk_cache}（组合风险汇总缓存表）：存储组合级别的加权 Beta、
 *       VaR（95% 置信度）、最大回撤等综合风险指标。</li>
 * </ul>
 * </p>
 *
 * <p>所有读操作均直接使用 {@code queryForList} 返回通用 {@code Map<String, Object>}，
 * 避免为纯缓存/展示场景创建专用 POJO 类。</p>
 */
public class QuantCacheDao extends BaseDao {

    /**
     * 批量查询多只股票的量化指标缓存，返回以 stockId 为键的指标 Map。
     *
     * <p>SQL 逻辑：使用 {@code IN (?, ?, ...)} 动态占位符批量查询
     * {@code stock_metric_cache} 表，占位符数量由入参列表长度决定，
     * 避免 N+1 查询性能问题。</p>
     *
     * <p>结果说明：
     * <ul>
     *   <li>缓存存在的股票 → 对应 stockId 在返回 Map 中有条目</li>
     *   <li>缓存不存在的股票（未计算或已过期）→ 对应 stockId 不在 Map 中，前端显示"—"</li>
     * </ul>
     * </p>
     *
     * @param stockIds 股票 ID 列表，为空或 null 时直接返回空 Map（不查库）
     * @return stockId → 指标字段 Map（包含 percentile_5y / beta_1y / volatility_1y /
     *         max_drawdown_1y / benchmark_symbol / computed_at），无缓存时返回空 Map
     */
    public Map<Long, Map<String, Object>> findMetricsByStockIds(List<Long> stockIds) {
        if (stockIds == null || stockIds.isEmpty()) return Collections.emptyMap();

        // 根据入参数量动态生成 IN 子句的占位符，例如 "?,?,?"
        String placeholders = stockIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = queryForList(
            "SELECT stock_id, percentile_5y, beta_1y, volatility_1y, max_drawdown_1y, " +
            "benchmark_symbol, computed_at " +
            "FROM stock_metric_cache WHERE stock_id IN (" + placeholders + ")",
            stockIds.toArray()); // 将 List<Long> 转为 Object[] 作为 JDBC 参数

        // 将查询结果按 stock_id 组织成 Map，便于调用方 O(1) 查找
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            // stock_id 在 JDBC 中返回类型不确定（Integer 或 Long），统一通过 Number 转换
            long sid = ((Number) row.get("stock_id")).longValue();
            result.put(sid, row);
        }
        return result;
    }

    /**
     * 查询指定组合的历史危机情景压测结果，按 scenario_key 升序排列。
     *
     * <p>SQL 逻辑：从 {@code portfolio_scenario_cache} 表中查询该组合的
     * 所有情景记录（通常固定为 4 个预设危机情景），按 scenario_key 排序以保证
     * 前端展示顺序的稳定性。</p>
     *
     * <p>detail_json 字段包含各持仓股票的逐个情景盈亏明细（JSON 字符串），
     * 由 Service 层负责反序列化。</p>
     *
     * @param portfolioId 组合 ID
     * @return 情景压测结果列表（包含 scenario_key / scenario_name / start_date / end_date /
     *         total_pnl_pct / detail_json / computed_at），未计算时返回空列表
     */
    public List<Map<String, Object>> findScenariosByPortfolio(long portfolioId) {
        return queryForList(
            "SELECT scenario_key, scenario_name, start_date, end_date, " +
            "total_pnl_pct, detail_json, computed_at " +
            "FROM portfolio_scenario_cache WHERE portfolio_id = ? " +
            // 按情景键排序，保证 UI 展示顺序固定（不受插入顺序影响）
            "ORDER BY scenario_key",
            portfolioId);
    }

    /**
     * 查询指定组合的风险汇总指标缓存（加权 Beta、VaR、最大回撤）。
     *
     * <p>SQL 逻辑：每个组合在 {@code portfolio_risk_cache} 表中最多只有一条记录
     *（以 portfolio_id 为主键或唯一键），直接取第一条即可。</p>
     *
     * @param portfolioId 组合 ID
     * @return 风险汇总 Map（包含 weighted_beta / var_95_pct / portfolio_maxdd / computed_at），
     *         未计算时返回 {@code null}
     */
    public Map<String, Object> findRiskSummaryByPortfolio(long portfolioId) {
        List<Map<String, Object>> rows = queryForList(
            "SELECT weighted_beta, var_95_pct, portfolio_maxdd, computed_at " +
            "FROM portfolio_risk_cache WHERE portfolio_id = ?",
            portfolioId);
        // 每个组合只有一条风险汇总记录，无记录时返回 null
        return rows.isEmpty() ? null : rows.get(0);
    }
}
