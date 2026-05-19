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
     * Cash-weighted return (Modified Dietz Method) since inception.
     * Return = (EMV - BMV - netCF) / (BMV + Σ(CF_i × w_i)) × 100
     */
    public BigDecimal cashWeightedReturn(long portfolioId) {
        List<DailyValue> all = dailyPortfolioValueDao.findAll(portfolioId);
        if (all.size() < 2) return BigDecimal.ZERO;

        DailyValue first = all.get(0);
        DailyValue last = all.get(all.size() - 1);
        long totalDays = ChronoUnit.DAYS.between(first.getSnapshotDate(), last.getSnapshotDate());
        if (totalDays == 0) return BigDecimal.ZERO;

        BigDecimal bmv = first.getTotalValue();
        BigDecimal emv = last.getTotalValue();
        BigDecimal netCf = BigDecimal.ZERO;
        BigDecimal weightedCf = BigDecimal.ZERO;

        for (int i = 1; i < all.size(); i++) {
            DailyValue prev = all.get(i - 1);
            DailyValue curr = all.get(i);
            BigDecimal cf = curr.getTotalCost().subtract(prev.getTotalCost());
            if (cf.compareTo(BigDecimal.ZERO) != 0) {
                netCf = netCf.add(cf);
                long daysSince = ChronoUnit.DAYS.between(first.getSnapshotDate(), curr.getSnapshotDate());
                BigDecimal weight = BigDecimal.valueOf(totalDays - daysSince)
                        .divide(BigDecimal.valueOf(totalDays), 6, RoundingMode.HALF_UP);
                weightedCf = weightedCf.add(cf.multiply(weight));
            }
        }

        BigDecimal denominator = bmv.add(weightedCf);
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        return emv.subtract(bmv).subtract(netCf)
                .divide(denominator, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
