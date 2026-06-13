package com.investory.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.dao.StocksageCacheDao;
import com.investory.server.AppContext;
import com.investory.server.SchedulerService;
import com.investory.util.StocksageAlphaExecutor;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * StockSage Alpha 定时任务调度器。
 */
public class StocksageScheduler {

    private static final Logger log = Logger.getLogger(StocksageScheduler.class.getName());
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper json = new ObjectMapper();

    private final StocksageAlphaExecutor executor;
    private final StocksageCacheDao cacheDao;

    public StocksageScheduler() {
        this.executor = AppContext.get(StocksageAlphaExecutor.class);
        this.cacheDao = AppContext.get(StocksageCacheDao.class);
    }

    /**
     * Register all scheduled tasks with the SchedulerService.
     */
    public void scheduleAll() {
        SchedulerService.scheduleAtFixedRate("StockSage缓存预热", this::scheduledPrefetch,
                secondsUntil(15, 30), 86400);
        SchedulerService.scheduleAtFixedRate("StockSage主策略扫描", this::scheduledMainScan,
                secondsUntil(18, 0), 86400);
        SchedulerService.scheduleAtFixedRate("StockSage盘前环境检测", this::scheduledRegimeCheckMorning,
                secondsUntil(9, 0), 86400);
        // Intraday regime check every 30 minutes (9:30-15:00)
        SchedulerService.scheduleAtFixedRate("StockSage盘中环境检测", this::scheduledRegimeCheckIntraday,
                secondsUntil(9, 30), 1800);
    }

    private static long secondsUntil(int hour, int minute) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(SHANGHAI);
        java.time.ZonedDateTime target = now.withHour(hour).withMinute(minute).withSecond(0);
        if (target.isBefore(now) || target.isEqual(now)) {
            target = target.plusDays(1);
        }
        return java.time.Duration.between(now, target).getSeconds();
    }

    public void scheduledPrefetch() {
        log.info("[StockSage] 开始预热缓存");
        try {
            executor.executeWithTimeout(20, java.util.concurrent.TimeUnit.MINUTES, "prefetch_data");
            log.info("[StockSage] 缓存预热完成");
        } catch (Exception e) {
            log.warning("[StockSage] 缓存预热失败: " + e.getMessage());
        }
    }

    public void scheduledMainScan() {
        log.info("[StockSage] 开始收盘后主策略扫描");
        try {
            Map<String, Object> result = executor.executeWithTimeout(30, java.util.concurrent.TimeUnit.MINUTES,
                "scan_universe", "--type", "main");

            if (result.containsKey("error")) {
                log.severe("[StockSage] 扫描失败: " + result.get("error"));
                return;
            }

            String today = LocalDate.now(SHANGHAI).format(DateTimeFormatter.ISO_LOCAL_DATE);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> picks = (List<Map<String, Object>>) result.getOrDefault("picks", List.of());
            String regime = String.valueOf(result.getOrDefault("regime", "unknown"));

            List<Map<String, Object>> pickRows = new ArrayList<>();
            for (Map<String, Object> p : picks) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pick_date", today);
                row.put("stock_symbol", p.get("code"));
                row.put("stock_name", p.get("name"));
                row.put("buy_score", p.get("buy_score"));
                row.put("sell_score", p.get("sell_score"));
                row.put("total_score", p.get("total_score"));
                row.put("strategy_type", "main");
                row.put("regime", regime);
                row.put("reason_text", joinReasons(p.get("bullish")));
                row.put("factors_json", "{}");
                pickRows.add(row);
            }
            if (!pickRows.isEmpty()) {
                cacheDao.batchInsertDailyPicks(pickRows);
            }

            log.info("[StockSage] 扫描完成: " + picks.size() + " 条推荐, 环境=" + regime);
        } catch (Exception e) {
            log.severe("[StockSage] 扫描异常: " + e.getMessage());
        }
    }

    public void scheduledRegimeCheckMorning() {
        runRegimeCheck();
    }

    public void scheduledRegimeCheckIntraday() {
        runRegimeCheck();
    }

    private void runRegimeCheck() {
        try {
            Map<String, Object> result = executor.execute("regime_status");
            if (result.containsKey("regime")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> regime = (Map<String, Object>) result.get("regime");
                String date = LocalDate.now(SHANGHAI).format(DateTimeFormatter.ISO_LOCAL_DATE);
                String signal = String.valueOf(regime.getOrDefault("signal", "unknown"));
                double score = toDouble(regime.get("score"));
                String desc = String.valueOf(regime.getOrDefault("description", ""));

                cacheDao.upsertRegimeCache(date, signal, score, desc,
                    safeJson(regime.get("indicators")));

                log.info("[StockSage] 环境检测: " + signal + " (score=" + score + ")");
            }
        } catch (Exception e) {
            log.warning("[StockSage] 环境检测失败: " + e.getMessage());
        }
    }

    private String joinReasons(Object bullish) {
        if (bullish instanceof List) {
            List<String> reasons = new ArrayList<>();
            for (Object item : (List<?>) bullish) {
                if (item == null) continue;
                if (item instanceof Map) {
                    Object signal = ((Map<?, ?>) item).get("signal");
                    Object factor = ((Map<?, ?>) item).get("factor");
                    if (signal != null && !String.valueOf(signal).isBlank()) {
                        reasons.add(String.valueOf(signal));
                    } else if (factor != null && !String.valueOf(factor).isBlank()) {
                        reasons.add(String.valueOf(factor));
                    }
                } else if (!String.valueOf(item).isBlank()) {
                    reasons.add(String.valueOf(item));
                }
            }
            return String.join("; ", reasons);
        }
        return "";
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
