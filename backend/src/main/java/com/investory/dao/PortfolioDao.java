package com.investory.dao;

import com.investory.model.Portfolio;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class PortfolioDao extends BaseDao {

    private Portfolio map(ResultSet rs) throws SQLException {
        Portfolio p = new Portfolio();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setName(rs.getString("name"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) p.setCreatedAt(ts.toLocalDateTime());
        return p;
    }

    public List<Portfolio> findByUser(long userId) {
        return query("SELECT * FROM portfolios WHERE user_id = ? ORDER BY created_at", this::map, userId);
    }

    public Portfolio findById(long id) {
        return queryOne("SELECT * FROM portfolios WHERE id = ?", this::map, id);
    }

    public long insert(Portfolio portfolio) {
        return insert("INSERT INTO portfolios (user_id, name) VALUES (?, ?)",
                portfolio.getUserId(), portfolio.getName());
    }

    public void updateName(long id, String name) {
        update("UPDATE portfolios SET name = ? WHERE id = ?", name, id);
    }

    public void delete(long id) {
        update("DELETE FROM portfolios WHERE id = ?", id);
    }
}
