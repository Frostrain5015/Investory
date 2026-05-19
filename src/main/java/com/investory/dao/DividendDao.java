package com.investory.dao;

import com.investory.model.Dividend;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

public class DividendDao extends BaseDao {

    private static final DividendDao INSTANCE = new DividendDao();
    public static DividendDao get() { return INSTANCE; }

    private Dividend map(ResultSet rs) throws SQLException {
        Dividend d = new Dividend();
        d.setId(rs.getLong("id"));
        d.setPortfolioId(rs.getLong("portfolio_id"));
        d.setStockId(rs.getLong("stock_id"));
        try { d.setStockName(rs.getString("stock_name")); } catch (SQLException ignored) {}
        try { d.setStockSymbol(rs.getString("stock_symbol")); } catch (SQLException ignored) {}
        d.setAmountPerShare(rs.getBigDecimal("amount_per_share"));
        d.setSharesHeld(rs.getBigDecimal("shares_held"));
        d.setTotalAmount(rs.getBigDecimal("total_amount"));
        Date date = rs.getDate("record_date");
        if (date != null) d.setRecordDate(date.toLocalDate());
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) d.setCreatedAt(ts.toLocalDateTime());
        return d;
    }

    public List<Dividend> findByPortfolio(long portfolioId) throws SQLException {
        return query("""
            SELECT d.*, s.name AS stock_name, s.symbol AS stock_symbol
            FROM dividends d JOIN stocks s ON d.stock_id = s.id
            WHERE d.portfolio_id = ? ORDER BY d.record_date DESC
            """, this::map, portfolioId);
    }

    public List<Dividend> findByPortfolioAndStock(long portfolioId, long stockId) throws SQLException {
        return query("""
            SELECT d.*, s.name AS stock_name, s.symbol AS stock_symbol
            FROM dividends d JOIN stocks s ON d.stock_id = s.id
            WHERE d.portfolio_id = ? AND d.stock_id = ? ORDER BY d.record_date DESC
            """, this::map, portfolioId, stockId);
    }

    public BigDecimal sumByPortfolioAndStock(long portfolioId, long stockId) throws SQLException {
        BigDecimal result = queryOne(
            "SELECT COALESCE(SUM(total_amount), 0) AS total FROM dividends WHERE portfolio_id = ? AND stock_id = ?",
            rs -> rs.getBigDecimal("total"), portfolioId, stockId);
        return result != null ? result : BigDecimal.ZERO;
    }

    public long insert(Dividend d) throws SQLException {
        return insert("""
            INSERT INTO dividends (portfolio_id, stock_id, amount_per_share, shares_held, total_amount, record_date)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            d.getPortfolioId(), d.getStockId(),
            d.getAmountPerShare(), d.getSharesHeld(), d.getTotalAmount(),
            Date.valueOf(d.getRecordDate()));
    }

    public void delete(long id) throws SQLException {
        update("DELETE FROM dividends WHERE id = ?", id);
    }
}
