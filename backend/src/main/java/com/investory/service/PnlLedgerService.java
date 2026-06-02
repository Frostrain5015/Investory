package com.investory.service;

import com.investory.dao.StockPriceDao;
import com.investory.model.DailyValue;
import com.investory.model.StockPrice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
public class PnlLedgerService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StockPriceDao stockPriceDao;

    public List<DailyValue> calculateDailyValues(long portfolioId, LocalDate fromDate, LocalDate toDate) {
        Ledger ledger = buildLedger(portfolioId, fromDate, toDate);
        List<DailyValue> values = new ArrayList<>();
        for (Map.Entry<LocalDate, DaySnapshot> entry : ledger.days.entrySet()) {
            LocalDate day = entry.getKey();
            if (day.isBefore(fromDate) || day.isAfter(toDate)) continue;
            DaySnapshot snapshot = entry.getValue();
            if (!snapshot.hasActivity) continue;
            DailyValue value = new DailyValue();
            value.setPortfolioId(portfolioId);
            value.setSnapshotDate(day);
            value.setTotalValue(scale2(snapshot.totalValue));
            value.setTotalCost(scale2(snapshot.totalCost));
            value.setDailyPnl(scale2(snapshot.dailyPnl));
            values.add(value);
        }
        return values;
    }

    public Map<String, Object> buildDetail(long portfolioId, String label, LocalDate startExclusive, LocalDate endInclusive) {
        Ledger ledger = buildLedger(portfolioId, startExclusive, endInclusive);
        DaySnapshot start = ledger.days.get(startExclusive);
        DaySnapshot end = ledger.days.get(endInclusive);
        if (start == null) start = DaySnapshot.empty();
        if (end == null) end = DaySnapshot.empty();

        List<Map<String, Object>> holdings = new ArrayList<>();
        Set<Long> stockIds = new TreeSet<>();
        stockIds.addAll(start.stockNet.keySet());
        stockIds.addAll(end.stockNet.keySet());
        stockIds.addAll(ledger.stockNames.keySet());

        BigDecimal totalPnl = ZERO;
        BigDecimal totalMv = ZERO;
        for (Long stockId : stockIds) {
            BigDecimal beginNet = start.stockNet.getOrDefault(stockId, ZERO);
            BigDecimal endNet = end.stockNet.getOrDefault(stockId, ZERO);
            BigDecimal pnl = endNet.subtract(beginNet);
            BigDecimal mv = end.marketValue.getOrDefault(stockId, ZERO);
            if (pnl.compareTo(ZERO) == 0 && mv.compareTo(ZERO) == 0 && !hasPeriodActivity(ledger, stockId, startExclusive, endInclusive)) {
                continue;
            }

            BigDecimal priceChange = priceChangePct(ledger, stockId, startExclusive, endInclusive);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stockName", ledger.stockNames.getOrDefault(stockId, "Unknown"));
            row.put("symbol", ledger.stockSymbols.getOrDefault(stockId, ""));
            row.put("pnl", scale2(pnl));
            row.put("priceChange", priceChange);
            row.put("marketValue", scale2(mv));
            holdings.add(row);
            totalPnl = totalPnl.add(pnl);
            totalMv = totalMv.add(mv);
        }

        if (totalMv.compareTo(ZERO) > 0) {
            for (Map<String, Object> row : holdings) {
                BigDecimal mv = (BigDecimal) row.get("marketValue");
                row.put("weightPct", mv.divide(totalMv, 4, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP));
            }
        }
        holdings.sort((a, b) -> ((BigDecimal) b.get("pnl")).abs().compareTo(((BigDecimal) a.get("pnl")).abs()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", label);
        result.put("totalPnl", scale2(totalPnl));
        result.put("holdings", holdings);
        result.put("transactions", periodEvents(portfolioId, startExclusive.plusDays(1), endInclusive));
        return result;
    }

    private Ledger buildLedger(long portfolioId, LocalDate requestedStart, LocalDate endDate) {
        Ledger ledger = new Ledger();
        if (requestedStart == null || endDate == null || endDate.isBefore(requestedStart)) return ledger;

        List<Tx> txs = loadTransactions(portfolioId, endDate);
        List<Div> divs = loadDividends(portfolioId, endDate);
        LocalDate firstDate = requestedStart;
        for (Tx tx : txs) if (tx.date.isBefore(firstDate)) firstDate = tx.date;
        for (Div div : divs) if (div.date.isBefore(firstDate)) firstDate = div.date;

        loadStockMetadata(ledger, txs, divs);
        Map<String, BigDecimal> rates = loadRates();
        Map<Long, NavigableMap<LocalDate, BigDecimal>> priceCache = loadPriceCache(ledger.stockNames.keySet(), firstDate, endDate);

        Map<Long, PositionState> states = new HashMap<>();
        BigDecimal transferCash = ZERO;
        BigDecimal previousExTransfer = null;
        int txIndex = 0;
        int divIndex = 0;

        for (LocalDate day = firstDate; !day.isAfter(endDate); day = day.plusDays(1)) {
            boolean dayActivity = false;
            while (txIndex < txs.size() && txs.get(txIndex).date.equals(day)) {
                Tx tx = txs.get(txIndex++);
                dayActivity = true;
                if ("TRANSFER_IN".equals(tx.type)) {
                    BigDecimal rate = rateForCurrency(tx.currency, rates);
                    transferCash = transferCash.add(tx.shares.multiply(rate));
                    continue;
                }
                if ("TRANSFER_OUT".equals(tx.type)) {
                    BigDecimal rate = rateForCurrency(tx.currency, rates);
                    transferCash = transferCash.subtract(tx.shares.multiply(rate));
                    continue;
                }
                if (tx.stockId == null) continue;
                BigDecimal rate = rateForStock(ledger, rates, tx.stockId);
                PositionState state = states.computeIfAbsent(tx.stockId, id -> new PositionState());
                if ("BUY".equals(tx.type)) {
                    BigDecimal cost = tx.shares.multiply(tx.price).add(tx.fee);
                    state.shares = state.shares.add(tx.shares);
                    state.costBasis = state.costBasis.add(cost);
                    state.cash = state.cash.subtract(cost);
                    if (tx.price.compareTo(ZERO) > 0) state.lastTradePrice = tx.price;
                } else if ("SELL".equals(tx.type)) {
                    BigDecimal proceeds = tx.shares.multiply(tx.price).subtract(tx.fee);
                    BigDecimal soldCost = state.costForSale(tx.shares);
                    state.shares = state.shares.subtract(tx.shares);
                    state.costBasis = state.costBasis.subtract(soldCost);
                    state.cash = state.cash.add(proceeds);
                    if (tx.price.compareTo(ZERO) > 0) state.lastTradePrice = tx.price;
                    if (state.shares.compareTo(ZERO) <= 0) {
                        state.shares = ZERO;
                        state.costBasis = ZERO;
                    }
                }
            }
            while (divIndex < divs.size() && divs.get(divIndex).date.equals(day)) {
                Div div = divs.get(divIndex++);
                dayActivity = true;
                PositionState state = states.computeIfAbsent(div.stockId, id -> new PositionState());
                BigDecimal rate = rateForStock(ledger, rates, div.stockId);
                state.dividends = state.dividends.add(div.amount.multiply(rate));
            }

            BigDecimal stockNetTotal = ZERO;
            BigDecimal marketTotal = ZERO;
            BigDecimal totalCost = ZERO;
            DaySnapshot snapshot = new DaySnapshot();
            snapshot.hasActivity = dayActivity || transferCash.compareTo(ZERO) != 0 || !states.isEmpty();

            for (Map.Entry<Long, PositionState> entry : states.entrySet()) {
                Long stockId = entry.getKey();
                PositionState state = entry.getValue();
                BigDecimal rate = rateForStock(ledger, rates, stockId);
                BigDecimal marketValue = ZERO;
                if (state.shares.compareTo(ZERO) > 0) {
                    BigDecimal price = closeOnOrBefore(priceCache.get(stockId), day);
                    if (price == null) price = state.lastTradePrice;
                    if (price != null) {
                        marketValue = price.multiply(state.shares).multiply(rate);
                    }
                    totalCost = totalCost.add(state.costBasis.multiply(rate));
                }
                BigDecimal stockNet = state.cash.multiply(rate).add(state.dividends).add(marketValue);
                if (stockNet.compareTo(ZERO) != 0 || marketValue.compareTo(ZERO) != 0 || state.shares.compareTo(ZERO) > 0) {
                    snapshot.stockNet.put(stockId, stockNet);
                    snapshot.marketValue.put(stockId, marketValue);
                }
                stockNetTotal = stockNetTotal.add(stockNet);
                marketTotal = marketTotal.add(marketValue);
            }

            snapshot.totalValueExTransfer = stockNetTotal;
            snapshot.totalValue = stockNetTotal.add(transferCash);
            snapshot.totalCost = totalCost;
            snapshot.dailyPnl = previousExTransfer == null ? stockNetTotal : stockNetTotal.subtract(previousExTransfer);
            snapshot.marketValueTotal = marketTotal;
            ledger.days.put(day, snapshot);
            previousExTransfer = stockNetTotal;
        }
        ledger.transactions = txs;
        ledger.dividends = divs;
        ledger.priceCache = priceCache;
        return ledger;
    }

    private List<Tx> loadTransactions(long portfolioId, LocalDate endDate) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, stock_id, type, shares, price, fee, trade_date, currency
            FROM transactions
            WHERE portfolio_id = ? AND trade_date <= ?
            ORDER BY trade_date, id
            """, portfolioId, Date.valueOf(endDate));
        List<Tx> txs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object stock = row.get("stock_id");
            txs.add(new Tx(
                    ((Number) row.get("id")).longValue(),
                    stock instanceof Number ? ((Number) stock).longValue() : null,
                    Objects.toString(row.get("type"), ""),
                    decimal(row.get("shares")),
                    decimal(row.get("price")),
                    decimal(row.get("fee")),
                    text(row.get("currency"), "CNY"),
                    ((Date) row.get("trade_date")).toLocalDate()
            ));
        }
        return txs;
    }

    private List<Div> loadDividends(long portfolioId, LocalDate endDate) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, stock_id, total_amount, record_date
            FROM dividends
            WHERE portfolio_id = ? AND record_date <= ?
            ORDER BY record_date, id
            """, portfolioId, Date.valueOf(endDate));
        List<Div> divs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            divs.add(new Div(
                    ((Number) row.get("id")).longValue(),
                    ((Number) row.get("stock_id")).longValue(),
                    decimal(row.get("total_amount")),
                    ((Date) row.get("record_date")).toLocalDate()
            ));
        }
        return divs;
    }

    private void loadStockMetadata(Ledger ledger, List<Tx> txs, List<Div> divs) {
        Set<Long> ids = new TreeSet<>();
        for (Tx tx : txs) if (tx.stockId != null) ids.add(tx.stockId);
        for (Div div : divs) ids.add(div.stockId);
        for (Long id : ids) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, symbol, name, currency FROM stocks WHERE id = ?", id);
            if (rows.isEmpty()) continue;
            Map<String, Object> row = rows.get(0);
            ledger.stockSymbols.put(id, Objects.toString(row.get("symbol"), ""));
            ledger.stockNames.put(id, Objects.toString(row.get("name"), "Unknown"));
            ledger.stockCurrencies.put(id, Objects.toString(row.get("currency"), "CNY"));
        }
    }

    private Map<Long, NavigableMap<LocalDate, BigDecimal>> loadPriceCache(Set<Long> stockIds, LocalDate from, LocalDate to) {
        Map<Long, NavigableMap<LocalDate, BigDecimal>> cache = new HashMap<>();
        for (Long stockId : stockIds) {
            NavigableMap<LocalDate, BigDecimal> prices = new TreeMap<>();
            for (StockPrice price : stockPriceDao.findRange(stockId, from, to)) {
                if (price.getClose() != null) prices.put(price.getTradeDate(), price.getClose());
            }
            cache.put(stockId, prices);
        }
        return cache;
    }

    private List<Map<String, Object>> periodEvents(long portfolioId, LocalDate start, LocalDate end) {
        List<Map<String, Object>> events = new ArrayList<>();
        events.addAll(jdbc.queryForList("""
            SELECT t.trade_date AS date, t.type, s.name AS stockName, t.shares, t.price
            FROM transactions t LEFT JOIN stocks s ON t.stock_id = s.id
            WHERE t.portfolio_id = ? AND t.trade_date BETWEEN ? AND ?
            ORDER BY t.trade_date, t.id
            """, portfolioId, Date.valueOf(start), Date.valueOf(end)));
        events.addAll(jdbc.queryForList("""
            SELECT d.record_date AS date, 'DIV' AS type, s.name AS stockName,
                   d.shares_held AS shares, d.amount_per_share AS price
            FROM dividends d LEFT JOIN stocks s ON d.stock_id = s.id
            WHERE d.portfolio_id = ? AND d.record_date BETWEEN ? AND ?
            ORDER BY d.record_date, d.id
            """, portfolioId, Date.valueOf(start), Date.valueOf(end)));
        events.sort(Comparator.comparing(e -> Objects.toString(e.get("date"), "")));
        return events;
    }

    private boolean hasPeriodActivity(Ledger ledger, Long stockId, LocalDate startExclusive, LocalDate endInclusive) {
        for (Tx tx : ledger.transactions) {
            if (Objects.equals(tx.stockId, stockId) && tx.date.isAfter(startExclusive) && !tx.date.isAfter(endInclusive)) return true;
        }
        for (Div div : ledger.dividends) {
            if (Objects.equals(div.stockId, stockId) && div.date.isAfter(startExclusive) && !div.date.isAfter(endInclusive)) return true;
        }
        return false;
    }

    private BigDecimal priceChangePct(Ledger ledger, Long stockId, LocalDate startExclusive, LocalDate endInclusive) {
        NavigableMap<LocalDate, BigDecimal> prices = ledger.priceCache.get(stockId);
        BigDecimal begin = closeOnOrBefore(prices, startExclusive);
        BigDecimal end = closeOnOrBefore(prices, endInclusive);
        if ((begin == null || begin.compareTo(ZERO) == 0)) {
            for (Tx tx : ledger.transactions) {
                if (Objects.equals(tx.stockId, stockId) && tx.date.isAfter(startExclusive) && !tx.date.isAfter(endInclusive) && tx.price.compareTo(ZERO) > 0) {
                    begin = tx.price;
                    break;
                }
            }
        }
        if (end == null || end.compareTo(ZERO) == 0) {
            for (int i = ledger.transactions.size() - 1; i >= 0; i--) {
                Tx tx = ledger.transactions.get(i);
                if (Objects.equals(tx.stockId, stockId) && !tx.date.isAfter(endInclusive) && tx.price.compareTo(ZERO) > 0) {
                    end = tx.price;
                    break;
                }
            }
        }
        if (begin == null || end == null || begin.compareTo(ZERO) == 0) return ZERO;
        return end.subtract(begin).divide(begin, 4, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, BigDecimal> loadRates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("CNY", BigDecimal.ONE);
        try {
            for (Map<String, Object> row : jdbc.queryForList("SELECT currency, rate FROM exchange_rates")) {
                String currency = Objects.toString(row.get("currency"), "CNY");
                BigDecimal rate = decimal(row.get("rate"));
                if (rate.compareTo(ZERO) > 0) rates.put(currency, BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
            }
        } catch (Exception ignored) {
        }
        return rates;
    }

    private BigDecimal rateForStock(Ledger ledger, Map<String, BigDecimal> rates, Long stockId) {
        if (stockId == null) return BigDecimal.ONE;
        return rates.getOrDefault(ledger.stockCurrencies.getOrDefault(stockId, "CNY"), BigDecimal.ONE);
    }

    private BigDecimal rateForCurrency(String currency, Map<String, BigDecimal> rates) {
        return rates.getOrDefault(currency != null ? currency : "CNY", BigDecimal.ONE);
    }

    private BigDecimal closeOnOrBefore(NavigableMap<LocalDate, BigDecimal> prices, LocalDate day) {
        if (prices == null || prices.isEmpty()) return null;
        Map.Entry<LocalDate, BigDecimal> entry = prices.floorEntry(day);
        return entry != null ? entry.getValue() : null;
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

    private BigDecimal scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record Tx(long id, Long stockId, String type, BigDecimal shares, BigDecimal price, BigDecimal fee, String currency, LocalDate date) {}
    private record Div(long id, Long stockId, BigDecimal amount, LocalDate date) {}

    private static final class PositionState {
        BigDecimal shares = ZERO;
        BigDecimal costBasis = ZERO;
        BigDecimal cash = ZERO;
        BigDecimal dividends = ZERO;
        BigDecimal lastTradePrice;

        BigDecimal costForSale(BigDecimal sellShares) {
            if (shares.compareTo(ZERO) <= 0 || costBasis.compareTo(ZERO) <= 0) return ZERO;
            if (sellShares.compareTo(shares) >= 0) return costBasis;
            return costBasis.multiply(sellShares).divide(shares, 8, RoundingMode.HALF_UP);
        }
    }

    private static final class DaySnapshot {
        boolean hasActivity;
        BigDecimal totalValue = ZERO;
        BigDecimal totalValueExTransfer = ZERO;
        BigDecimal totalCost = ZERO;
        BigDecimal dailyPnl = ZERO;
        BigDecimal marketValueTotal = ZERO;
        Map<Long, BigDecimal> stockNet = new LinkedHashMap<>();
        Map<Long, BigDecimal> marketValue = new LinkedHashMap<>();

        static DaySnapshot empty() {
            return new DaySnapshot();
        }
    }

    private static final class Ledger {
        NavigableMap<LocalDate, DaySnapshot> days = new TreeMap<>();
        List<Tx> transactions = List.of();
        List<Div> dividends = List.of();
        Map<Long, NavigableMap<LocalDate, BigDecimal>> priceCache = Map.of();
        Map<Long, String> stockSymbols = new HashMap<>();
        Map<Long, String> stockNames = new HashMap<>();
        Map<Long, String> stockCurrencies = new HashMap<>();
    }
}
