package com.investory.dao;

import java.sql.*;
import java.util.*;

/**
 * 回测策略数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code backtest_strategies}（回测策略定义表）</p>
 *
 * <p>该表存储用户保存的回测策略配置，每条记录包含策略名称、策略类型（如均线、动量等）、
 * 策略参数 JSON（strategy_json）和回测配置 JSON（config_json，包含资金、手续费等运行参数）。
 * 用户可保存、修改、删除自己的策略，并基于策略发起回测任务。</p>
 *
 * <p>权限边界：所有写操作均以 {@code user_id} 作为额外过滤条件，防止跨用户操作。</p>
 */
public class StrategyDao extends BaseDao {

    /**
     * 插入一条新的回测策略记录，并返回数据库自动生成的主键 ID。
     *
     * @param userId       创建该策略的用户 ID
     * @param name         策略名称（用户自定义，用于列表展示）
     * @param strategyType 策略类型标识（如 "MA_CROSS"、"MOMENTUM"），用于后端路由到对应算法
     * @param strategyJson 策略参数的 JSON 序列化字符串（如均线周期、信号阈值等）
     * @param configJson   回测运行配置的 JSON 字符串（如初始资金、手续费率、起止日期等）
     * @return 新插入记录的主键 ID
     */
    public long insert(long userId, String name, String strategyType, String strategyJson, String configJson) {
        return insert(
            "INSERT INTO backtest_strategies (user_id, name, strategy_type, strategy_json, config_json) VALUES (?, ?, ?, ?, ?)",
            userId, name, strategyType, strategyJson, configJson
        );
    }

    /**
     * 按主键 ID 和用户 ID 更新策略记录的可编辑字段，并刷新 updated_at 时间戳。
     *
     * <p>SQL 逻辑：WHERE 子句同时校验 {@code id} 和 {@code user_id}，
     * 防止用户修改他人的策略记录（双重鉴权）。</p>
     *
     * @param id           待更新的策略主键 ID
     * @param userId       操作用户的 ID（用于权限校验）
     * @param name         新的策略名称
     * @param strategyJson 新的策略参数 JSON
     * @param configJson   新的回测配置 JSON
     */
    public void update(long id, long userId, String name, String strategyJson, String configJson) {
        update("UPDATE backtest_strategies SET name=?, strategy_json=?, config_json=?, updated_at=NOW() WHERE id=? AND user_id=?",
            name, strategyJson, configJson, id, userId);
    }

    /**
     * 查询指定用户的所有回测策略，按最后修改时间降序排列（最近修改的在前）。
     *
     * <p>返回通用 {@code Map<String, Object>} 而非 POJO，避免为此场景专门创建模型类，
     * Controller 层可直接将结果序列化为 JSON 返回给前端。</p>
     *
     * @param userId 用户 ID
     * @return 该用户的策略列表，每条记录为字段名→值的 Map；无策略时返回空列表
     */
    public List<Map<String, Object>> findByUser(long userId) {
        return queryForList(
            "SELECT * " +
            "FROM backtest_strategies WHERE user_id = ? ORDER BY updated_at DESC",
            userId
        );
    }

    /**
     * 按主键 ID 查询单条策略记录（不校验用户归属，供内部调用）。
     *
     * @param id 策略主键 ID
     * @return 策略字段 Map，不存在时返回 {@code null}
     */
    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = queryForList(
            "SELECT * FROM backtest_strategies WHERE id = ?", id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按主键 ID 和用户 ID 删除策略记录（双重鉴权，防止越权删除）。
     *
     * @param id     待删除的策略主键 ID
     * @param userId 操作用户的 ID（用于权限校验）
     * @return 受影响的行数：1 表示删除成功，0 表示记录不存在或不属于该用户
     */
    public int delete(long id, long userId) {
        return update("DELETE FROM backtest_strategies WHERE id = ? AND user_id = ?", id, userId);
    }
}
