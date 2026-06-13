package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.model.DailyValue;
import com.investory.model.HoldingSnapshot;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;

public class PortfolioAnalysisService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final DailyPortfolioValueDao dailyPortfolioValueDao = AppContext.get(DailyPortfolioValueDao.class);

    public BigDecimal totalMarketValue(List<HoldingSnapshot> snapshots) {
        BigDecimal total = ZERO;
        for (HoldingSnapshot snapshot : snapshots) total = total.add(nz(snapshot.getMarketValue()));
        return total;
    }

    public BigDecimal totalDividends(List<HoldingSnapshot> snapshots) {
        BigDecimal total = ZERO;
        for (HoldingSnapshot snapshot : snapshots) total = total.add(nz(snapshot.getTotalDividends()));
        return total;
    }

    public BigDecimal totalInvested(List<HoldingSnapshot> snapshots) {
        BigDecimal total = ZERO;
        for (HoldingSnapshot snapshot : snapshots) total = total.add(nz(snapshot.getTotalInvested()));
        return total;
    }

    public BigDecimal totalUnrealizedPnl(List<HoldingSnapshot> snapshots) {
        BigDecimal total = ZERO;
        for (HoldingSnapshot snapshot : snapshots) total = total.add(nz(snapshot.getUnrealizedPnl()));
        return total;
    }

    public BigDecimal overallReturnPct(List<HoldingSnapshot> snapshots) {
        BigDecimal invested = totalInvested(snapshots);
        if (invested.compareTo(ZERO) == 0) return ZERO;
        return totalUnrealizedPnl(snapshots)
                .divide(invested, 4, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public List<DailyValue> getDailyValues(long portfolioId, LocalDate from, LocalDate to) {
        return dailyPortfolioValueDao.findRange(portfolioId, from, to);
    }

    public DailyValue getTodayValue(long portfolioId) {
        return dailyPortfolioValueDao.findLatest(portfolioId);
    }

    public BigDecimal cumulativeReturnRate(BigDecimal cumulativePnl, BigDecimal totalInvested) {
        if (totalInvested.compareTo(ZERO) == 0) return ZERO;
        return cumulativePnl.divide(totalInvested, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal holdingReturnRate(BigDecimal totalMarketValue, BigDecimal totalInvested, BigDecimal totalDividends) {
        if (totalInvested.compareTo(ZERO) == 0) return ZERO;
        return totalMarketValue.add(totalDividends).subtract(totalInvested)
                .divide(totalInvested, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal totalRealizedPnl(long portfolioId) {
        Map<String, BigDecimal> rates = loadRates();
        Map<Long, PositionState> positions = new HashMap<>();
        BigDecimal realized = ZERO;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT t.stock_id, t.type, t.shares, t.price, t.fee,
                       COALESCE(s.currency, 'CNY') AS currency
                FROM transactions t LEFT JOIN stocks s ON t.stock_id = s.id
                WHERE t.portfolio_id = ? AND t.type IN ('BUY', 'SELL')
                ORDER BY t.stock_id, t.trade_date, t.id
                """)) {
            ps.setLong(1, portfolioId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long stockId = rs.getLong("stock_id");
                    PositionState state = positions.computeIfAbsent(stockId, id -> new PositionState());
                    String currency = text(rs.getString("currency"), "CNY");
                    BigDecimal shares = decimal(rs.getObject("shares"));
                    BigDecimal price = decimal(rs.getObject("price"));
                    BigDecimal fee = decimal(rs.getObject("fee"));

                    if ("BUY".equals(rs.getString("type"))) {
                        BigDecimal cost = shares.multiply(price).add(fee);
                        state.shares = state.shares.add(shares);
                        state.costBasis = state.costBasis.add(cost);
                        state.currency = currency;
                    } else if ("SELL".equals(rs.getString("type"))) {
                        BigDecimal proceeds = shares.multiply(price).subtract(fee);
                        BigDecimal soldCost = state.costForSale(shares);
                        realized = realized.add(proceeds.subtract(soldCost).multiply(rateFor(currency, rates)));
                        state.shares = state.shares.subtract(shares);
                        state.costBasis = state.costBasis.subtract(soldCost);
                        state.currency = currency;
                        if (state.shares.compareTo(ZERO) <= 0) {
                            state.shares = ZERO;
                            state.costBasis = ZERO;
                        }
                    }
                }
            }

            try (PreparedStatement ps2 = conn.prepareStatement("""
                SELECT d.total_amount, COALESCE(s.currency, 'CNY') AS currency
                FROM dividends d LEFT JOIN stocks s ON d.stock_id = s.id
                WHERE d.portfolio_id = ?
                """)) {
                ps2.setLong(1, portfolioId);
                try (ResultSet rs = ps2.executeQuery()) {
                    while (rs.next()) {
                        String currency = text(rs.getString("currency"), "CNY");
                        realized = realized.add(decimal(rs.getObject("total_amount")).multiply(rateFor(currency, rates)));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate realized PnL", e);
        }

        return realized.setScale(2, RoundingMode.HALF_UP);
    }

    public List<Map<String, Object>> getClosedPositions(long portfolioId) {
        Map<String, BigDecimal> rates = loadRates();
        Map<Long, ClosedPositionState> states = new LinkedHashMap<>();

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT t.stock_id, t.type, t.shares, t.price, t.fee,
                       s.symbol, s.name, s.market, COALESCE(s.currency, 'CNY') AS currency
                FROM transactions t JOIN stocks s ON t.stock_id = s.id
                WHERE t.portfolio_id = ? AND t.type IN ('BUY', 'SELL')
                ORDER BY t.stock_id, t.trade_date, t.id
                """)) {
            ps.setLong(1, portfolioId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long stockId = rs.getLong("stock_id");
                    ClosedPositionState state = states.computeIfAbsent(stockId, id -> new ClosedPositionState());
                    state.stockId = stockId;
                    state.symbol = text(rs.getString("symbol"), "");
                    state.name = text(rs.getString("name"), "");
                    state.market = text(rs.getString("market"), "");
                    state.currency = text(rs.getString("currency"), "CNY");
                    BigDecimal rate = rateFor(state.currency, rates);
                    BigDecimal shares = decimal(rs.getObject("shares"));
                    BigDecimal price = decimal(rs.getObject("price"));
                    BigDecimal fee = decimal(rs.getObject("fee"));

                    if ("BUY".equals(rs.getString("type"))) {
                        BigDecimal cost = shares.multiply(price).add(fee);
                        state.shares = state.shares.add(shares);
                        state.costBasis = state.costBasis.add(cost);
                        state.totalBought = state.totalBought.add(shares);
                        state.buyCost = state.buyCost.add(cost.multiply(rate));
                    } else if ("SELL".equals(rs.getString("type"))) {
                        BigDecimal proceeds = shares.multiply(price).subtract(fee);
                        BigDecimal soldCost = state.costForSale(shares);
                        state.shares = state.shares.subtract(shares);
                        state.costBasis = state.costBasis.subtract(soldCost);
                        state.totalSold = state.totalSold.add(shares);
                        state.sellProceeds = state.sellProceeds.add(proceeds.multiply(rate));
                        state.realizedPnl = state.realizedPnl.add(proceeds.subtract(soldCost).multiply(rate));
                        if (state.shares.compareTo(ZERO) <= 0) {
                            state.shares = ZERO;
                            state.costBasis = ZERO;
                        }
                    }
                }
            }

            try (PreparedStatement ps2 = conn.prepareStatement("""
                SELECT d.stock_id, d.total_amount, COALESCE(s.currency, 'CNY') AS currency
                FROM dividends d JOIN stocks s ON d.stock_id = s.id
                WHERE d.portfolio_id = ?
                """)) {
                ps2.setLong(1, portfolioId);
                try (ResultSet rs = ps2.executeQuery()) {
                    while (rs.next()) {
                        long stockId = rs.getLong("stock_id");
                        ClosedPositionState state = states.get(stockId);
                        if (state == null) continue;
                        BigDecimal amount = decimal(rs.getObject("total_amount")).multiply(rateFor(text(rs.getString("currency"), "CNY"), rates));
                        state.dividends = state.dividends.add(amount);
                        state.realizedPnl = state.realizedPnl.add(amount);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get closed positions", e);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ClosedPositionState state : states.values()) {
            if (state.totalBought.compareTo(ZERO) <= 0 || state.totalSold.compareTo(ZERO) <= 0 || state.shares.compareTo(ZERO) != 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stock_id", state.stockId);
            row.put("symbol", state.symbol);
            row.put("name", state.name);
            row.put("market", state.market);
            row.put("total_bought", state.totalBought.setScale(4, RoundingMode.HALF_UP));
            row.put("total_sold", state.totalSold.setScale(4, RoundingMode.HALF_UP));
            row.put("buy_cost", state.buyCost.setScale(2, RoundingMode.HALF_UP));
            row.put("sell_proceeds", state.sellProceeds.setScale(2, RoundingMode.HALF_UP));
            row.put("dividends", state.dividends.setScale(2, RoundingMode.HALF_UP));
            row.put("realizedPnl", state.realizedPnl.setScale(2, RoundingMode.HALF_UP));
            result.add(row);
        }
        return result;
    }

    private Map<String, BigDecimal> loadRates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("CNY", BigDecimal.ONE);
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT currency, rate FROM exchange_rates");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String currency = text(rs.getString("currency"), "CNY");
                BigDecimal rate = decimal(rs.getObject("rate"));
                if (rate.compareTo(ZERO) > 0) rates.put(currency, BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
            }
        } catch (Exception ignored) {
        }
        return rates;
    }

    private BigDecimal rateFor(String currency, Map<String, BigDecimal> rates) {
        return rates.getOrDefault(currency, BigDecimal.ONE);
    }

    private BigDecimal nz(BigDecimal value) {
        return value != null ? value : ZERO;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(value.toString());
    }

    private String text(Object value, String fallback) {
        if (value == null) return fallback;
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private static final class PositionState {
        BigDecimal shares = ZERO;
        BigDecimal costBasis = ZERO;
        String currency = "CNY";

        BigDecimal costForSale(BigDecimal sellShares) {
            if (shares.compareTo(ZERO) <= 0 || costBasis.compareTo(ZERO) <= 0) return ZERO;
            if (sellShares.compareTo(shares) >= 0) return costBasis;
            return costBasis.multiply(sellShares).divide(shares, 8, RoundingMode.HALF_UP);
        }
    }

    private static final class ClosedPositionState {
        long stockId;
        String symbol = "";
        String name = "";
        String market = "";
        String currency = "CNY";
        BigDecimal shares = ZERO;
        BigDecimal costBasis = ZERO;
        BigDecimal totalBought = ZERO;
        BigDecimal totalSold = ZERO;
        BigDecimal buyCost = ZERO;
        BigDecimal sellProceeds = ZERO;
        BigDecimal dividends = ZERO;
        BigDecimal realizedPnl = ZERO;

        BigDecimal costForSale(BigDecimal sellShares) {
            if (shares.compareTo(ZERO) <= 0 || costBasis.compareTo(ZERO) <= 0) return ZERO;
            if (sellShares.compareTo(shares) >= 0) return costBasis;
            return costBasis.multiply(sellShares).divide(shares, 8, RoundingMode.HALF_UP);
        }
    }
}
