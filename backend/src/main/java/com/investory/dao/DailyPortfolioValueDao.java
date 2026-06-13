package com.investory.dao;

import com.investory.model.DailyValue;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * 组合每日净值数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code daily_portfolio_value}（组合每日资产快照表）</p>
 *
 * <p>该表以 {@code (portfolio_id, snapshot_date)} 作为唯一约束，
 * 每天由定时任务（爬虫）或手动触发后写入当日组合的总市值、总成本及当日盈亏，
 * 用于绘制组合净值曲线和计算历史收益率。</p>
 */
public class DailyPortfolioValueDao extends BaseDao {

    /**
     * 将结果集当前行映射为 {@link DailyValue} 对象。
     *
     * @param rs 已定位到目标行的结果集
     * @return 填充好字段的 {@link DailyValue} 实例
     * @throws SQLException 读取字段时可能抛出的数据库异常
     */
    private DailyValue map(ResultSet rs) throws SQLException {
        DailyValue d = new DailyValue();
        d.setId(rs.getLong("id"));                             // 记录主键
        d.setPortfolioId(rs.getLong("portfolio_id"));          // 所属组合 ID
        Date date = rs.getDate("snapshot_date");
        if (date != null) d.setSnapshotDate(date.toLocalDate()); // 快照日期（java.sql.Date → LocalDate）
        d.setTotalValue(rs.getBigDecimal("total_value"));      // 当日组合总市值
        d.setTotalCost(rs.getBigDecimal("total_cost"));        // 当日组合总成本
        d.setDailyPnl(rs.getBigDecimal("daily_pnl"));         // 当日盈亏（总市值 - 总成本）
        return d;
    }

    /**
     * 查询指定组合在某日期范围内的每日净值记录，按日期升序排列。
     *
     * <p>SQL 逻辑：使用 {@code BETWEEN ? AND ?} 筛选 {@code snapshot_date} 在
     * [{@code from}, {@code to}] 闭区间内的记录，结果按日期从早到晚排序，
     * 适用于绘制折线图。</p>
     *
     * @param portfolioId 组合 ID
     * @param from        查询起始日期（含）
     * @param to          查询结束日期（含）
     * @return 日期范围内的每日净值列表，按 snapshot_date 升序排列
     */
    public List<DailyValue> findRange(long portfolioId, LocalDate from, LocalDate to) {
        return query("""
            SELECT * FROM daily_portfolio_value
            WHERE portfolio_id = ? AND snapshot_date BETWEEN ? AND ?
            ORDER BY snapshot_date
            """, this::map, portfolioId, Date.valueOf(from), Date.valueOf(to));
    }

    /**
     * 查询指定组合截至今日的最新一条净值快照。
     *
     * <p>SQL 逻辑：限定 {@code snapshot_date <= CURDATE()} 排除未来日期（数据预填场景），
     * 按日期降序取第一条（即最近一天有数据的记录）。</p>
     *
     * @param portfolioId 组合 ID
     * @return 最新净值快照，若无任何历史数据则返回 {@code null}
     */
    public DailyValue findLatest(long portfolioId) {
        List<DailyValue> list = query("""
            SELECT * FROM daily_portfolio_value
            WHERE portfolio_id = ? AND snapshot_date <= CURDATE()
            ORDER BY snapshot_date DESC LIMIT 1
            """, this::map, portfolioId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询指定组合的全部历史净值记录，按日期升序排列。
     *
     * <p>用于导出全量数据或计算全周期收益率等场景，不做日期范围过滤。</p>
     *
     * @param portfolioId 组合 ID
     * @return 所有历史每日净值列表，按 snapshot_date 升序排列
     */
    public List<DailyValue> findAll(long portfolioId) {
        return query("""
            SELECT * FROM daily_portfolio_value
            WHERE portfolio_id = ?
            ORDER BY snapshot_date
            """, this::map, portfolioId);
    }

    /**
     * 插入或更新指定日期的组合净值快照（upsert）。
     *
     * <p>SQL 逻辑：使用 MySQL 的 {@code INSERT ... ON DUPLICATE KEY UPDATE} 语法，
     * 以 {@code (portfolio_id, snapshot_date)} 唯一键判断：
     * <ul>
     *   <li>若该日期快照不存在 → 执行 INSERT，创建新快照</li>
     *   <li>若已存在 → 执行 UPDATE，覆盖 total_value / total_cost / daily_pnl</li>
     * </ul>
     * 当日收盘价更新后重新计算净值时会触发覆盖逻辑。</p>
     *
     * @param v 日净值对象（portfolioId 和 snapshotDate 不可为空）
     */
    public void upsert(DailyValue v) {
        update("""
            INSERT INTO daily_portfolio_value (portfolio_id, snapshot_date, total_value, total_cost, daily_pnl)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              total_value = VALUES(total_value),
              total_cost  = VALUES(total_cost),
              daily_pnl   = VALUES(daily_pnl)
            """,
            v.getPortfolioId(), Date.valueOf(v.getSnapshotDate()),
            v.getTotalValue(), v.getTotalCost(), v.getDailyPnl());
    }

    public void deleteFrom(long portfolioId, LocalDate from) {
        update("""
            DELETE FROM daily_portfolio_value
            WHERE portfolio_id = ? AND snapshot_date >= ?
            """, portfolioId, Date.valueOf(from));
    }
}
