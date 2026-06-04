package com.investory.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.dao.StocksageCacheDao;
import com.investory.util.StocksageAlphaExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * StockSage Alpha 定时任务调度器。
 *
 * <p>每日收盘后自动运行多因子扫描并缓存结果，
 * 盘中定期检测市场环境变化。
 *
 * <p>所有时间均为 Asia/Shanghai 时区。
 */
@Component
public class StocksageScheduler {

    private static final Logger log = Logger.getLogger(StocksageScheduler.class.getName());
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper json = new ObjectMapper();

    private final StocksageAlphaExecutor executor;
    private final StocksageCacheDao cacheDao;

    @Autowired
    public StocksageScheduler(StocksageAlphaExecutor executor, StocksageCacheDao cacheDao) {
        this.executor = executor;
        this.cacheDao = cacheDao;
    }

    // ── 盘后预热缓存 ─────────────────────────────────────────────────────

    /**
     * 每个交易日 15:30 预热 akshare 缓存（收盘后数据已更新）。
     * 预热 CSI300 前30只股票 + 市场环境数据，后续选股/风控分析秒级响应。
     */
    @Scheduled(cron = "${stocksage.prefetch.cron:0 30 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void scheduledPrefetch() {
        log.info("[StockSage] 开始预热缓存");
        try {
            executor.executeWithTimeout(20, java.util.concurrent.TimeUnit.MINUTES, "prefetch_data");
            log.info("[StockSage] 缓存预热完成");
        } catch (Exception e) {
            log.warning("[StockSage] 缓存预热失败: " + e.getMessage());
        }
    }

    // ── 收盘后主策略扫描 ─────────────────────────────────────────────────

    /**
     * 每个交易日 18:00 运行主策略全市场扫描。
     * A 股收盘 15:00，18:00 时收盘数据已齐备。
     */
    @Scheduled(cron = "${stocksage.scan.cron:0 0 18 * * MON-FRI}", zone = "Asia/Shanghai")
    public void scheduledMainScan() {
        log.info("[StockSage] 开始收盘后主策略扫描");
        try {
            Map<String, Object> result = executor.executeWithTimeout(30, java.util.concurrent.TimeUnit.MINUTES,
                "scan_universe", "--type", "main");

            if (result.containsKey("error")) {
                log.severe("[StockSage] 扫描失败: " + result.get("error"));
                return;
            }

            // 缓存扫描结果到数据库
            String today = LocalDate.now(SHANGHAI).format(DateTimeFormatter.ISO_LOCAL_DATE);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> picks = (List<Map<String, Object>>) result.getOrDefault("picks", List.of());
            String regime = String.valueOf(result.getOrDefault("regime", "unknown"));

            // 写入每日推荐表
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

    // ── 盘中环境检测 ────────────────────────────────────────────────────

    /**
     * 每个交易日 9:00 开盘前检测市场环境。
     */
    @Scheduled(cron = "${stocksage.regime.am.cron:0 0 9 * * MON-FRI}", zone = "Asia/Shanghai")
    public void scheduledRegimeCheckMorning() {
        runRegimeCheck();
    }

    /**
     * 盘中每 30 分钟检测一次市场环境变化（9:30-15:00）。
     */
    @Scheduled(cron = "${stocksage.regime.intraday.cron:0 0/30 9-15 * * MON-FRI}", zone = "Asia/Shanghai")
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

    // ── 辅助 ─────────────────────────────────────────────────────────────

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
