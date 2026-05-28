package com.investory.dao;

import com.investory.model.User;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * 用户数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code users}（用户账号表）及多张关联子表</p>
 *
 * <p>该表存储用户的登录凭证（用户名、密码哈希）、邮箱和管理员标志。
 * {@link #delete} 方法负责级联删除用户名下所有关联数据，
 * 包括组合、持仓、交易、股息、每日净值、自选股、AI 设置、回测记录、聊天历史等，
 * 实现完整的账号注销逻辑。</p>
 */
@Repository
public class UserDao extends BaseDao {

    /**
     * 将结果集当前行映射为 {@link User} 对象。
     *
     * <p>{@code is_admin} 字段为可选列（部分旧版本数据库可能不存在），
     * 通过 try-catch 忽略列不存在的异常，保持向后兼容。</p>
     *
     * @param rs 已定位到目标行的结果集
     * @return 填充好字段的 {@link User} 实例
     * @throws SQLException 读取必要字段时可能抛出的数据库异常
     */
    private User map(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(rs.getLong("id"));                             // 用户主键 ID
        u.setUsername(rs.getString("username"));               // 登录用户名（唯一）
        u.setPasswordHash(rs.getString("password_hash"));      // BCrypt 哈希后的密码
        u.setEmail(rs.getString("email"));                     // 用户邮箱
        try { u.setFrostIdId(rs.getString("frost_id_id")); } catch (SQLException ignored) {}
        // is_admin 为可选字段，列不存在时静默跳过（兼容旧版本数据库结构）
        try { u.setAdmin(rs.getBoolean("is_admin")); } catch (SQLException ignored) {}
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) u.setCreatedAt(ts.toLocalDateTime()); // 注册时间（Timestamp → LocalDateTime）
        return u;
    }

    /**
     * 按用户名精确查询用户信息（用于登录认证）。
     *
     * <p>SQL 逻辑：{@code username} 字段有唯一索引，最多返回一条记录。</p>
     *
     * @param username 登录用户名（大小写敏感，与存储格式一致）
     * @return 对应的 {@link User} 对象（含密码哈希），用户不存在时返回 {@code null}
     */
    public User findByUsername(String username) {
        return queryOne("SELECT * FROM users WHERE username = ?", this::map, username);
    }

    /**
     * 按主键 ID 查询用户信息。
     *
     * @param id 用户主键 ID
     * @return 对应的 {@link User} 对象，不存在时返回 {@code null}
     */
    public User findById(long id) {
        return queryOne("SELECT * FROM users WHERE id = ?", this::map, id);
    }

    /**
     * 插入一个新用户记录，并返回数据库自动生成的主键 ID。
     *
     * <p>注意：调用方应在传入前完成密码的 BCrypt 哈希处理，此方法不做任何加密操作。</p>
     *
     * @param user 用户对象（username、passwordHash、email 不可为空）
     * @return 新插入记录的主键 ID
     */
    public long insert(User user) {
        if (user.getFrostIdId() != null) {
            return insert(
                "INSERT INTO users (username, password_hash, email, frost_id_id) VALUES (?, ?, ?, ?)",
                user.getUsername(), user.getPasswordHash(), user.getEmail(), user.getFrostIdId()
            );
        }
        return insert(
            "INSERT INTO users (username, password_hash, email) VALUES (?, ?, ?)",
            user.getUsername(), user.getPasswordHash(), user.getEmail()
        );
    }

    /**
     * 按用户 ID 更新密码哈希（用于修改密码功能）。
     *
     * <p>直接调用 {@code jdbc.update} 而非父类的 {@code update} 方法，语义等价。</p>
     *
     * @param userId 用户主键 ID
     * @param hash   新密码的 BCrypt 哈希值
     */
    public void updatePassword(long userId, String hash) {
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", hash, userId);
    }

    /**
     * 按 Frost ID 用户唯一标识查找已绑定的用户。
     */
    public User findByFrostIdId(String frostIdId) {
        return queryOne("SELECT * FROM users WHERE frost_id_id = ?", this::map, frostIdId);
    }

    /**
     * 按邮箱地址查找用户。
     */
    public User findByEmail(String email) {
        return queryOne("SELECT * FROM users WHERE email = ?", this::map, email);
    }

    /**
     * 更新用户的 Frost ID 绑定。
     */
    public void updateFrostIdId(long userId, String frostIdId) {
        jdbc.update("UPDATE users SET frost_id_id = ? WHERE id = ?", frostIdId, userId);
    }

    /**
     * 检查指定用户名是否已被注册（用于注册时的重复校验）。
     *
     * <p>SQL 逻辑：查询 users 表中 username 匹配的记录的 id 字段，
     * 若能查到则说明用户名已存在。</p>
     *
     * @param username 待检查的用户名
     * @return {@code true} 表示用户名已存在，{@code false} 表示可以注册
     */
    public boolean usernameExists(String username) {
        return queryOne("SELECT id FROM users WHERE username = ?",
                rs -> rs.getLong("id"), username) != null;
    }

    /**
     * 级联删除用户及其名下所有数据（账号注销）。
     *
     * <p>删除顺序说明：子表数据必须先于父表数据删除，以避免外键约束冲突。
     * 具体删除顺序如下（均通过子查询关联 portfolios 表）：
     * <ol>
     *   <li>{@code daily_portfolio_value} — 每日组合净值快照</li>
     *   <li>{@code dividends}            — 股息记录</li>
     *   <li>{@code transactions}         — 交易流水</li>
     *   <li>{@code holdings}             — 持仓汇总</li>
     *   <li>{@code cash_balances}        — 现金余额</li>
     *   <li>{@code watchlist}            — 自选股列表</li>
     *   <li>{@code ai_settings}          — AI 功能设置</li>
     *   <li>{@code backtest_results}     — 回测结果</li>
     *   <li>{@code backtest_strategies}  — 回测策略定义</li>
     *   <li>{@code ai_chat_history}      — AI 聊天历史</li>
     *   <li>{@code portfolios}           — 投资组合（父表，最后删除）</li>
     *   <li>{@code users}                — 用户账号本体（最终删除）</li>
     * </ol>
     * </p>
     *
     * @param userId 待删除的用户主键 ID
     */
    public void delete(long userId) {
        // 先删除所有组合下的子表数据（通过子查询 SELECT id FROM portfolios WHERE user_id=? 关联）
        jdbc.update("DELETE FROM daily_portfolio_value WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM dividends WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM transactions WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM holdings WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM cash_balances WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        // 删除直接关联 user_id 的数据（不经过 portfolios）
        jdbc.update("DELETE FROM watchlist WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ai_settings WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM backtest_results WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM backtest_strategies WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ?", userId);
        // 删除父表记录（portfolios 必须在其子表清空后删除）
        jdbc.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        // 最后删除用户账号本体
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }
}
