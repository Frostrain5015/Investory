package com.investory.dao;

import com.investory.model.Holding;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 持仓数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code holdings}（持仓汇总表）</p>
 *
 * <p>该表以 {@code (portfolio_id, stock_id)} 作为唯一约束，记录每个组合中每只股票的
 * 当前总持股数、平均成本、稀释成本、总投入金额及累计已收股息。
 * 每次交易/股息操作后由 Service 层调用 {@link #upsert} 刷新最新状态。</p>
 */
@Repository
public class HoldingDao extends BaseDao {

    /**
     * 将结果集当前行映射为 {@link Holding} 对象。
     *
     * @param rs 已定位到目标行的结果集
     * @return 填充好字段的 {@link Holding} 实例
     * @throws SQLException 读取字段时可能抛出的数据库异常
     */
    private Holding map(ResultSet rs) throws SQLException {
        Holding h = new Holding();
        h.setId(rs.getLong("id"));                             // 持仓记录主键
        h.setPortfolioId(rs.getLong("portfolio_id"));          // 所属组合 ID
        h.setStockId(rs.getLong("stock_id"));                  // 关联股票 ID
        h.setTotalShares(rs.getBigDecimal("total_shares"));    // 当前总持股数（卖出后减少）
        h.setAvgCost(rs.getBigDecimal("avg_cost"));            // 加权平均买入成本（每股）
        h.setDilutedCost(rs.getBigDecimal("diluted_cost"));    // 股息再投资后的稀释成本（每股）
        h.setTotalInvested(rs.getBigDecimal("total_invested")); // 累计净投入资金
        h.setTotalDividends(rs.getBigDecimal("total_dividends")); // 累计已收股息总额
        Timestamp ts = rs.getTimestamp("updated_at");
        if (ts != null) h.setUpdatedAt(ts.toLocalDateTime()); // 最后更新时间（Timestamp → LocalDateTime）
        return h;
    }

    /**
     * 查询指定组合下所有持仓中股数大于 0 的记录（即仍有持仓的股票）。
     *
     * <p>SQL 逻辑：筛选 {@code portfolio_id} 匹配且 {@code total_shares > 0} 的记录，
     * 已清仓（total_shares = 0）的持仓不返回。</p>
     *
     * @param portfolioId 组合 ID
     * @return 当前持仓列表（不含已清仓股票），无持仓时返回空列表
     */
    public List<Holding> findByPortfolio(long portfolioId) {
        return query("SELECT * FROM holdings WHERE portfolio_id = ? AND total_shares > 0",
                this::map, portfolioId);
    }

    /**
     * 查询指定组合中某只股票的持仓记录（包含已清仓记录）。
     *
     * <p>SQL 逻辑：按 {@code (portfolio_id, stock_id)} 精确匹配，
     * 该组合为唯一键，最多返回一条记录。</p>
     *
     * @param portfolioId 组合 ID
     * @param stockId     股票 ID
     * @return 持仓对象，若尚未建仓则返回 {@code null}
     */
    public Holding findByPortfolioAndStock(long portfolioId, long stockId) {
        return queryOne("SELECT * FROM holdings WHERE portfolio_id = ? AND stock_id = ?",
                this::map, portfolioId, stockId);
    }

    /**
     * 插入或更新持仓记录（upsert）。
     *
     * <p>SQL 逻辑：使用 MySQL 的 {@code INSERT ... ON DUPLICATE KEY UPDATE} 语法，
     * 以 {@code (portfolio_id, stock_id)} 唯一键判断：
     * <ul>
     *   <li>若该组合-股票组合不存在 → 执行 INSERT，创建新持仓记录</li>
     *   <li>若已存在 → 执行 UPDATE，覆盖 total_shares / avg_cost / diluted_cost /
     *       total_invested / total_dividends 五个字段为最新计算值</li>
     * </ul>
     * </p>
     *
     * @param h 持仓对象（portfolioId 和 stockId 不可为空）
     */
    public void upsert(Holding h) {
        update("""
            INSERT INTO holdings (portfolio_id, stock_id, total_shares, avg_cost, diluted_cost,
                                  total_invested, total_dividends)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              total_shares    = VALUES(total_shares),
              avg_cost        = VALUES(avg_cost),
              diluted_cost    = VALUES(diluted_cost),
              total_invested  = VALUES(total_invested),
              total_dividends = VALUES(total_dividends)
            """,
            h.getPortfolioId(), h.getStockId(),
            h.getTotalShares(), h.getAvgCost(), h.getDilutedCost(),
            h.getTotalInvested(), h.getTotalDividends());
    }
}
