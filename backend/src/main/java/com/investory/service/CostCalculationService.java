package com.investory.service;

import com.investory.model.Holding;
import com.investory.model.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class CostCalculationService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public Holding rebuild(List<Transaction> transactions) {
        BigDecimal totalShares = ZERO;
        BigDecimal totalInvested = ZERO;
        BigDecimal avgCost = ZERO;

        for (Transaction t : transactions) {
            if ("BUY".equals(t.getType())) {
                BigDecimal cost = nz(t.getShares()).multiply(nz(t.getPrice())).add(nz(t.getFee()));
                BigDecimal newShares = totalShares.add(nz(t.getShares()));
                if (newShares.compareTo(ZERO) > 0) {
                    avgCost = totalInvested.add(cost).divide(newShares, 6, RoundingMode.HALF_UP);
                }
                totalShares = newShares;
                totalInvested = totalInvested.add(cost);
            } else if ("SELL".equals(t.getType())) {
                BigDecimal sellShares = nz(t.getShares());
                BigDecimal soldCost = totalShares.compareTo(ZERO) > 0
                        ? totalInvested.multiply(sellShares).divide(totalShares, 8, RoundingMode.HALF_UP)
                        : ZERO;
                if (sellShares.compareTo(totalShares) >= 0) {
                    soldCost = totalInvested;
                }
                totalShares = totalShares.subtract(sellShares);
                totalInvested = totalInvested.subtract(soldCost);
                if (totalShares.compareTo(ZERO) <= 0) {
                    totalShares = ZERO;
                    totalInvested = ZERO;
                    avgCost = ZERO;
                }
            }
        }

        Holding h = new Holding();
        h.setTotalShares(totalShares.setScale(4, RoundingMode.HALF_UP));
        h.setAvgCost(avgCost.setScale(4, RoundingMode.HALF_UP));
        h.setTotalInvested(totalInvested.setScale(4, RoundingMode.HALF_UP));
        h.setTotalDividends(ZERO);
        h.setDilutedCost(avgCost.setScale(4, RoundingMode.HALF_UP));
        return h;
    }

    public void applyDividends(Holding h, BigDecimal totalDividends) {
        if (totalDividends == null) totalDividends = ZERO;
        h.setTotalDividends(totalDividends.setScale(4, RoundingMode.HALF_UP));

        if (h.getTotalShares().compareTo(ZERO) > 0) {
            BigDecimal effectiveInvested = h.getTotalInvested().subtract(totalDividends);
            BigDecimal diluted = effectiveInvested.divide(h.getTotalShares(), 6, RoundingMode.HALF_UP);
            h.setDilutedCost(diluted.setScale(4, RoundingMode.HALF_UP));
        } else {
            h.setDilutedCost(ZERO);
        }
    }

    private BigDecimal nz(BigDecimal value) {
        return value != null ? value : ZERO;
    }
}
