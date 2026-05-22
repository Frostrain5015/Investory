package com.investory.dao;

import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class QuantCacheDao extends BaseDao {

    /**
     * 批量查询股票指标缓存，返回 stockId → 指标 Map。
     * 缓存为空时返回空 Map（不抛异常），前端对应列显示"—"。
     */
    public Map<Long, Map<String, Object>> findMetricsByStockIds(List<Long> stockIds) {
        if (stockIds == null || stockIds.isEmpty()) return Collections.emptyMap();

        String placeholders = stockIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT stock_id, percentile_5y, beta_1y, volatility_1y, max_drawdown_1y, " +
            "benchmark_symbol, computed_at " +
            "FROM stock_metric_cache WHERE stock_id IN (" + placeholders + ")",
            stockIds.toArray());

        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long sid = ((Number) row.get("stock_id")).longValue();
            result.put(sid, row);
        }
        return result;
    }

    /**
     * 查询组合的 4 个历史危机压测结果，按 computed_at 降序。
     * 未计算时返回空列表。
     */
    public List<Map<String, Object>> findScenariosByPortfolio(long portfolioId) {
        return jdbc.queryForList(
            "SELECT scenario_key, scenario_name, start_date, end_date, " +
            "total_pnl_pct, detail_json, computed_at " +
            "FROM portfolio_scenario_cache WHERE portfolio_id = ? " +
            "ORDER BY scenario_key",
            portfolioId);
    }

    /**
     * 查询组合风险汇总（加权Beta / VaR / 最大回撤）。未计算时返回 null。
     */
    public Map<String, Object> findRiskSummaryByPortfolio(long portfolioId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT weighted_beta, var_95_pct, portfolio_maxdd, computed_at " +
            "FROM portfolio_risk_cache WHERE portfolio_id = ?",
            portfolioId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
