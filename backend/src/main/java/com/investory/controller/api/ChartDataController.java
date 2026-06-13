package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.*;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;

public class ChartDataController {

    private final StockDao stockDao = AppContext.get(StockDao.class);
    private final StockPriceDao stockPriceDao = AppContext.get(StockPriceDao.class);
    private final DividendDao dividendDao = AppContext.get(DividendDao.class);
    private final HoldingDao holdingDao = AppContext.get(HoldingDao.class);
    private final HoldingService holdingService = AppContext.get(HoldingService.class);
    private final PortfolioAnalysisService analysisService = AppContext.get(PortfolioAnalysisService.class);
    private final PortfolioDao portfolioDao = AppContext.get(PortfolioDao.class);

    public void handleChart(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        String portfolioIdParam = req.getParameter("portfolioId");
        Long portfolioId = portfolioIdParam != null && !portfolioIdParam.isBlank() ? Long.parseLong(portfolioIdParam) : null;
        long pid = resolvePortfolioId(portfolioId, session);
        boolean portfolioScoped = !"price".equals(req.getParameter("type"));
        if (portfolioScoped && pid <= 0) {
            resp.setStatus(403);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"forbidden\"}");
            return;
        }

        String type = req.getParameter("type");
        String symbol = req.getParameter("symbol");
        String daysStr = req.getParameter("days");
        String start = req.getParameter("start");
        String end = req.getParameter("end");
        String yearStr = req.getParameter("year");
        String benchmark = req.getParameter("benchmark");
        int days = daysStr != null ? Integer.parseInt(daysStr) : 0;

        resp.setContentType("application/json;charset=UTF-8");
        switch (type != null ? type : "") {
            case "price" -> resp.getWriter().write(priceData(symbol, days != 0 ? days : 180, start, end, benchmark));
            case "allocation" -> resp.getWriter().write(allocationData(pid));
            case "pnl_rank" -> resp.getWriter().write(pnlRankData(pid));
            case "pnl_calendar" -> {
                Integer year = yearStr != null ? Integer.parseInt(yearStr) : null;
                resp.getWriter().write(pnlCalendarData(pid, year));
            }
            case "cumulative_return" -> resp.getWriter().write(cumulativeReturnData(pid, days != 0 ? days : 365, start, end));
            default -> resp.getWriter().write("{\"error\":\"unknown type\"}");
        }
    }

    private String priceData(String symbol, int days, String startStr, String endStr, String benchmarkSymbol) throws Exception {
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return "[]";
        LocalDate to, from;
        if (startStr != null && endStr != null) {
            from = LocalDate.parse(startStr);
            to   = LocalDate.parse(endStr);
        } else if (days == 0) {
            to = LocalDate.now();
            List<java.sql.Date> dates = jdbcQueryForListSingle(
                "SELECT MIN(trade_date) FROM stock_prices WHERE stock_id = ?",
                java.sql.Date.class, stock.getId());
            java.sql.Date earliest = dates.isEmpty() ? null : dates.get(0);
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

        List<Map<String, Object>> benchmarkData = null;
        if (benchmarkSymbol != null && !benchmarkSymbol.isBlank()) {
            Stock bmStock = stockDao.findBySymbol(benchmarkSymbol);
            if (bmStock != null) {
                List<StockPrice> bmPrices = stockPriceDao.findRange(bmStock.getId(), from, to);
                if (!bmPrices.isEmpty() && !prices.isEmpty()) {
                    Map<String, BigDecimal> stockCloses = new LinkedHashMap<>();
                    for (StockPrice p : prices) stockCloses.put(p.getTradeDate().toString(), p.getClose());
                    Map<String, BigDecimal> bmCloses = new LinkedHashMap<>();
                    for (StockPrice p : bmPrices) bmCloses.put(p.getTradeDate().toString(), p.getClose());
                    BigDecimal stockBase = null, bmBase = null;
                    benchmarkData = new ArrayList<>();
                    for (StockPrice p : prices) {
                        String date = p.getTradeDate().toString();
                        BigDecimal bmClose = bmCloses.get(date);
                        if (bmClose == null) continue;
                        if (stockBase == null) { stockBase = p.getClose(); bmBase = bmClose; }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("date", date);
                        m.put("close", p.getClose());
                        m.put("base100", p.getClose().divide(stockBase, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
                        m.put("bmClose", bmClose);
                        m.put("bmBase100", bmClose.divide(bmBase, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
                        benchmarkData.add(m);
                    }
                }
            }
        }
        if (benchmarkData != null) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("prices", result);
            wrapper.put("benchmark", benchmarkData);
            return JsonUtil.toJson(wrapper);
        }
        return JsonUtil.toJson(result);
    }

    private String allocationData(long portfolioId) {
        List<HoldingSnapshot> snapshots = holdingService.getSnapshots(portfolioId);
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
        List<DailyValue> values = analysisService.getDailyValues(portfolioId, from, to);
        List<Object[]> result = new ArrayList<>();
        for (DailyValue v : values) {
            result.add(new Object[]{ v.getSnapshotDate().toString(), v.getDailyPnl(), v.getTotalValue() });
        }
        return JsonUtil.toJson(result);
    }

    private String cumulativeReturnData(long portfolioId, int days, String startStr, String endStr) throws Exception {
        LocalDate to, from;
        if (startStr != null && endStr != null) {
            from = LocalDate.parse(startStr);
            to   = LocalDate.parse(endStr);
        } else if (days == 0) {
            to   = LocalDate.now();
            from = LocalDate.of(1990, 1, 1);
        } else {
            to   = LocalDate.now();
            from = to.minusDays(days);
        }
        List<Map<String, Object>> result = new ArrayList<>();

        java.sql.Date firstTxDate = jdbcQueryForObject(
            "SELECT MIN(trade_date) FROM transactions WHERE portfolio_id=? AND type IN ('BUY','SELL','TRANSFER_IN','TRANSFER_OUT')",
            java.sql.Date.class, portfolioId);
        if (firstTxDate != null) {
            LocalDate firstTx = firstTxDate.toLocalDate();
            if (firstTx.isAfter(from)) from = firstTx;
        }

        Map<String, BigDecimal> toCny = new HashMap<>();
        toCny.put("CNY", BigDecimal.ONE);
        try {
            List<Map<String, Object>> rates = jdbcQueryForList("SELECT currency, rate FROM exchange_rates");
            for (Map<String, Object> r : rates) {
                String curr = (String) r.get("currency");
                BigDecimal rate = (BigDecimal) r.get("rate");
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0)
                    toCny.put(curr, BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
            }
        } catch (Exception ignored) {}

        List<Map<String, Object>> allTxns = jdbcQueryForList(
            "SELECT stock_id, type, shares, price, fee, trade_date, currency FROM transactions WHERE portfolio_id=? AND type IN ('BUY','SELL') ORDER BY trade_date",
            portfolioId);

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

        Map<LocalDate, BigDecimal> cashWithTransfers = computeCashMap(portfolioId, from, to, toCny, true);
        Map<LocalDate, BigDecimal> cashNoTransfers = computeCashMap(portfolioId, from, to, toCny, false);

        Map<LocalDate, BigDecimal> divByDate = new HashMap<>();
        for (Dividend d : dividendDao.findByPortfolio(portfolioId)) {
            BigDecimal rate = toCny.getOrDefault(
                stockCurrency.computeIfAbsent(d.getStockId(), sid -> {
                    Stock s = stockDao.findById(sid);
                    return s != null ? s.getCurrency() : "CNY";
                }), BigDecimal.ONE);
            divByDate.merge(d.getRecordDate(), d.getTotalAmount().multiply(rate), BigDecimal::add);
        }

        Set<Long> allStockIds = new HashSet<>();
        for (Map<Long, BigDecimal[]> m : holdingsByDate.values()) allStockIds.addAll(m.keySet());
        Map<Long, Map<LocalDate, BigDecimal>> priceCache = new HashMap<>();
        for (Long sid : allStockIds) {
            Map<LocalDate, BigDecimal> dateMap = new HashMap<>();
            for (StockPrice p : stockPriceDao.findRange(sid, from, to))
                dateMap.put(p.getTradeDate(), p.getClose());
            priceCache.put(sid, dateMap);
        }

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
                m.put("value", totalToday);
                m.put("valueExTransfer", totalExTransfer);
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
                                                       Map<String, BigDecimal> toCny, boolean includeTransfers) throws Exception {
        TreeMap<LocalDate, BigDecimal> map = new TreeMap<>();
        List<Map<String, Object>> txns = jdbcQueryForList(
            "SELECT trade_date, type, shares, price, currency, fee FROM transactions WHERE portfolio_id=? AND trade_date <= ? ORDER BY trade_date",
            portfolioId, java.sql.Date.valueOf(to));
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
        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        BigDecimal running = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (map.floorEntry(d) != null) running = map.floorEntry(d).getValue();
            result.put(d, running);
        }
        return result;
    }

    private long resolvePortfolioId(Long param, HttpSession session) {
        Object uidAttr = session.getAttribute("userId");
        Long userId = uidAttr instanceof Number ? ((Number) uidAttr).longValue() : null;
        if (param != null) {
            return portfolioDao.isOwner(param, userId) ? param : 0L;
        }
        Object id = session.getAttribute("portfolioId");
        return id instanceof Number ? ((Number) id).longValue() : 0L;
    }

    // ── JDBC helpers ─────────────────────────────────────────────────────

    private List<Map<String, Object>> jdbcQueryForList(String sql, Object... args) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= colCount; c++) row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    result.add(row);
                }
            }
        }
        return result;
    }

    private <T> List<T> jdbcQueryForListSingle(String sql, Class<T> clazz, Object... args) throws Exception {
        List<T> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add((T) rs.getObject(1));
            }
        }
        return result;
    }

    private <T> T jdbcQueryForObject(String sql, Class<T> clazz, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return (T) rs.getObject(1);
            }
        }
        return null;
    }
}
