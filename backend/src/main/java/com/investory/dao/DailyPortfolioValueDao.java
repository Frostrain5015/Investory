package com.investory.dao;

import com.investory.model.DailyValue;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

@Repository
public class DailyPortfolioValueDao extends BaseDao {

    private DailyValue map(ResultSet rs) throws SQLException {
        DailyValue d = new DailyValue();
        d.setId(rs.getLong("id"));
        d.setPortfolioId(rs.getLong("portfolio_id"));
        Date date = rs.getDate("snapshot_date");
        if (date != null) d.setSnapshotDate(date.toLocalDate());
        d.setTotalValue(rs.getBigDecimal("total_value"));
        d.setTotalCost(rs.getBigDecimal("total_cost"));
        d.setDailyPnl(rs.getBigDecimal("daily_pnl"));
        return d;
    }

    public List<DailyValue> findRange(long portfolioId, LocalDate from, LocalDate to) {
        return query("""
            SELECT * FROM daily_portfolio_value
            WHERE portfolio_id = ? AND snapshot_date BETWEEN ? AND ?
            ORDER BY snapshot_date
            """, this::map, portfolioId, Date.valueOf(from), Date.valueOf(to));
    }

    public DailyValue findLatest(long portfolioId) {
        List<DailyValue> list = query("""
            SELECT * FROM daily_portfolio_value
            WHERE portfolio_id = ? AND snapshot_date <= CURDATE()
            ORDER BY snapshot_date DESC LIMIT 1
            """, this::map, portfolioId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<DailyValue> findAll(long portfolioId) {
        return query("""
            SELECT * FROM daily_portfolio_value
            WHERE portfolio_id = ?
            ORDER BY snapshot_date
            """, this::map, portfolioId);
    }

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
}
