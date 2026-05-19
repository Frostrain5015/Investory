package com.investory.dao;

import com.investory.model.DailyValue;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

public class DailyPortfolioValueDao extends BaseDao {

    private static final DailyPortfolioValueDao INSTANCE = new DailyPortfolioValueDao();
    public static DailyPortfolioValueDao get() { return INSTANCE; }

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

    public List<DailyValue> findRange(long portfolioId, LocalDate from, LocalDate to) throws SQLException {
        return query("""
            SELECT * FROM daily_portfolio_value
            WHERE portfolio_id = ? AND snapshot_date BETWEEN ? AND ?
            ORDER BY snapshot_date
            """, this::map, portfolioId, Date.valueOf(from), Date.valueOf(to));
    }

    public void upsert(DailyValue v) throws SQLException {
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
