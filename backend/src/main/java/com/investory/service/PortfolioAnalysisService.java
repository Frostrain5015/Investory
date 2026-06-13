package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.model.DailyValue;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

public class PortfolioAnalysisService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final DailyPortfolioValueDao dailyPortfolioValueDao;

    public PortfolioAnalysisService() {
        this.dailyPortfolioValueDao = AppContext.get(DailyPortfolioValueDao.class);
    }

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

        String txSql = """
            SELECT t.stock_id, t.type, t.shares, t.price, t.fee,
                   COALESCE(s.currency, 'CNY') AS currency
            FROM transactions t LEFT JOIN stocks s ON t.stock_id = s.id
            WHERE t.portfolio_id = ? AND t.type IN ('BUY', 'SELL')
            ORDER BY t.stock_id, t.trade_date, t.id
            """;
        List<Map<String, Object>> rows = query(txSql, portfolioId);
        for (Map<String, Object> row : rows) {
            Object stockIdValue = row.get("stock_id");
            if (!(stockIdValue instanceof Number stockNumber)) continue;
            long stockId = stockNumber.longValue();
            PositionState state = positions.computeIfAbsent(stockId, id -> new PositionState());
            String currency = text(row.get("currency"), "CNY");
            BigDecimal shares = decimal(row.get("shares"));
            BigDecimal price = decimal(row.get("price"));
            BigDecimal fee = decimal(row.get("fee"));

            if ("BUY".equals(row.get("type"))) {
                BigDecimal cost = shares.multiply(price).add(fee);
                state.shares = state.shares.add(shares);
                state.costBasis = state.costBasis.add(cost);
                state.currency = currency;
            } else if ("SELL".equals(row.get("type"))) {
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

        String divSql = """
            SELECT d.total_amount, COALESCE(s.currency, 'CNY') AS currency
            FROM dividends d LEFT JOIN stocks s ON d.stock_id = s.id
            WHERE d.portfolio_id = ?
            """;
        List<Map<String, Object>> dividends = query(divSql, portfolioId);
        for (Map<String, Object> row : dividends) {
            String currency = text(row.get("currency"), "CNY");
            realized = realized.add(decimal(row.get("total_amount")).multiply(rateFor(currency, rates)));
        }

        return realized.setScale(2, RoundingMode.HALF_UP);
    }

    public List<Map<String, Object>> getClosedPositions(long portfolioId) {
        Map<String, BigDecimal> rates = loadRates();
        Map<Long, ClosedPositionState> states = new LinkedHashMap<>();

        String txSql = """
            SELECT t.stock_id, t.type, t.shares, t.price, t.fee,
                   s.symbol, s.name, s.market, COALESCE(s.currency, 'CNY') AS currency
            FROM transactions t JOIN stocks s ON t.stock_id = s.id
            WHERE t.portfolio_id = ? AND t.type IN ('BUY', 'SELL')
            ORDER BY t.stock_id, t.trade_date, t.id
            """;
        List<Map<String, Object>> txRows = query(txSql, portfolioId);
        for (Map<String, Object> row : txRows) {
            long stockId = ((Number) row.get("stock_id")).longValue();
            ClosedPositionState state = states.computeIfAbsent(stockId, id -> new ClosedPositionState());
            state.stockId = stockId;
            state.symbol = text(row.get("symbol"), "");
            state.name = text(row.get("name"), "");
            state.market = text(row.get("market"), "");
            state.currency = text(row.get("currency"), "CNY");
            BigDecimal rate = rateFor(state.currency, rates);
            BigDecimal shares = decimal(row.get("shares"));
            BigDecimal price = decimal(row.get("price"));
            BigDecimal fee = decimal(row.get("fee"));

            if ("BUY".equals(row.get("type"))) {
                BigDecimal cost = shares.multiply(price).add(fee);
                state.shares = state.shares.add(shares);
                state.costBasis = state.costBasis.add(cost);
                state.totalBought = state.totalBought.add(shares);
                state.buyCost = state.buyCost.add(cost.multiply(rate));
            } else if ("SELL".equals(row.get("type"))) {
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

        String divSql = """
            SELECT d.stock_id, d.total_amount, COALESCE(s.currency, 'CNY') AS currency
            FROM dividends d JOIN stocks s ON d.stock_id = s.id
            WHERE d.portfolio_id = ?
            """;
        List<Map<String, Object>> divRows = query(divSql, portfolioId);
        for (Map<String, Object> row : divRows) {
            long stockId = ((Number) row.get("stock_id")).longValue();
            ClosedPositionState state = states.get(stockId);
            if (state == null) continue;
            BigDecimal amount = decimal(row.get("total_amount")).multiply(rateFor(text(row.get("currency"), "CNY"), rates));
            state.dividends = state.dividends.add(amount);
            state.realizedPnl = state.realizedPnl.add(amount);
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
        try {
            List<Map<String, Object>> rows = query("SELECT currency, rate FROM exchange_rates");
            for (Map<String, Object> row : rows) {
                String currency = text(row.get("currency"), "CNY");
                BigDecimal rate = decimal(row.get("rate"));
                if (rate.compareTo(ZERO) > 0) rates.put(currency, BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
            }
        } catch (Exception ignored) {}
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

    // ── JDBC helper ─────────────────────────────────────────────────────

    private List<Map<String, Object>> query(String sql, Object... params) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof LocalDate) {
                    ps.setDate(i + 1, Date.valueOf((LocalDate) params[i]));
                } else {
                    ps.setObject(i + 1, params[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String col = rs.getMetaData().getColumnLabel(i);
                        Object val = rs.getObject(i);
                        if (val instanceof java.sql.Date) {
                            val = ((java.sql.Date) val).toLocalDate();
                        }
                        row.put(col, val);
                    }
                    result.add(row);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
        return result;
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
