package com.investory.dao;

import com.investory.model.Transaction;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class TransactionDao extends BaseDao {

    private Transaction map(ResultSet rs) throws SQLException {
        Transaction t = new Transaction();
        t.setId(rs.getLong("id"));
        t.setPortfolioId(rs.getLong("portfolio_id"));
        t.setStockId(rs.getLong("stock_id"));
        try { t.setStockName(rs.getString("stock_name")); } catch (SQLException ignored) {}
        try { t.setStockSymbol(rs.getString("stock_symbol")); } catch (SQLException ignored) {}
        try { t.setStockMarket(rs.getString("stock_market")); } catch (SQLException ignored) {}
        try { t.setCurrency(rs.getString("currency")); } catch (SQLException ignored) {}
        t.setType(rs.getString("type"));
        t.setShares(rs.getBigDecimal("shares"));
        t.setPrice(rs.getBigDecimal("price"));
        t.setFee(rs.getBigDecimal("fee"));
        Date d = rs.getDate("trade_date");
        if (d != null) t.setTradeDate(d.toLocalDate());
        t.setNote(rs.getString("note"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) t.setCreatedAt(ts.toLocalDateTime());
        return t;
    }

    public Transaction findById(long id) {
        List<Transaction> result = query("""
            SELECT t.*, s.name AS stock_name, s.symbol AS stock_symbol, s.market AS stock_market
            FROM transactions t LEFT JOIN stocks s ON t.stock_id = s.id
            WHERE t.id = ?
            """, this::map, id);
        return result.isEmpty() ? null : result.get(0);
    }

    public List<Transaction> findByPortfolio(long portfolioId) {
        return query("""
            SELECT t.*, s.name AS stock_name, s.symbol AS stock_symbol, s.market AS stock_market
            FROM transactions t LEFT JOIN stocks s ON t.stock_id = s.id
            WHERE t.portfolio_id = ? ORDER BY t.trade_date DESC, t.id DESC
            """, this::map, portfolioId);
    }

    public List<Transaction> findByPortfolioAndStock(long portfolioId, long stockId) {
        return query("""
            SELECT t.*, s.name AS stock_name, s.symbol AS stock_symbol
            FROM transactions t JOIN stocks s ON t.stock_id = s.id
            WHERE t.portfolio_id = ? AND t.stock_id = ? ORDER BY t.trade_date, t.id
            """, this::map, portfolioId, stockId);
    }

    public long insert(Transaction t) {
        return insert("""
            INSERT INTO transactions (portfolio_id, stock_id, type, shares, price, fee, trade_date, note)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            t.getPortfolioId(), t.getStockId(), t.getType(),
            t.getShares(), t.getPrice(), t.getFee(),
            Date.valueOf(t.getTradeDate()), t.getNote());
    }

    public void update(Transaction t) {
        update("""
            UPDATE transactions SET stock_id=?, type=?, shares=?, price=?, fee=?, trade_date=?, note=?
            WHERE id=?
            """,
            t.getStockId(), t.getType(), t.getShares(), t.getPrice(), t.getFee(),
            Date.valueOf(t.getTradeDate()), t.getNote(), t.getId());
    }

    public void delete(long id) {
        update("DELETE FROM transactions WHERE id = ?", id);
    }
}
