package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.model.DailyValue;
import com.investory.model.HoldingSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class PortfolioAnalysisService {

    @Autowired private DailyPortfolioValueDao dailyPortfolioValueDao;

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
        BigDecimal netExternal = totalInvested.add(cashBalance);
        if (netExternal.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal totalReturn = totalMarketValue.add(cashBalance).add(totalDividends).subtract(netExternal);
        return totalReturn.divide(netExternal, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
