package com.investory.dao;

import java.util.*;

/**
 * StockSage Alpha 缓存数据访问对象。
 *
 * <p>操作表：stocksage_scan_cache, stocksage_factor_cache,
 * stocksage_regime_cache, stocksage_chip_cache,
 * stocksage_daily_picks, stocksage_pick_feedback。
 *
 * <p>只提供读操作（缓存的写入由 StocksageScheduler 和 StocksageAlphaService 负责）。
 */
public class StocksageCacheDao extends BaseDao {

    // ── 扫描结果 ─────────────────────────────────────────────────────────

    /** 获取最新一次指定类型的扫描结果 */
    public List<Map<String, Object>> findLatestScanResults(String scanType, int limit) {
        return queryForList(
            "SELECT * FROM stocksage_scan_cache WHERE scan_type = ? " +
            "AND scan_date = (SELECT MAX(scan_date) FROM stocksage_scan_cache WHERE scan_type = ?) " +
            "ORDER BY total_score DESC LIMIT ?",
            scanType, scanType, limit);
    }

    /** 批量插入扫描结果 */
    public void batchInsertScanResults(List<Map<String, Object>> rows) {
        String sql = "INSERT INTO stocksage_scan_cache " +
            "(scan_type, scan_date, stock_symbol, stock_name, buy_score, sell_score, total_score, regime, bullish, bearish, factors_json) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        for (Map<String, Object> r : rows) {
            update(sql,
                r.get("scan_type"), r.get("scan_date"), r.get("stock_symbol"), r.get("stock_name"),
                r.get("buy_score"), r.get("sell_score"), r.get("total_score"), r.get("regime"),
                r.get("bullish"), r.get("bearish"), r.get("factors_json"));
        }
    }

    // ── 因子缓存 ─────────────────────────────────────────────────────────

    /** 批量查询多只股票的最新因子分，以 symbol 为 key */
    public Map<String, Map<String, Object>> findFactorScoresBySymbols(List<String> symbols) {
        if (symbols.isEmpty()) return Map.of();
        String placeholders = String.join(",", symbols.stream().map(s -> "?").toArray(String[]::new));
        List<Map<String, Object>> rows = queryForList(
            "SELECT f.stock_symbol, f.factor_name, f.factor_group, f.buy_score, f.sell_score, " +
            "f.factor_value, f.description FROM stocksage_factor_cache f " +
            "INNER JOIN (SELECT stock_symbol, MAX(computed_at) AS max_t FROM stocksage_factor_cache " +
            "WHERE stock_symbol IN (" + placeholders + ") GROUP BY stock_symbol) latest " +
            "ON f.stock_symbol = latest.stock_symbol AND f.computed_at = latest.max_t",
            symbols.toArray());
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String sym = (String) row.get("stock_symbol");
            result.putIfAbsent(sym, new LinkedHashMap<>());
        }
        return result;
    }

    /** 获取单只股票的最新逐因子明细 */
    public List<Map<String, Object>> findFactorBreakdown(String symbol) {
        return queryForList(
            "SELECT * FROM stocksage_factor_cache WHERE stock_symbol = ? " +
            "AND computed_at = (SELECT MAX(computed_at) FROM stocksage_factor_cache WHERE stock_symbol = ?) " +
            "ORDER BY buy_score DESC",
            symbol, symbol);
    }

    /** 批量覆盖写入因子缓存 */
    public void batchUpsertFactorCache(List<Map<String, Object>> factors) {
        String sql = "INSERT INTO stocksage_factor_cache " +
            "(stock_symbol, factor_name, factor_group, factor_value, buy_score, sell_score, description) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";
        for (Map<String, Object> f : factors) {
            update(sql,
                f.get("stock_symbol"), f.get("factor_name"), f.get("factor_group"),
                f.get("factor_value"), f.get("buy_score"), f.get("sell_score"), f.get("description"));
        }
    }

    // ── 市场环境 ─────────────────────────────────────────────────────────

    /** 获取最新的市场环境记录 */
    public Map<String, Object> findLatestRegime() {
        List<Map<String, Object>> rows = queryForList(
            "SELECT * FROM stocksage_regime_cache ORDER BY regime_date DESC LIMIT 1");
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入或更新市场环境 */
    public void upsertRegimeCache(String regimeDate, String regime, Double confidence,
                                   String description, String indicatorsJson) {
        update("INSERT INTO stocksage_regime_cache (regime_date, regime, confidence, description, indicators_json) " +
               "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE regime=VALUES(regime), " +
               "confidence=VALUES(confidence), description=VALUES(description), indicators_json=VALUES(indicators_json)",
               regimeDate, regime, confidence, description, indicatorsJson);
    }

    // ── 筹码分布 ─────────────────────────────────────────────────────────

    /** 获取某只股票的最新筹码分布 */
    public Map<String, Object> findChipDistribution(String symbol) {
        List<Map<String, Object>> rows = queryForList(
            "SELECT * FROM stocksage_chip_cache WHERE stock_symbol = ? " +
            "ORDER BY computed_at DESC LIMIT 1", symbol);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入筹码分布（ON DUPLICATE KEY UPDATE） */
    public void upsertChipCache(String symbol, String chipDataJson) {
        update("INSERT INTO stocksage_chip_cache (stock_symbol, chip_data_json) VALUES (?, ?) " +
               "ON DUPLICATE KEY UPDATE chip_data_json=VALUES(chip_data_json), computed_at=NOW()",
               symbol, chipDataJson);
    }

    // ── 每日推荐 ─────────────────────────────────────────────────────────

    /** 获取今日推荐 */
    public List<Map<String, Object>> findDailyPicks(String date, int limit) {
        return queryForList(
            "SELECT * FROM stocksage_daily_picks WHERE pick_date = ? ORDER BY total_score DESC LIMIT ?",
            date, limit);
    }

    /** 获取历史推荐 */
    public List<Map<String, Object>> findPickHistory(String fromDate, String toDate, int limit) {
        return queryForList(
            "SELECT * FROM stocksage_daily_picks WHERE pick_date BETWEEN ? AND ? " +
            "ORDER BY pick_date DESC, total_score DESC LIMIT ?",
            fromDate, toDate, limit);
    }

    /** 批量插入每日推荐 */
    public void batchInsertDailyPicks(List<Map<String, Object>> picks) {
        String sql = "INSERT INTO stocksage_daily_picks " +
            "(pick_date, stock_symbol, stock_name, buy_score, sell_score, total_score, " +
            "strategy_type, regime, reason_text, factors_json) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        for (Map<String, Object> p : picks) {
            update(sql,
                p.get("pick_date"), p.get("stock_symbol"), p.get("stock_name"),
                p.get("buy_score"), p.get("sell_score"), p.get("total_score"),
                p.get("strategy_type"), p.get("regime"), p.get("reason_text"), p.get("factors_json"));
        }
    }

    // ── 用户反馈 ─────────────────────────────────────────────────────────

    /** 插入或更新用户对推荐的反馈 */
    public void upsertPickFeedback(long pickId, long userId, Boolean liked) {
        update("INSERT INTO stocksage_pick_feedback (pick_id, user_id, liked) VALUES (?, ?, ?) " +
               "ON DUPLICATE KEY UPDATE liked=VALUES(liked)",
               pickId, userId, liked);
    }
}
