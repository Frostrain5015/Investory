package com.investory.dao;

import com.investory.model.Transaction;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 交易记录数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code transactions}（交易流水表），关联表：{@code stocks}</p>
 *
 * <p>该表记录每笔买入/卖出交易的详细信息，包括交易股票、类型、股数、价格、手续费、
 * 交易日期和备注。查询方法通常通过 LEFT JOIN / JOIN {@code stocks} 表附带股票名称、
 * 代码、市场等冗余字段，方便前端展示而无需二次查询。</p>
 *
 * <p>交易类型枚举值示例：{@code "BUY"}（买入）、{@code "SELL"}（卖出）。</p>
 */
public class TransactionDao extends BaseDao {

    /**
     * 将结果集当前行映射为 {@link Transaction} 对象。
     *
     * <p>stock_name / stock_symbol / stock_market / currency 为 JOIN 后的冗余列，
     * 简单查询（无 JOIN）时这些列不存在，通过 try-catch 忽略 {@link SQLException}
     * 以保持方法的通用性。</p>
     *
     * @param rs 已定位到目标行的结果集
     * @return 填充好字段的 {@link Transaction} 实例
     * @throws SQLException 读取必要字段时可能抛出的数据库异常
     */
    private Transaction map(ResultSet rs) throws SQLException {
        Transaction t = new Transaction();
        t.setId(rs.getLong("id"));                              // 交易记录主键
        t.setPortfolioId(rs.getLong("portfolio_id"));           // 所属组合 ID
        t.setStockId(rs.getLong("stock_id"));                   // 关联股票 ID
        // 以下字段来自 JOIN stocks，简单查询中不存在，用 try-catch 跳过
        try { t.setStockName(rs.getString("stock_name")); } catch (SQLException ignored) {}
        try { t.setStockSymbol(rs.getString("stock_symbol")); } catch (SQLException ignored) {}
        try { t.setStockMarket(rs.getString("stock_market")); } catch (SQLException ignored) {}
        try { t.setCurrency(rs.getString("currency")); } catch (SQLException ignored) {}
        t.setType(rs.getString("type"));                        // 交易类型（BUY / SELL）
        t.setShares(rs.getBigDecimal("shares"));                // 交易股数
        t.setPrice(rs.getBigDecimal("price"));                  // 成交价格（每股）
        t.setFee(rs.getBigDecimal("fee"));                      // 手续费
        Date d = rs.getDate("trade_date");
        if (d != null) t.setTradeDate(d.toLocalDate());        // 交易日期（Date → LocalDate）
        t.setNote(rs.getString("note"));                        // 备注信息
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) t.setCreatedAt(ts.toLocalDateTime()); // 创建时间（Timestamp → LocalDateTime）
        return t;
    }

    /**
     * 按交易 ID 查询单条交易记录，同时 JOIN 股票名称、代码和市场信息。
     *
     * <p>SQL 逻辑：LEFT JOIN stocks 表，将 stocks.name / symbol / market 作为别名列附加到结果集，
     * 即使股票信息缺失也不会丢失交易记录本身。</p>
     *
     * @param id 交易记录主键 ID
     * @return 交易对象（含股票基础信息），不存在时返回 {@code null}
     */
    public Transaction findById(long id) {
        List<Transaction> result = query("""
            SELECT t.*, s.name AS stock_name, s.symbol AS stock_symbol, s.market AS stock_market
            FROM transactions t LEFT JOIN stocks s ON t.stock_id = s.id
            WHERE t.id = ?
            """, this::map, id);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * 查询指定组合的所有交易记录，按交易日期和 ID 降序排列（最新记录在前）。
     *
     * <p>SQL 逻辑：LEFT JOIN stocks 附带股票基础信息；
     * 先按 trade_date DESC，再按 id DESC 保证同日多笔交易的稳定顺序。</p>
     *
     * @param portfolioId 组合 ID
     * @return 交易记录列表（含股票信息），按时间倒序排列
     */
    public List<Transaction> findByPortfolio(long portfolioId) {
        return query("""
            SELECT t.*, s.name AS stock_name, s.symbol AS stock_symbol, s.market AS stock_market
            FROM transactions t LEFT JOIN stocks s ON t.stock_id = s.id
            WHERE t.portfolio_id = ? ORDER BY t.trade_date DESC, t.id DESC
            """, this::map, portfolioId);
    }

    /**
     * 查询指定组合中某只股票的所有交易记录，按交易日期和 ID 升序排列（用于成本计算）。
     *
     * <p>SQL 逻辑：INNER JOIN stocks（确保股票存在）；
     * 先按 trade_date 再按 id 升序，保证按时间顺序重现买卖操作，
     * 用于 FIFO / 加权平均成本等计算。</p>
     *
     * @param portfolioId 组合 ID
     * @param stockId     股票 ID
     * @return 该股票在该组合内的全部交易列表，按时间升序排列
     */
    public List<Transaction> findByPortfolioAndStock(long portfolioId, long stockId) {
        return query("""
            SELECT t.*, s.name AS stock_name, s.symbol AS stock_symbol
            FROM transactions t JOIN stocks s ON t.stock_id = s.id
            WHERE t.portfolio_id = ? AND t.stock_id = ? ORDER BY t.trade_date, t.id
            """, this::map, portfolioId, stockId);
    }

    /**
     * 插入一条新的交易记录，并返回数据库自动生成的主键 ID。
     *
     * <p>注意：方法名与父类的 {@code update} 方法不同，此处覆盖了父类的 {@code insert} 方法。</p>
     *
     * @param t 交易对象（portfolioId、stockId、type、shares、price、fee、tradeDate 不可为空）
     * @return 新插入记录的主键 ID
     */
    public long insert(Transaction t) {
        return insert("""
            INSERT INTO transactions (portfolio_id, stock_id, type, shares, price, fee, trade_date, currency, note)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            t.getPortfolioId(), t.getStockId(), t.getType(),
            t.getShares(), t.getPrice(), t.getFee(),
            // LocalDate 转换为 java.sql.Date 以匹配 JDBC 参数类型
            Date.valueOf(t.getTradeDate()), t.getCurrency(), t.getNote());
    }

    /**
     * 按主键 ID 更新交易记录的可编辑字段。
     *
     * <p>可修改字段：stock_id、type、shares、price、fee、trade_date、note。
     * portfolio_id 和 created_at 不允许修改。</p>
     *
     * @param t 包含最新字段值的交易对象（id 不可为空）
     */
    public void update(Transaction t) {
        update("""
            UPDATE transactions SET stock_id=?, type=?, shares=?, price=?, fee=?, trade_date=?, currency=?, note=?
            WHERE id=?
            """,
            t.getStockId(), t.getType(), t.getShares(), t.getPrice(), t.getFee(),
            Date.valueOf(t.getTradeDate()), t.getCurrency(), t.getNote(), t.getId());
    }

    /**
     * 按主键 ID 删除指定交易记录。
     *
     * <p>注意：删除交易后需由 Service 层重新计算持仓成本和数量，
     * 否则 holdings 表数据会与实际交易不一致。</p>
     *
     * @param id 待删除的交易记录主键 ID
     */
    public void delete(long id) {
        update("DELETE FROM transactions WHERE id = ?", id);
    }
}
