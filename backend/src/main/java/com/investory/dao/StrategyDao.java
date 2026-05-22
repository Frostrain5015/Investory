package com.investory.dao;

import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class StrategyDao extends BaseDao {

    public long insert(long userId, String name, String strategyType, String strategyJson) {
        return insert(
            "INSERT INTO backtest_strategies (user_id, name, strategy_type, strategy_json) VALUES (?, ?, ?, ?)",
            userId, name, strategyType, strategyJson
        );
    }

    public void update(long id, long userId, String name, String strategyJson) {
        update("UPDATE backtest_strategies SET name=?, strategy_json=?, updated_at=NOW() WHERE id=? AND user_id=?",
            name, strategyJson, id, userId);
    }

    public List<Map<String, Object>> findByUser(long userId) {
        return jdbc.queryForList(
            "SELECT * " +
            "FROM backtest_strategies WHERE user_id = ? ORDER BY updated_at DESC",
            userId
        );
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM backtest_strategies WHERE id = ?", id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int delete(long id, long userId) {
        return update("DELETE FROM backtest_strategies WHERE id = ? AND user_id = ?", id, userId);
    }
}
