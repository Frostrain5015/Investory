package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.model.DailyValue;
import com.investory.model.HoldingSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class PortfolioAnalysisService {

    @Autowired private DailyPortfolioValueDao dailyPortfolioValueDao;
    @Autowired private JdbcTemplate jdbc;

    public BigDecimal totalMarketValue(List<HoldingSnapshot> snapshots) {
        return snapshots.stream()
                .map(HoldingSnapshot::getMarketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalDividends(List<HoldingSnapshot> snapshots) {
        BigDecimal sum = BigDecimal.ZERO;
        for (HoldingSnapshot s : snapshots) {
            if (s.getTotalDividends() != null) sum = sum.add(s.getTotalDividends());
        }
        return sum;
    }

    public BigDecimal totalInvested(List<HoldingSnapshot> snapshots) {
        return snapshots.stream()
                .map(HoldingSnapshot::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalUnrealizedPnl(List<HoldingSnapshot> snapshots) {
        return snapshots.stream()
                .map(HoldingSnapshot::getUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal overallReturnPct(List<HoldingSnapshot> snapshots) {
        BigDecimal invested = totalInvested(snapshots);
        if (invested.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return totalUnrealizedPnl(snapshots)
                .divide(invested, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public List<DailyValue> getDailyValues(long portfolioId, LocalDate from, LocalDate to) {
        return dailyPortfolioValueDao.findRange(portfolioId, from, to);
    }

    /** Today's P&L from the latest daily snapshot. */
    public DailyValue getTodayValue(long portfolioId) {
        return dailyPortfolioValueDao.findLatest(portfolioId);
    }

    /**
     * Simple return since inception.
     * Return = (totalMarketValue + cashBalance - totalInvested - netExternalCash) / (totalInvested + netExternalCash) * 100
     */
    public BigDecimal cashWeightedReturn(long portfolioId, BigDecimal totalMarketValue, BigDecimal totalInvested, BigDecimal cashBalance, BigDecimal totalDividends) {
        if (totalInvested.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        // Simple return: (MV + Div - Invested) / Invested (cash doesn't dilute)
        BigDecimal totalReturn = totalMarketValue.add(totalDividends).subtract(totalInvested);
        return totalReturn.divide(totalInvested, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Realized P&L from sells + all dividends ever received */
    public BigDecimal totalRealizedPnl(long portfolioId) {
        // Per-stock: sell proceeds - allocated buy cost + dividends
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT t.stock_id, " +
            "  COALESCE(SUM(CASE WHEN t.type='BUY' THEN t.shares ELSE 0 END),0) AS total_bought, " +
            "  COALESCE(SUM(CASE WHEN t.type='SELL' THEN t.shares ELSE 0 END),0) AS total_sold, " +
            "  COALESCE(SUM(CASE WHEN t.type='BUY' THEN t.shares*t.price+t.fee ELSE 0 END),0) AS buy_cost, " +
            "  COALESCE(SUM(CASE WHEN t.type='SELL' THEN t.shares*t.price-t.fee ELSE 0 END),0) AS sell_proceeds, " +
            "  COALESCE((SELECT SUM(d.total_amount) FROM dividends d WHERE d.portfolio_id=t.portfolio_id AND d.stock_id=t.stock_id),0) AS dividends " +
            "FROM transactions t WHERE t.portfolio_id=? AND t.type IN ('BUY','SELL') " +
            "GROUP BY t.stock_id HAVING total_sold > 0",
            portfolioId);

        BigDecimal realized = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            BigDecimal totalBought = (BigDecimal) row.get("total_bought");
            BigDecimal totalSold = (BigDecimal) row.get("total_sold");
            BigDecimal buyCost = (BigDecimal) row.get("buy_cost");
            BigDecimal sellProceeds = (BigDecimal) row.get("sell_proceeds");
            BigDecimal dividends = (BigDecimal) row.get("dividends");

            // Allocated cost for sold shares
            BigDecimal ratio = totalBought.compareTo(BigDecimal.ZERO) > 0
                ? totalSold.divide(totalBought, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal allocatedCost = buyCost.multiply(ratio);

            // Realized on sells = proceeds - allocated cost
            realized = realized.add(sellProceeds).subtract(allocatedCost);
            // All dividends are realized
            realized = realized.add(dividends);
        }
        return realized;
    }

    public List<Map<String, Object>> getClosedPositions(long portfolioId) {
        return jdbc.queryForList(
            "SELECT t.stock_id, s.symbol, s.name, s.market, " +
            "  COALESCE(SUM(CASE WHEN t.type='BUY' THEN t.shares ELSE 0 END),0) AS total_bought, " +
            "  COALESCE(SUM(CASE WHEN t.type='SELL' THEN t.shares ELSE 0 END),0) AS total_sold, " +
            "  COALESCE(SUM(CASE WHEN t.type='BUY' THEN t.shares*t.price+t.fee ELSE 0 END),0) AS buy_cost, " +
            "  COALESCE(SUM(CASE WHEN t.type='SELL' THEN t.shares*t.price-t.fee ELSE 0 END),0) AS sell_proceeds, " +
            "  COALESCE((SELECT SUM(d.total_amount) FROM dividends d WHERE d.portfolio_id=t.portfolio_id AND d.stock_id=t.stock_id),0) AS dividends " +
            "FROM transactions t JOIN stocks s ON t.stock_id=s.id " +
            "WHERE t.portfolio_id=? AND t.type IN ('BUY','SELL') " +
            "GROUP BY t.stock_id, s.symbol, s.name, s.market " +
            "HAVING total_bought > 0 AND total_bought = total_sold",
            portfolioId);
    }
}
