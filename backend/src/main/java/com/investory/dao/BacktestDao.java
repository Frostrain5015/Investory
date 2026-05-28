package com.investory.dao;

import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class BacktestDao extends BaseDao {

    public long insert(long userId, Long portfolioId, String name, String strategyType,
                       String strategyJson, String configJson, String startDate, String endDate,
                       String equityCurveJson, String metricsJson, String tradeLogJson) {
        return insert(
            "INSERT INTO backtest_results (user_id, portfolio_id, name, strategy_type, " +
            "strategy_json, config_json, start_date, end_date, " +
            "equity_curve_json, metrics_json, trade_log_json) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            userId, portfolioId, name, strategyType,
            strategyJson, configJson, startDate, endDate,
            equityCurveJson, metricsJson, tradeLogJson
        );
    }

    public void updateResult(long id, String equityCurveJson, String metricsJson, String tradeLogJson) {
        update(
            "UPDATE backtest_results SET equity_curve_json=?, metrics_json=?, trade_log_json=? WHERE id=?",
            equityCurveJson, metricsJson, tradeLogJson, id
        );
    }

    public List<Map<String, Object>> findByUser(long userId) {
        return jdbc.queryForList(
            "SELECT id, name, strategy_type, start_date, end_date, " +
            "LEFT(metrics_json, 500) AS metrics_preview, created_at " +
            "FROM backtest_results WHERE user_id = ? ORDER BY created_at DESC",
            userId
        );
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM backtest_results WHERE id = ?", id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int delete(long id, long userId) {
        return update("DELETE FROM backtest_results WHERE id = ? AND user_id = ?", id, userId);
    }

    public List<Map<String, Object>> findByIds(long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.addAll(ids);
        return jdbc.queryForList(
            "SELECT id, name, strategy_type, start_date, end_date, metrics_json, equity_curve_json " +
            "FROM backtest_results WHERE user_id = ? AND id IN (" + placeholders + ")",
            params.toArray()
        );
    }
}
