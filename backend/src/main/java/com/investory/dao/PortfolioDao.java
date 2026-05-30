package com.investory.dao;

import com.investory.model.Portfolio;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 投资组合数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code portfolios}（投资组合表）</p>
 *
 * <p>每个用户可拥有多个投资组合（如"A股组合"、"港股组合"等），
 * 组合是持仓、交易、股息、每日净值等所有数据的顶层归属单元。
 * 权限边界通过 {@code user_id} 字段确保用户只能访问自己的组合。</p>
 */
@Repository
public class PortfolioDao extends BaseDao {

    /**
     * 将结果集当前行映射为 {@link Portfolio} 对象。
     *
     * @param rs 已定位到目标行的结果集
     * @return 填充好字段的 {@link Portfolio} 实例
     * @throws SQLException 读取字段时可能抛出的数据库异常
     */
    private Portfolio map(ResultSet rs) throws SQLException {
        Portfolio p = new Portfolio();
        p.setId(rs.getLong("id"));                          // 组合主键 ID
        p.setUserId(rs.getLong("user_id"));                 // 所属用户 ID
        p.setName(rs.getString("name"));                    // 组合名称（用户自定义）
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) p.setCreatedAt(ts.toLocalDateTime()); // 创建时间（Timestamp → LocalDateTime）
        return p;
    }

    /**
     * 查询指定用户的所有投资组合，按创建时间升序排列（最早创建的在前）。
     *
     * @param userId 用户 ID
     * @return 该用户的组合列表，按 created_at 升序排列；无组合时返回空列表
     */
    public List<Portfolio> findByUser(long userId) {
        return query("SELECT * FROM portfolios WHERE user_id = ? ORDER BY created_at", this::map, userId);
    }

    /**
     * 按主键 ID 查询单个投资组合（不校验用户归属，供内部调用）。
     *
     * @param id 组合主键 ID
     * @return 对应的 {@link Portfolio} 对象，不存在时返回 {@code null}
     */
    public Portfolio findById(long id) {
        return queryOne("SELECT * FROM portfolios WHERE id = ?", this::map, id);
    }

    /**
     * 插入一个新的投资组合，并返回数据库自动生成的主键 ID。
     *
     * @param portfolio 组合对象（userId 和 name 不可为空）
     * @return 新插入记录的主键 ID
     */
    public long insert(Portfolio portfolio) {
        return insert("INSERT INTO portfolios (user_id, name) VALUES (?, ?)",
                portfolio.getUserId(), portfolio.getName());
    }

    /**
     * 按主键 ID 更新投资组合的名称。
     *
     * @param id   组合主键 ID
     * @param name 新的组合名称
     */
    public void updateName(long id, String name) {
        update("UPDATE portfolios SET name = ? WHERE id = ?", name, id);
    }

    /**
     * 按主键 ID 删除投资组合记录。
     *
     * <p>注意：此方法仅删除 portfolios 表中的记录，关联的持仓、交易、股息、
     * 每日净值等数据需由 Service 层或数据库外键级联删除处理，
     * 否则会产生孤儿数据。</p>
     *
     * @param id 待删除的组合主键 ID
     */
    public void delete(long id) {
        update("DELETE FROM portfolios WHERE id = ?", id);
    }

    /**
     * 查询所有投资组合（供后台定时任务使用，如每日净值回填）。
     *
     * @return 全部组合列表，按 id 升序排列；无组合时返回空列表
     */
    public List<Portfolio> findAll() {
        return query("SELECT * FROM portfolios ORDER BY id", this::map);
    }

    /**
     * 校验指定组合是否属于给定用户（权限验证）。
     *
     * <p>SQL 逻辑：使用 {@code COUNT(*)} 查询同时满足 {@code id} 和 {@code user_id}
     * 条件的记录数，大于 0 表示该组合确实归属于该用户。</p>
     *
     * @param portfolioId 待校验的组合 ID
     * @param userId      当前登录用户的 ID；为 {@code null} 时直接返回 {@code false}
     * @return {@code true} 表示该组合属于该用户，{@code false} 表示无权访问或参数非法
     */
    public boolean isOwner(long portfolioId, Long userId) {
        // userId 为 null（未登录）时直接拒绝，避免无谓查库
        if (userId == null) return false;
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM portfolios WHERE id = ? AND user_id = ?", Long.class, portfolioId, userId);
        return count != null && count > 0;
    }
}
