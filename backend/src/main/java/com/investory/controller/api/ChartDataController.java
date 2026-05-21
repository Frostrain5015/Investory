package com.investory.controller.api;

import com.investory.dao.DividendDao;
import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.dao.HoldingDao;
import com.investory.model.*;
import com.investory.service.HoldingService;
import com.investory.service.PortfolioAnalysisService;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ChartDataController {

    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private HoldingService holdingService;
    @Autowired private PortfolioAnalysisService analysisService;
    @Autowired private JdbcTemplate jdbc;

    @GetMapping(value = "/chart", produces = MediaType.APPLICATION_JSON_VALUE)
    public String chart(@RequestParam(required = false) String type,
                        @RequestParam(required = false) String symbol,
                        @RequestParam(required = false) Integer days,
                        @RequestParam(required = false) String start,
                        @RequestParam(required = false) String end,
                        @RequestParam(required = false) Long portfolioId,
                        @RequestParam(required = false) Integer year,
                        HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            resp.setStatus(401);
            return "{\"error\":\"unauthorized\"}";
        }
        long pid = resolvePortfolioId(portfolioId, session);
        try {
            return switch (type != null ? type : "") {
                case "price"             -> priceData(symbol, days != null ? days : 180, start, end);
                case "allocation"        -> allocationData(pid);
                case "pnl_rank"          -> pnlRankData(pid);
                case "pnl_calendar"      -> pnlCalendarData(pid, year);
                case "cumulative_return" -> cumulativeReturnData(pid, days != null ? days : 365, start, end);
                default -> "{\"error\":\"unknown type\"}";
            };
        } catch (Exception e) {
            resp.setStatus(500);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String priceData(String symbol, int days, String startStr, String endStr) {
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return "[]";
        LocalDate to, from;
        if (startStr != null && endStr != null) {
            from = LocalDate.parse(startStr);
            to   = LocalDate.parse(endStr);
        } else if (days == 0) {
            to = LocalDate.now();
            java.sql.Date earliest = jdbc.queryForObject(
                "SELECT MIN(trade_date) FROM stock_prices WHERE stock_id = ?",
                java.sql.Date.class, stock.getId());
            from = earliest != null ? earliest.toLocalDate() : to.minusYears(1);
        } else {
            to   = LocalDate.now();
            from = to.minusDays(days);
        }
        List<StockPrice> prices = stockPriceDao.findRange(stock.getId(), from, to);
        List<Map<String, Object>> result = new ArrayList<>();
        for (StockPrice p : prices) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date",   p.getTradeDate().toString());
            m.put("open",   p.getOpen());
            m.put("close",  p.getClose());
            m.put("high",   p.getHigh());
            m.put("low",    p.getLow());
            m.put("volume", p.getVolume());
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }

    private String allocationData(long portfolioId) {
        List<HoldingSnapshot> snapshots = holdingService.getSnapshots(portfolioId);
        // Group by stock, sum market values (already in CNY for A-shares, need conversion for others)
        List<Map<String, Object>> result = new ArrayList<>();
        for (HoldingSnapshot s : snapshots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",   s.getStockName());
            m.put("symbol", s.getStockSymbol());
            m.put("value",  s.getMarketValue());
            m.put("currency", s.getCurrency() != null ? s.getCurrency() : "CNY");
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }

    private String pnlRankData(long portfolioId) {
        List<HoldingSnapshot> snapshots = holdingService.getSnapshots(portfolioId);
        snapshots.sort(Comparator.comparing(HoldingSnapshot::getUnrealizedPnl));
        List<Map<String, Object>> result = new ArrayList<>();
        for (HoldingSnapshot s : snapshots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",   s.getStockName());
            m.put("symbol", s.getStockSymbol());
            m.put("pnl",    s.getUnrealizedPnl());
            m.put("pnlPct", s.getUnrealizedPnlPct());
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }

    private String pnlCalendarData(long portfolioId, Integer year) {
        int y = year != null ? year : LocalDate.now().getYear();
        LocalDate from = LocalDate.of(y, 1, 1);
        LocalDate to   = LocalDate.of(y, 12, 31);
        // Find previous year's last value for continuity
        DailyValue lastBefore = analysisService.getDailyValues(portfolioId, from.minusDays(1), from.minusDays(1))
            .stream().findFirst().orElse(null);
        List<DailyValue> values = analysisService.getDailyValues(portfolioId, from, to);
        List<Object[]> result = new ArrayList<>();
        BigDecimal prevValue = lastBefore != null ? lastBefore.getTotalValue() : null;
        for (DailyValue v : values) {
            BigDecimal dailyPnl;
            if (prevValue != null) {
                dailyPnl = v.getTotalValue().subtract(prevValue);
            } else {
                dailyPnl = BigDecimal.ZERO;
            }
            prevValue = v.getTotalValue();
            result.add(new Object[]{ v.getSnapshotDate().toString(), dailyPnl, v.getTotalValue() });
        }
        return JsonUtil.toJson(result);
    }

    private String cumulativeReturnData(long portfolioId, int days, String startStr, String endStr) {
        LocalDate to, from;
        if (startStr != null && endStr != null) {
            from = LocalDate.parse(startStr);
            to   = LocalDate.parse(endStr);
        } else if (days == 0) {
            to   = LocalDate.now();
            from = LocalDate.of(1990, 1, 1); // firstTx guard below will tighten this
        } else {
            to   = LocalDate.now();
            from = to.minusDays(days);
        }
        List<Map<String, Object>> result = new ArrayList<>();

        // Don't show curve before first transaction
        java.sql.Date firstTxDate = jdbc.queryForObject(
            "SELECT MIN(trade_date) FROM transactions WHERE portfolio_id=? AND type IN ('BUY','SELL','TRANSFER_IN','TRANSFER_OUT')",
            java.sql.Date.class, portfolioId);
        if (firstTxDate != null) {
            LocalDate firstTx = firstTxDate.toLocalDate();
            if (firstTx.isAfter(from)) from = firstTx;
        }

        // Exchange rates for CNY conversion
        Map<String, BigDecimal> toCny = new HashMap<>();
        toCny.put("CNY", BigDecimal.ONE);
        try {
            List<Map<String, Object>> rates = jdbc.queryForList("SELECT currency, rate FROM exchange_rates");
            for (Map<String, Object> r : rates) {
                String curr = (String) r.get("currency");
                BigDecimal rate = (BigDecimal) r.get("rate");
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0)
                    toCny.put(curr, BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
            }
        } catch (Exception ignored) {}

        // Pre-compute time-based holdings: shares per stock per transaction date
        List<Map<String, Object>> allTxns = jdbc.queryForList(
            "SELECT stock_id, type, shares, price, fee, trade_date, currency FROM transactions WHERE portfolio_id=? AND type IN ('BUY','SELL') ORDER BY trade_date",
            portfolioId);
        // Stock currency lookup
        Map<Long, String> stockCurrency = new HashMap<>();
        Map<Long, BigDecimal> stockRate = new HashMap<>();
        for (Map<String, Object> t : allTxns) {
            long sid = ((Number) t.get("stock_id")).longValue();
            if (!stockCurrency.containsKey(sid)) {
                Stock s = stockDao.findById(sid);
                String cur = s != null ? s.getCurrency() : "CNY";
                stockCurrency.put(sid, cur);
                stockRate.put(sid, toCny.getOrDefault(cur, BigDecimal.ONE));
            }
        }

        // Build daily holdings map
        TreeMap<LocalDate, Map<Long, BigDecimal[]>> holdingsByDate = new TreeMap<>();
        Map<Long, BigDecimal> cumShares = new HashMap<>();
        Map<Long, BigDecimal> cumInvested = new HashMap<>();
        for (Map<String, Object> t : allTxns) {
            long sid = ((Number) t.get("stock_id")).longValue();
            String type = (String) t.get("type");
            BigDecimal shares = t.get("shares") != null ? (BigDecimal) t.get("shares") : BigDecimal.ZERO;
            BigDecimal price = t.get("price") != null ? (BigDecimal) t.get("price") : BigDecimal.ZERO;
            BigDecimal fee = t.get("fee") != null ? (BigDecimal) t.get("fee") : BigDecimal.ZERO;
            LocalDate d = ((java.sql.Date) t.get("trade_date")).toLocalDate();

            BigDecimal old = cumShares.getOrDefault(sid, BigDecimal.ZERO);
            BigDecimal newShares = "BUY".equals(type) ? old.add(shares) : old.subtract(shares);
            cumShares.put(sid, newShares);

            BigDecimal inv = cumInvested.getOrDefault(sid, BigDecimal.ZERO);
            if ("BUY".equals(type)) inv = inv.add(shares.multiply(price).add(fee));
            else inv = inv.subtract(shares.multiply(price).subtract(fee));
            cumInvested.put(sid, inv);

            Map<Long, BigDecimal[]> dayMap = new LinkedHashMap<>();
            for (Map.Entry<Long, BigDecimal> e : cumShares.entrySet()) {
                long s = e.getKey();
                BigDecimal sh = e.getValue();
                if (sh.compareTo(BigDecimal.ZERO) <= 0) continue;
                dayMap.put(s, new BigDecimal[]{sh, cumInvested.getOrDefault(s, BigDecimal.ZERO)});
            }
            holdingsByDate.put(d, dayMap);
        }

        // Cash with transfers (for total value) and without (for P&L)
        Map<LocalDate, BigDecimal> cashWithTransfers = computeCashMap(portfolioId, from, to, toCny, true);
        Map<LocalDate, BigDecimal> cashNoTransfers = computeCashMap(portfolioId, from, to, toCny, false);

        // Dividend schedule
        Map<LocalDate, BigDecimal> divByDate = new HashMap<>();
        for (Dividend d : dividendDao.findByPortfolio(portfolioId)) {
            divByDate.merge(d.getRecordDate(), d.getTotalAmount(), BigDecimal::add);
        }

        // Pre-load all prices for every stock in the portfolio over [from, to] — one query per stock
        // instead of one query per (stock × day), reducing N×M SQL calls to N calls.
        Set<Long> allStockIds = new HashSet<>();
        for (Map<Long, BigDecimal[]> m : holdingsByDate.values()) allStockIds.addAll(m.keySet());
        Map<Long, Map<LocalDate, BigDecimal>> priceCache = new HashMap<>();
        for (Long sid : allStockIds) {
            Map<LocalDate, BigDecimal> dateMap = new HashMap<>();
            for (StockPrice p : stockPriceDao.findRange(sid, from, to))
                dateMap.put(p.getTradeDate(), p.getClose());
            priceCache.put(sid, dateMap);
        }

        // Walk day by day with time-based holdings, exclude transfers from P&L
        Map<Long, BigDecimal> lastPrice = new HashMap<>();
        Map<Long, BigDecimal[]> currentHolding = null;
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            if (holdingsByDate.containsKey(cursor)) currentHolding = holdingsByDate.get(cursor);
            if (holdingsByDate.floorEntry(cursor) != null) currentHolding = holdingsByDate.floorEntry(cursor).getValue();
            if (currentHolding == null || currentHolding.isEmpty()) { cursor = cursor.plusDays(1); continue; }

            BigDecimal stockValue = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;
            boolean hasPrice = false;

            for (Map.Entry<Long, BigDecimal[]> e : currentHolding.entrySet()) {
                long sid = e.getKey();
                BigDecimal[] shInv = e.getValue();
                BigDecimal sh = shInv[0] != null ? shInv[0] : BigDecimal.ZERO;
                BigDecimal inv = shInv[1] != null ? shInv[1] : BigDecimal.ZERO;
                BigDecimal r = stockRate.containsKey(sid) ? stockRate.get(sid) : BigDecimal.ONE;

                BigDecimal close = priceCache.getOrDefault(sid, Collections.emptyMap()).get(cursor);
                if (close == null) close = lastPrice.get(sid);
                if (close != null) {
                    lastPrice.put(sid, close);
                    stockValue = stockValue.add(close.multiply(sh).multiply(r));
                    if (inv != null) totalCost = totalCost.add(inv.multiply(r));
                    hasPrice = true;
                }
            }

            if (hasPrice) {
                BigDecimal cashFull = cashWithTransfers.getOrDefault(cursor, BigDecimal.ZERO);
                BigDecimal cashExTf = cashNoTransfers.getOrDefault(cursor, BigDecimal.ZERO);
                BigDecimal div = divByDate.getOrDefault(cursor, BigDecimal.ZERO);
                BigDecimal totalToday = stockValue.add(cashFull).add(div);
                BigDecimal totalExTransfer = stockValue.add(cashExTf).add(div);

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", cursor.toString());
                m.put("value", totalToday);  // Full total assets including transfers
                m.put("valueExTransfer", totalExTransfer);  // Market-only (for P&L calc)
                m.put("return", totalCost.compareTo(BigDecimal.ZERO) > 0
                        ? stockValue.add(div).subtract(totalCost).divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                        : BigDecimal.ZERO);
                result.add(m);
            }
            cursor = cursor.plusDays(1);
        }
        return JsonUtil.toJson(result);
    }

    private Map<LocalDate, BigDecimal> computeCashMap(long portfolioId, LocalDate from, LocalDate to,
                                                       Map<String, BigDecimal> toCny, boolean includeTransfers) {
        TreeMap<LocalDate, BigDecimal> map = new TreeMap<>();
        List<Map<String, Object>> txns = jdbc.queryForList(
            "SELECT trade_date, type, shares, price, currency, fee FROM transactions WHERE portfolio_id=? AND trade_date <= ? ORDER BY trade_date",
            portfolioId, to);
        BigDecimal cum = BigDecimal.ZERO;
        for (Map<String, Object> t : txns) {
            LocalDate d = ((java.sql.Date) t.get("trade_date")).toLocalDate();
            String type = (String) t.get("type");
            BigDecimal shares = t.get("shares") != null ? (BigDecimal) t.get("shares") : BigDecimal.ZERO;
            BigDecimal price = t.get("price") != null ? (BigDecimal) t.get("price") : BigDecimal.ZERO;
            BigDecimal fee = t.get("fee") != null ? (BigDecimal) t.get("fee") : BigDecimal.ZERO;
            if (shares == null) shares = BigDecimal.ZERO;
            String cur = (String) t.getOrDefault("currency", "CNY");
            BigDecimal r = toCny.getOrDefault(cur, BigDecimal.ONE);
            switch (type) {
                case "SELL": cum = cum.add(shares.multiply(price).subtract(fee).multiply(r)); break;
                case "BUY":  cum = cum.subtract(shares.multiply(price).add(fee).multiply(r)); break;
                case "TRANSFER_IN":  if (includeTransfers) cum = cum.add(shares.multiply(r)); break;
                case "TRANSFER_OUT": if (includeTransfers) cum = cum.subtract(shares.multiply(r)); break;
            }
            map.put(d, cum);
        }
        // Fill forward
        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        BigDecimal running = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (map.floorEntry(d) != null) running = map.floorEntry(d).getValue();
            result.put(d, running);
        }
        return result;
    }

    private long resolvePortfolioId(Long param, HttpSession session) {
        if (param != null) return param;
        Object id = session.getAttribute("portfolioId");
        return id != null ? (Long) id : 0L;
    }
}
