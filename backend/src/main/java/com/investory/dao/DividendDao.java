package com.investory.dao;

import com.investory.model.Dividend;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 股息记录数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code dividends}（股息记录表），关联表：{@code stocks}</p>
 *
 * <p>该表记录每次收到的分红/股息信息，包括每股派息金额、持股数、总收入和登记日期。
 * 查询时 JOIN {@code stocks} 表以附带股票名称和代码，方便前端展示。</p>
 *
 * <p>股息数据会影响持仓的稀释成本（diluted_cost）计算：
 * 累计股息越多，等效成本越低，从而更真实地反映持有收益。</p>
 */
@Repository
public class DividendDao extends BaseDao {

    /**
     * 将结果集当前行映射为 {@link Dividend} 对象。
     *
     * <p>stock_name / stock_symbol 为 JOIN 后的冗余列，
     * 简单查询（无 JOIN）时通过 try-catch 跳过不存在的列。</p>
     *
     * @param rs 已定位到目标行的结果集
     * @return 填充好字段的 {@link Dividend} 实例
     * @throws SQLException 读取必要字段时可能抛出的数据库异常
     */
    private Dividend map(ResultSet rs) throws SQLException {
        Dividend d = new Dividend();
        d.setId(rs.getLong("id"));                               // 股息记录主键
        d.setPortfolioId(rs.getLong("portfolio_id"));            // 所属组合 ID
        d.setStockId(rs.getLong("stock_id"));                    // 关联股票 ID
        // 以下字段来自 JOIN stocks，简单查询中不存在，用 try-catch 跳过
        try { d.setStockName(rs.getString("stock_name")); } catch (SQLException ignored) {}
        try { d.setStockSymbol(rs.getString("stock_symbol")); } catch (SQLException ignored) {}
        d.setAmountPerShare(rs.getBigDecimal("amount_per_share")); // 每股派息金额
        d.setSharesHeld(rs.getBigDecimal("shares_held"));         // 登记日当日持股数
        d.setTotalAmount(rs.getBigDecimal("total_amount"));       // 本次股息总收入（每股派息 × 持股数）
        Date date = rs.getDate("record_date");
        if (date != null) d.setRecordDate(date.toLocalDate());   // 股息登记日（Date → LocalDate）
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) d.setCreatedAt(ts.toLocalDateTime());   // 创建时间（Timestamp → LocalDateTime）
        return d;
    }

    /**
     * 查询指定组合的所有股息记录，按登记日降序排列（最新在前）。
     *
     * <p>SQL 逻辑：INNER JOIN stocks 附带股票名称和代码；
     * 按 record_date DESC 排序，方便在股息历史页面展示最近收益。</p>
     *
     * @param portfolioId 组合 ID
     * @return 股息记录列表（含股票信息），按登记日倒序排列
     */
    public List<Dividend> findByPortfolio(long portfolioId) {
        return query("""
            SELECT d.*, s.name AS stock_name, s.symbol AS stock_symbol
            FROM dividends d JOIN stocks s ON d.stock_id = s.id
            WHERE d.portfolio_id = ? ORDER BY d.record_date DESC
            """, this::map, portfolioId);
    }

    /**
     * 查询指定组合中某只股票的所有股息记录，按登记日降序排列。
     *
     * <p>用于股票详情页展示该股票的历史分红记录。</p>
     *
     * @param portfolioId 组合 ID
     * @param stockId     股票 ID
     * @return 该股票在该组合内的全部股息列表，按登记日倒序排列
     */
    public List<Dividend> findByPortfolioAndStock(long portfolioId, long stockId) {
        return query("""
            SELECT d.*, s.name AS stock_name, s.symbol AS stock_symbol
            FROM dividends d JOIN stocks s ON d.stock_id = s.id
            WHERE d.portfolio_id = ? AND d.stock_id = ? ORDER BY d.record_date DESC
            """, this::map, portfolioId, stockId);
    }

    /**
     * 统计指定组合中某只股票的股息总收入。
     *
     * <p>SQL 逻辑：使用 {@code COALESCE(SUM(...), 0)} 处理无记录时 SUM 返回 NULL 的情况，
     * 确保结果始终为数值（不返回 null）。</p>
     *
     * @param portfolioId 组合 ID
     * @param stockId     股票 ID
     * @return 该股票在该组合内的累计股息总额；无记录时返回 {@link BigDecimal#ZERO}
     */
    public BigDecimal sumByPortfolioAndStock(long portfolioId, long stockId) {
        BigDecimal result = queryOne(
            // COALESCE 保证无记录时返回 0 而非 NULL
            "SELECT COALESCE(SUM(total_amount), 0) AS total FROM dividends WHERE portfolio_id = ? AND stock_id = ?",
            rs -> rs.getBigDecimal("total"), portfolioId, stockId);
        // 双重保险：即使 queryOne 返回 null 也返回 ZERO
        return result != null ? result : BigDecimal.ZERO;
    }

    /**
     * 插入一条新的股息记录，并返回数据库自动生成的主键 ID。
     *
     * @param d 股息对象（portfolioId、stockId、amountPerShare、sharesHeld、totalAmount、recordDate 不可为空）
     * @return 新插入记录的主键 ID
     */
    public long insert(Dividend d) {
        return insert("""
            INSERT INTO dividends (portfolio_id, stock_id, amount_per_share, shares_held, total_amount, record_date)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            d.getPortfolioId(), d.getStockId(),
            d.getAmountPerShare(), d.getSharesHeld(), d.getTotalAmount(),
            // LocalDate 转换为 java.sql.Date 以匹配 JDBC 参数类型
            Date.valueOf(d.getRecordDate()));
    }

    /**
     * 按主键 ID 查询单条股息记录，同时 JOIN 股票名称和代码。
     *
     * @param id 股息记录主键 ID
     * @return 股息对象（含股票基础信息），不存在时返回 {@code null}
     */
    public Dividend findById(long id) {
        List<Dividend> result = query("""
            SELECT d.*, s.name AS stock_name, s.symbol AS stock_symbol
            FROM dividends d JOIN stocks s ON d.stock_id = s.id
            WHERE d.id = ?
            """, this::map, id);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * 按主键 ID 更新股息记录的可编辑字段。
     *
     * <p>可修改字段：amount_per_share、shares_held、total_amount、record_date。
     * portfolio_id、stock_id 和 created_at 不允许修改。</p>
     *
     * @param d 包含最新字段值的股息对象（id 不可为空）
     */
    public void update(Dividend d) {
        update("""
            UPDATE dividends SET amount_per_share=?, shares_held=?, total_amount=?, record_date=?
            WHERE id=?
            """,
            d.getAmountPerShare(), d.getSharesHeld(), d.getTotalAmount(),
            Date.valueOf(d.getRecordDate()), d.getId());
    }

    /**
     * 按主键 ID 删除指定股息记录。
     *
     * <p>注意：删除后需由 Service 层重新计算持仓的稀释成本，
     * 否则 holdings.total_dividends 与实际数据不一致。</p>
     *
     * @param id 待删除的股息记录主键 ID
     */
    public void delete(long id) {
        update("DELETE FROM dividends WHERE id = ?", id);
    }
}
