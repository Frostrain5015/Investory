package com.investory.service;

import com.investory.model.Holding;
import com.investory.model.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Core cost calculation engine.
 *
 * Moving weighted average cost:
 *   BUY:  avg_cost = (total_invested + new_cost) / (old_shares + new_shares)
 *   SELL: avg_cost unchanged; total_invested reduced by avg_cost * sold_shares
 *
 * Diluted cost (cash dividends only):
 *   diluted_cost = (total_invested - total_dividends) / total_shares
 */
public class CostCalculationService {

    private static final CostCalculationService INSTANCE = new CostCalculationService();
    public static CostCalculationService get() { return INSTANCE; }

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * Rebuild holding state from an ordered list of transactions (oldest first).
     * Does NOT include dividends — call applyDividends() afterwards.
     */
    public Holding rebuild(List<Transaction> transactions) {
        BigDecimal totalShares   = ZERO;
        BigDecimal totalInvested = ZERO;
        BigDecimal avgCost       = ZERO;

        for (Transaction t : transactions) {
            if ("BUY".equals(t.getType())) {
                BigDecimal cost = t.getShares().multiply(t.getPrice()).add(t.getFee());
                BigDecimal newShares = totalShares.add(t.getShares());
                if (newShares.compareTo(ZERO) > 0) {
                    avgCost = totalInvested.add(cost).divide(newShares, 6, RoundingMode.HALF_UP);
                }
                totalShares   = newShares;
                totalInvested = totalInvested.add(cost);

            } else if ("SELL".equals(t.getType())) {
                // Reduce shares; reduce invested proportionally at avg_cost
                BigDecimal soldCost = avgCost.multiply(t.getShares());
                totalShares   = totalShares.subtract(t.getShares());
                totalInvested = totalInvested.subtract(soldCost);
                if (totalShares.compareTo(ZERO) <= 0) {
                    totalShares   = ZERO;
                    totalInvested = ZERO;
                    avgCost       = ZERO;
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

    /** Apply total cash dividends to update diluted_cost. */
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
}
