package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.model.DailyValue;
import com.investory.model.HoldingSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

public class PortfolioAnalysisService {

    private static final PortfolioAnalysisService INSTANCE = new PortfolioAnalysisService();
    public static PortfolioAnalysisService get() { return INSTANCE; }

    /** Total market value across all snapshots. */
    public BigDecimal totalMarketValue(List<HoldingSnapshot> snapshots) {
        return snapshots.stream()
                .map(HoldingSnapshot::getMarketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Total invested (cost basis) across all snapshots. */
    public BigDecimal totalInvested(List<HoldingSnapshot> snapshots) {
        return snapshots.stream()
                .map(HoldingSnapshot::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Total unrealized P&L across all snapshots. */
    public BigDecimal totalUnrealizedPnl(List<HoldingSnapshot> snapshots) {
        return snapshots.stream()
                .map(HoldingSnapshot::getUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Overall return percentage. */
    public BigDecimal overallReturnPct(List<HoldingSnapshot> snapshots) {
        BigDecimal invested = totalInvested(snapshots);
        if (invested.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return totalUnrealizedPnl(snapshots)
                .divide(invested, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Fetch daily P&L series for the calendar view. */
    public List<DailyValue> getDailyValues(long portfolioId, LocalDate from, LocalDate to) throws SQLException {
        return DailyPortfolioValueDao.get().findRange(portfolioId, from, to);
    }
}
