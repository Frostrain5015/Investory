package com.investory.dao;

import com.investory.model.Holding;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class HoldingDao extends BaseDao {

    private Holding map(ResultSet rs) throws SQLException {
        Holding h = new Holding();
        h.setId(rs.getLong("id"));
        h.setPortfolioId(rs.getLong("portfolio_id"));
        h.setStockId(rs.getLong("stock_id"));
        h.setTotalShares(rs.getBigDecimal("total_shares"));
        h.setAvgCost(rs.getBigDecimal("avg_cost"));
        h.setDilutedCost(rs.getBigDecimal("diluted_cost"));
        h.setTotalInvested(rs.getBigDecimal("total_invested"));
        h.setTotalDividends(rs.getBigDecimal("total_dividends"));
        Timestamp ts = rs.getTimestamp("updated_at");
        if (ts != null) h.setUpdatedAt(ts.toLocalDateTime());
        return h;
    }

    public List<Holding> findByPortfolio(long portfolioId) {
        return query("SELECT * FROM holdings WHERE portfolio_id = ? AND total_shares > 0",
                this::map, portfolioId);
    }

    public Holding findByPortfolioAndStock(long portfolioId, long stockId) {
        return queryOne("SELECT * FROM holdings WHERE portfolio_id = ? AND stock_id = ?",
                this::map, portfolioId, stockId);
    }

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
