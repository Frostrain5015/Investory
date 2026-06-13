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
 *
 * <p>职责：
 * <ul>
 *   <li>调用 Python 桥接脚本执行多因子评分、市场环境检测、筹码分析</li>
 *   <li>管理缓存表（读优先，缓存未命中时触发计算）</li>
 *   <li>提供扫描结果、因子拆解、每日推荐等聚合查询</li>
 * </ul>
 */
public class StocksageAlphaService {

    private final StocksageAlphaExecutor executor;
    private final StocksageCacheDao cacheDao;
    private final ObjectMapper json = new ObjectMapper();

    public StocksageAlphaService() {
        this.executor = AppContext.get(StocksageAlphaExecutor.class);
        this.cacheDao = AppContext.get(StocksageCacheDao.class);
    }

    // ── 因子评分 ─────────────────────────────────────────────────────────

    /**
     * 批量获取股票多因子评分。优先读缓存，缓存缺失时调用 Python 实时计算。
     */
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

    /**
     * 单股逐因子拆解，返回各因子的名称、分组、值、买入分、卖出分。
     */
    public Map<String, Object> getFactorBreakdown(String symbol) {
        List<Map<String, Object>> factors = cacheDao.findFactorBreakdown(symbol);
        if (factors.isEmpty()) {
            // 缓存未命中，实时调用 Python
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

    // ── 市场环境 ─────────────────────────────────────────────────────────

    /** 获取当前市场环境，优先读缓存。统一返回 {"regime": {...}} 格式。 */
    public Map<String, Object> getRegimeStatus() {
        Map<String, Object> cached = null;
        try {
            cached = cacheDao.findLatestRegime();
        } catch (Exception ignored) {}

        if (cached != null && !cached.isEmpty() && cached.get("regime") != null) {
            // Normalize DB row format to bridge format
            String signal = String.valueOf(cached.getOrDefault("regime", "NORMAL"));
            return Map.of("regime", Map.of(
                "signal", signal,
                "score", cached.getOrDefault("confidence", 5.0),
                "description", cached.getOrDefault("description", ""),
                "indicators", cached.getOrDefault("indicators_json", Map.of())
            ));
        }

        // Cache miss — call bridge
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

    /** 触发扫描刷新（异步，通过 SSE 推送进度） */
    public Map<String, Object> triggerUniverseScan() {
        try {
            return executor.execute("scan_universe", "--type", "main");
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // ── 筹码分布 ─────────────────────────────────────────────────────────

    /** 获取筹码分布数据 */
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

    // ── 每日推荐 ─────────────────────────────────────────────────────────

    /** 获取今日推荐 */
    public List<Map<String, Object>> getDailyPicks() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return cacheDao.findDailyPicks(today, 10);
    }

    // ── 股票综合分析（聚合：因子 + 筹码 + 环境 + 同业对比） ───────────

    /**
     * 获取单只股票的完整分析：因子拆解、筹码分布、当前环境上下文。
     */
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

    // ── 辅助 ─────────────────────────────────────────────────────────────

    private double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private String safeJson(Object obj) {
        try { return json.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}
