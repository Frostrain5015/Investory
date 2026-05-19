package com.investory.dao;

import com.investory.model.Portfolio;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

public class PortfolioDao extends BaseDao {

    private static final PortfolioDao INSTANCE = new PortfolioDao();
    public static PortfolioDao get() { return INSTANCE; }

    private Portfolio map(ResultSet rs) throws SQLException {
        Portfolio p = new Portfolio();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setName(rs.getString("name"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) p.setCreatedAt(ts.toLocalDateTime());
        return p;
    }

    public List<Portfolio> findByUser(long userId) throws SQLException {
        return query("SELECT * FROM portfolios WHERE user_id = ? ORDER BY created_at", this::map, userId);
    }

    public Portfolio findById(long id) throws SQLException {
        return queryOne("SELECT * FROM portfolios WHERE id = ?", this::map, id);
    }

    public long insert(Portfolio portfolio) throws SQLException {
        return insert("INSERT INTO portfolios (user_id, name) VALUES (?, ?)",
                portfolio.getUserId(), portfolio.getName());
    }

    public void delete(long id) throws SQLException {
        update("DELETE FROM portfolios WHERE id = ?", id);
    }
}
