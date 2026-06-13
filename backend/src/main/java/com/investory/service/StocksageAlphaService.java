package com.investory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.dao.StocksageCacheDao;
import com.investory.server.AppContext;
import com.investory.util.StocksageAlphaExecutor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * StockSage Alpha 核心服务。
 */
public class StocksageAlphaService {

    private final StocksageAlphaExecutor executor;
    private final StocksageCacheDao cacheDao;
    private final ObjectMapper json = new ObjectMapper();

    public StocksageAlphaService() {
        this.executor = AppContext.get(StocksageAlphaExecutor.class);
        this.cacheDao = AppContext.get(StocksageCacheDao.class);
    }

    public Map<String, Object> getFactorScores(List<String> symbols) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String sym : symbols) {
            List<Map<String, Object>> cached = cacheDao.findFactorBreakdown(sym);
            if (!cached.isEmpty()) {
                double buySum = 0, sellSum = 0;
                for (Map<String, Object> f : cached) {
                    buySum += toDouble(f.get("buy_score"));
                    sellSum += toDouble(f.get("sell_score"));
                }
                result.put(sym, Map.of(
                    "symbol", sym,
                    "buy_score", Math.round(buySum * 10.0) / 10.0,
                    "sell_score", Math.round(sellSum * 10.0) / 10.0,
                    "total_score", Math.round((buySum / (buySum + sellSum + 0.01)) * 1000.0) / 10.0,
                    "cached", true
                ));
            } else {
                result.put(sym, Map.of("symbol", sym, "cached", false, "message", "尚未计算"));
            }
        }
        return Map.of("scores", result);
    }

    public Map<String, Object> getFactorBreakdown(String symbol) {
        List<Map<String, Object>> factors = cacheDao.findFactorBreakdown(symbol);
        if (factors.isEmpty()) {
            try {
                return executor.execute("factor_breakdown", "--symbol", symbol);
            } catch (Exception e) {
                return Map.of("error", e.getMessage());
            }
        }

        double buySum = 0, sellSum = 0;
        List<Map<String, Object>> factorList = new ArrayList<>();
        for (Map<String, Object> f : factors) {
            buySum += toDouble(f.get("buy_score"));
            sellSum += toDouble(f.get("sell_score"));
            factorList.add(Map.of(
                "name", f.getOrDefault("factor_name", ""),
                "group", f.getOrDefault("factor_group", "other"),
                "value", f.getOrDefault("factor_value", 0),
                "buy_score", f.getOrDefault("buy_score", 0),
                "sell_score", f.getOrDefault("sell_score", 0),
                "description", f.getOrDefault("description", "")
            ));
        }

        return Map.of(
            "symbol", symbol,
            "total_score", Math.round((buySum / (buySum + sellSum + 0.01)) * 1000.0) / 10.0,
            "buy_score", Math.round(buySum * 10.0) / 10.0,
            "sell_score", Math.round(sellSum * 10.0) / 10.0,
            "factors", factorList
        );
    }

    public Map<String, Object> getRegimeStatus() {
        Map<String, Object> cached = null;
        try {
            cached = cacheDao.findLatestRegime();
        } catch (Exception ignored) {}

        if (cached != null && !cached.isEmpty() && cached.get("regime") != null) {
            String signal = String.valueOf(cached.getOrDefault("regime", "NORMAL"));
            return Map.of("regime", Map.of(
                "signal", signal,
                "score", cached.getOrDefault("confidence", 5.0),
                "description", cached.getOrDefault("description", ""),
                "indicators", cached.getOrDefault("indicators_json", Map.of())
            ));
        }

        try {
            Map<String, Object> result = executor.execute("regime_status");
            if (result.containsKey("regime")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> regime = (Map<String, Object>) result.get("regime");
                String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
                cacheDao.upsertRegimeCache(date,
                    String.valueOf(regime.getOrDefault("signal", "unknown")),
                    toDouble(regime.get("score")),
                    String.valueOf(regime.getOrDefault("description", "")),
                    safeJson(regime.get("indicators")));
                return result;
            }
            return result;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    public Map<String, Object> triggerUniverseScan() {
        try {
            return executor.execute("scan_universe", "--type", "main");
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    public Map<String, Object> getChipDistribution(String symbol) {
        try {
            Map<String, Object> cached = cacheDao.findChipDistribution(symbol);
            if (cached != null && !cached.isEmpty()) return cached;
        } catch (Exception ignored) {}

        try {
            return executor.execute("chip_distribution", "--symbol", symbol);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    public List<Map<String, Object>> getDailyPicks() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return cacheDao.findDailyPicks(today, 10);
    }

    public Map<String, Object> getStockAnalysis(String symbol) {
        Map<String, Object> factors = getFactorBreakdown(symbol);
        Map<String, Object> chip = getChipDistribution(symbol);
        Map<String, Object> regime = getRegimeStatus();

        return Map.of(
            "symbol", symbol,
            "factors", factors,
            "chip", chip,
            "regime", regime
        );
    }

    private double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private String safeJson(Object obj) {
        try { return json.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}
