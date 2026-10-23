package com.investory.controller.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;


/** Administration of business metadata, users and preserved synchronization records. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JdbcTemplate jdbc;

    public AdminController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 检查当前请求是否为管理员身份。
     *
     * @param req HTTP 请求
     * @return true 表示已通过管理员校验
     */
    private boolean checkAdmin(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    /**
     * 获取数据库统计状态，包含各市场证券数量，
     * 以及各数据表的磁盘占用（MB）。
     *
     * <p>历史行情按需从外部接口获取，不再读取归档行情表。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return 包含 markets（按市场分组统计）、totals（全局合计）、tables（表磁盘信息）的 Map
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus(HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");

        // Price history is external; metadata statistics never open a price table.

        Map<String, Object> result = new LinkedHashMap<>();

        // 按市场分组统计 A股(SH/SZ/BJ合并为A)、港股(HK)、美股(US) 的证券数量
        List<Map<String, Object>> markets = jdbc.queryForList("""
            SELECT CASE WHEN s.market IN ('SH','SZ','BJ') THEN 'A' ELSE s.market END AS market,
                   COUNT(DISTINCT s.id) AS stock_count
            FROM stocks s
            WHERE s.market IN ('SH','SZ','BJ','HK','US')
            GROUP BY CASE WHEN s.market IN ('SH','SZ','BJ') THEN 'A' ELSE s.market END
            ORDER BY market
            """);
        // 单独汇总指数/大宗商品/货币等其他市场（JP/KR/GB/…/IDX/CMD/CCY）
        Map<String, Object> idxStats = jdbc.queryForMap("""
            SELECT 'IDX' AS market,
                   COUNT(DISTINCT s.id) AS stock_count
            FROM stocks s
            WHERE s.market IN ('JP','KR','GB','DE','FR','TW','SG','IN','AU','CA','BR','IDX','CMD','CCY')
            """);
        if (idxStats != null) {
            markets.add(idxStats);
        }
        result.put("markets", markets);

        // 计算证券数量合计
        Map<String, Object> totals = new LinkedHashMap<>();
        long totalStocks = 0;
        for (var m : markets) {
            totalStocks += ((Number) m.get("stock_count")).longValue();

        }
        totals.put("stock_count", totalStocks);
        result.put("totals", totals);

        // 按 data+index 总大小降序列出各表的磁盘占用
        List<Map<String, Object>> tables = jdbc.queryForList("""
            SELECT table_name,
                   ROUND(data_length/1024/1024, 1)  AS data_mb,
                   ROUND(index_length/1024/1024, 1) AS index_mb,
                   ROUND((data_length+index_length)/1024/1024, 1) AS total_mb,
                   table_rows
            FROM information_schema.TABLES
            WHERE table_schema = DATABASE()
            ORDER BY (data_length+index_length) DESC
            """);
        result.put("tables", tables);

        return result;
    }

    // ── 用户管理 ──────────────────────────────────────────────────────────

    /**
     * 获取所有用户列表，附带每位用户的交易记录数和组合数。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return 用户列表，每条记录包含 id、username、email、created_at、txn_count、portfolio_count
     */
    @GetMapping("/users")
    public List<Map<String, Object>> getUsers(HttpServletRequest req) {
        if (!checkAdmin(req)) return List.of(Map.of("error", "unauthorized"));
        return jdbc.queryForList("""
            SELECT u.id, u.username, u.email, u.created_at,
                   (SELECT COUNT(*) FROM transactions t
                    JOIN portfolios p ON t.portfolio_id = p.id
                    WHERE p.user_id = u.id) AS txn_count,
                   (SELECT COUNT(*) FROM portfolios WHERE user_id = u.id) AS portfolio_count
            FROM users u
            ORDER BY u.id
            """);
    }

    /**
     * 以指定用户身份登录（管理员模拟登录），同时保留 isAdmin=true 使侧边栏链接可见。
     * 将目标用户的第一个组合 id 也写入 Session，确保页面数据正确加载。
     *
     * @param userId 要模拟的目标用户 id
     * @param req    HTTP 请求（用于管理员校验和 Session 修改）
     * @return 包含 status 和 username 的结果 Map
     */
    @PostMapping("/impersonate/{userId}")
    public Map<String, Object> impersonate(@PathVariable long userId, HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        var userRow = jdbc.queryForList(
            "SELECT id, username FROM users WHERE id = ?", userId);
        if (userRow.isEmpty()) return Map.of("error", "user not found");
        var portfolios = jdbc.queryForList(
            "SELECT id FROM portfolios WHERE user_id = ? ORDER BY id LIMIT 1", userId);

        HttpSession session = req.getSession();
        session.setAttribute("userId", userId);
        session.setAttribute("username", userRow.get(0).get("username"));
        // Keep isAdmin = true so the sidebar nav link stays visible
        if (!portfolios.isEmpty()) {
            session.setAttribute("portfolioId", ((Number) portfolios.get(0).get("id")).longValue());
        }
        return Map.of("status", "ok", "username", userRow.get(0).get("username"));
    }

    /**
     * 删除指定用户及其所有关联数据（组合、持仓、交易、股息、AI设置、回测结果等）。
     * 禁止管理员删除自己的账号。
     *
     * @param userId 要删除的用户 id
     * @param req    HTTP 请求（用于管理员校验及自我删除防护）
     * @return 包含 status 的结果 Map
     */
    @DeleteMapping("/users/{userId}")
    public Map<String, Object> deleteUser(@PathVariable long userId, HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        // Don't allow deleting yourself
        HttpSession session = req.getSession(false);
        if (session != null && Long.valueOf(userId).equals(session.getAttribute("userId"))) {
            return Map.of("error", "不能删除自己");
        }
        // 按外键依赖顺序逐表删除，避免约束冲突
        jdbc.update("DELETE FROM daily_portfolio_value WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM dividends WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM transactions WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM holdings WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM cash_balances WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM watchlist WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ai_settings WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM backtest_results WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM backtest_strategies WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        return Map.of("status", "ok");
    }

    /**
     * 获取各市场最近一次抓取的历史记录（每市场取 id 最大的一条）。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return 列表，每条包含 market、started_at、ended_at、rows_written、stocks_failed、status
     */
    @GetMapping("/crawl-history")
    public List<Map<String, Object>> getCrawlHistory(HttpServletRequest req) {
        if (!checkAdmin(req)) return List.of(Map.of("error", "unauthorized"));
        // Latest run per market
        return jdbc.queryForList("""
            SELECT m.market, m.started_at, m.ended_at, m.rows_written, m.stocks_failed, m.status
            FROM crawl_history m
            INNER JOIN (
                SELECT market, MAX(id) AS max_id FROM crawl_history GROUP BY market
            ) latest ON m.id = latest.max_id
            ORDER BY m.market
            """);
    }

    /** Historical run records remain available after the price collector is retired. */
    @DeleteMapping("/crawl-history")
    public Map<String, Object> clearCrawlHistory(HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        return Map.of("status", "ok", "deleted", jdbc.update("DELETE FROM crawl_history"));
    }
}
