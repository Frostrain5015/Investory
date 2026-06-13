package com.investory.controller.api;

import com.google.gson.Gson;
import com.investory.server.DatabaseManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台管理 REST 控制器。
 */
public class AdminController {

    private static final Logger log = Logger.getLogger(AdminController.class.getName());
    private static final Gson gson = new Gson();
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile Process  currentProcess  = null;
    private volatile boolean  stopRequested   = false;
    private volatile boolean  pauseRequested  = false;
    private final Object      pauseLock       = new Object();

    private String pythonExecutable = System.getProperty("python.executable", "python3");

    private boolean checkAdmin(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    public void handleGetStatus(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "unauthorized")));
            return;
        }
        try (Connection conn = DatabaseManager.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("ANALYZE TABLE stock_prices");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> markets = jdbcQueryForList("""
            SELECT CASE WHEN s.market IN ('SH','SZ') THEN 'A' ELSE s.market END AS market,
                   COUNT(DISTINCT s.id) AS stock_count,
                   COUNT(sp.id)          AS price_rows,
                   COALESCE(MAX(sp.trade_date), '-') AS latest_date,
                   COALESCE(MIN(sp.trade_date), '-') AS earliest_date
            FROM stocks s
            LEFT JOIN stock_prices sp ON sp.stock_id = s.id
            WHERE s.market IN ('SH','SZ','HK','US')
            GROUP BY CASE WHEN s.market IN ('SH','SZ') THEN 'A' ELSE s.market END
            ORDER BY market
            """);
        Map<String, Object> idxStats = jdbcQueryForMap("""
            SELECT 'IDX' AS market,
                   COUNT(DISTINCT s.id) AS stock_count,
                   COUNT(sp.id)          AS price_rows,
                   COALESCE(MAX(sp.trade_date), '-') AS latest_date,
                   COALESCE(MIN(sp.trade_date), '-') AS earliest_date
            FROM stocks s
            LEFT JOIN stock_prices sp ON sp.stock_id = s.id
            WHERE s.market IN ('JP','KR','GB','DE','FR','TW','SG','IN','AU','CA','BR','IDX','CMD','CCY')
            """);
        if (idxStats != null) markets.add(idxStats);
        result.put("markets", markets);
        Map<String, Object> totals = new LinkedHashMap<>();
        long totalStocks = 0, totalRows = 0;
        for (var m : markets) { totalStocks += ((Number) m.get("stock_count")).longValue(); totalRows += ((Number) m.get("price_rows")).longValue(); }
        totals.put("stock_count", totalStocks); totals.put("price_rows", totalRows);
        result.put("totals", totals);
        List<Map<String, Object>> tables = jdbcQueryForList("""
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
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleGetUsers(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(List.of(Map.of("error", "unauthorized"))));
            return;
        }
        List<Map<String, Object>> result = jdbcQueryForList("""
            SELECT u.id, u.username, u.email, u.created_at,
                   (SELECT COUNT(*) FROM transactions t JOIN portfolios p ON t.portfolio_id = p.id WHERE p.user_id = u.id) AS txn_count,
                   (SELECT COUNT(*) FROM portfolios WHERE user_id = u.id) AS portfolio_count
            FROM users u ORDER BY u.id
            """);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleImpersonate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "unauthorized")));
            return;
        }
        long userId = Long.parseLong((String) req.getAttribute("userId"));
        var userRow = jdbcQueryForList("SELECT id, username FROM users WHERE id = ?", userId);
        if (userRow.isEmpty()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "user not found")));
            return;
        }
        var portfolios = jdbcQueryForList("SELECT id FROM portfolios WHERE user_id = ? ORDER BY id LIMIT 1", userId);
        HttpSession session = req.getSession();
        session.setAttribute("userId", userId);
        session.setAttribute("username", userRow.get(0).get("username"));
        if (!portfolios.isEmpty()) session.setAttribute("portfolioId", ((Number) portfolios.get(0).get("id")).longValue());
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(Map.of("status", "ok", "username", userRow.get(0).get("username"))));
    }

    public void handleDeleteUser(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "unauthorized")));
            return;
        }
        long userId = Long.parseLong((String) req.getAttribute("userId"));
        HttpSession session = req.getSession(false);
        if (session != null && Long.valueOf(userId).equals(session.getAttribute("userId"))) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "不能删除自己")));
            return;
        }
        jdbcUpdate("DELETE FROM daily_portfolio_value WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbcUpdate("DELETE FROM dividends WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbcUpdate("DELETE FROM transactions WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbcUpdate("DELETE FROM holdings WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbcUpdate("DELETE FROM cash_balances WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbcUpdate("DELETE FROM watchlist WHERE user_id = ?", userId);
        jdbcUpdate("DELETE FROM ai_settings WHERE user_id = ?", userId);
        jdbcUpdate("DELETE FROM ai_chat_history WHERE user_id = ?", userId);
        jdbcUpdate("DELETE FROM backtest_results WHERE user_id = ?", userId);
        jdbcUpdate("DELETE FROM backtest_strategies WHERE user_id = ?", userId);
        jdbcUpdate("DELETE FROM portfolios WHERE user_id = ?", userId);
        jdbcUpdate("DELETE FROM users WHERE id = ?", userId);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(Map.of("status", "ok")));
    }

    public void handleGetCrawlHistory(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(List.of(Map.of("error", "unauthorized"))));
            return;
        }
        List<Map<String, Object>> result = jdbcQueryForList("""
            SELECT m.market, m.started_at, m.ended_at, m.rows_written, m.stocks_failed, m.status
            FROM crawl_history m
            INNER JOIN (SELECT market, MAX(id) AS max_id FROM crawl_history GROUP BY market) latest ON m.id = latest.max_id
            ORDER BY m.market
            """);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleClearCrawlHistory(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "unauthorized")));
            return;
        }
        int deleted = jdbcUpdate("DELETE FROM crawl_history");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(Map.of("status", "ok", "deleted", deleted)));
    }

    public void handleCrawlStatus(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "unauthorized")));
            return;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", currentProcess != null && currentProcess.isAlive());
        status.put("market", null);
        status.put("label", null);
        status.put("progress", null);
        status.put("stopRequested", stopRequested);
        status.put("pauseRequested", pauseRequested);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(status));
    }

    public void handleStartCrawl(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        var writer = resp.getWriter();

        if (!checkAdmin(req)) {
            writer.write("event: error\ndata: {\"msg\":\"未授权\"}\n\n");
            writer.flush();
            return;
        }
        String market = (String) req.getAttribute("market");
        String start = req.getParameter("start") != null ? req.getParameter("start") : "";
        String end = req.getParameter("end") != null ? req.getParameter("end") : "";
        boolean reconnect = "true".equals(req.getParameter("reconnect"));

        if (!List.of("a", "sh", "sz", "hk", "us", "idx", "all").contains(market)) {
            writer.write("event: error\ndata: {\"msg\":\"无效市场: " + market + "\"}\n\n");
            writer.flush();
            return;
        }

        Map<String, String> LABELS = Map.of("all", "全市场", "a", "A股", "sh", "A股(沪)", "sz", "A股(深)", "hk", "港股", "us", "美股", "idx", "指数");
        String label = LABELS.getOrDefault(market, market.toUpperCase());
        final String startDate = start.isBlank() ? java.time.LocalDate.now().minusDays(10).toString() : start;
        final String endDate = end.isBlank() ? java.time.LocalDate.now().toString() : end;

        // Start async processing
        jakarta.servlet.AsyncContext ac = req.startAsync();
        ac.setTimeout(0);
        var asyncWriter = resp.getWriter();

        executor.submit(() -> {
            try {
                asyncWriter.write("event: status\ndata: {\"msg\":\"启动 " + label + " 抓取 (" + startDate + " ~ " + endDate + ")\",\"market\":\"" + market + "\"}\n\n");
                asyncWriter.flush();

                File script = new File("script/fetch_stocks.py");
                if (!script.exists()) script = new File("../script/fetch_stocks.py").getCanonicalFile();
                if (!script.exists()) {
                    asyncWriter.write("event: error\ndata: {\"msg\":\"脚本未找到\"}\n\n");
                    asyncWriter.flush();
                    ac.complete();
                    return;
                }
                File scriptDir = script.getParentFile();
                boolean isFirstStart = true;

                while (!stopRequested) {
                    if (isFirstStart) { isFirstStart = false; }
                    ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u", script.getAbsolutePath(), "-m", market, "--start", startDate, "--end", endDate);
                    pb.directory(scriptDir);
                    pb.redirectErrorStream(true);
                    pb.environment().put("PYTHONUNBUFFERED", "1");
                    Process p = pb.start();
                    currentProcess = p;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (stopRequested) { p.destroyForcibly(); break; }
                            Matcher m = PROGRESS_RE.matcher(line);
                            if (m.find()) {
                                Map<String, Object> prog = new LinkedHashMap<>();
                                prog.put("current", Integer.parseInt(m.group(1)));
                                prog.put("total",   Integer.parseInt(m.group(2)));
                                prog.put("pct",     Double.parseDouble(m.group(3)));
                                prog.put("name",    m.group(4).trim());
                                asyncWriter.write("event: progress\ndata: " + gson.toJson(prog) + "\n\n");
                                asyncWriter.flush();
                            } else if (line.contains("===") || line.contains("完成")) {
                                asyncWriter.write("event: info\ndata: {\"msg\":\"" + line.replaceFirst("^.*?INFO\\s*", "").trim() + "\"}\n\n");
                                asyncWriter.flush();
                            }
                        }
                    }
                    boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.MINUTES);
                    if (!finished) { p.destroyForcibly(); asyncWriter.write("event: error\ndata: {\"msg\":\"" + label + " 抓取超时\"}\n\n"); asyncWriter.flush(); }
                    int exitCode = finished ? p.exitValue() : -1;
                    currentProcess = null;
                    if (stopRequested) { asyncWriter.write("event: stopped\ndata: {\"market\":\"" + market + "\",\"msg\":\"" + label + " 抓取已停止\"}\n\n"); asyncWriter.flush(); break; }
                    if (exitCode == 0) { asyncWriter.write("event: done\ndata: {\"market\":\"" + market + "\",\"msg\":\"" + label + " 抓取完成\"}\n\n"); asyncWriter.flush(); break; }
                    if (pauseRequested) {
                        asyncWriter.write("event: info\ndata: {\"msg\":\"已暂停\"}\n\n");
                        asyncWriter.flush();
                        synchronized (pauseLock) {
                            while (pauseRequested && !stopRequested) { try { pauseLock.wait(1000); } catch (InterruptedException e) { break; } }
                        }
                        if (stopRequested) { asyncWriter.write("event: stopped\ndata: {\"market\":\"" + market + "\",\"msg\":\"" + label + " 抓取已停止\"}\n\n"); asyncWriter.flush(); break; }
                        asyncWriter.write("event: info\ndata: {\"msg\":\"继续抓取...\"}\n\n");
                        asyncWriter.flush();
                        continue;
                    }
                    asyncWriter.write("event: error\ndata: {\"msg\":\"脚本退出码: " + exitCode + "\"}\n\n");
                    asyncWriter.flush();
                    break;
                }
            } catch (Exception e) {
                try { asyncWriter.write("event: error\ndata: {\"msg\":\"" + e.getMessage() + "\"}\n\n"); asyncWriter.flush(); } catch (Exception ignored) {}
            } finally {
                stopRequested = false;
                pauseRequested = false;
                currentProcess = null;
                ac.complete();
            }
        });

        ac.complete(); // Don't keep request open - SSE is appending to async context
    }

    public void handleStopCrawl(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "unauthorized")));
            return;
        }
        stopRequested = true;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        Process p = currentProcess;
        if (p != null) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(Map.of("status", "stopping")));
    }

    public void handlePauseCrawl(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "unauthorized")));
            return;
        }
        if (currentProcess == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("status", "no_process")));
            return;
        }
        pauseRequested = true;
        currentProcess.destroyForcibly();
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(Map.of("status", "paused")));
    }

    public void handleResumeCrawl(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!checkAdmin(req)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "unauthorized")));
            return;
        }
        if (currentProcess == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("status", "no_process")));
            return;
        }
        synchronized (pauseLock) { pauseRequested = false; pauseLock.notifyAll(); }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(Map.of("status", "resumed")));
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

    private Map<String, Object> jdbcQueryForMap(String sql, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    int colCount = rs.getMetaData().getColumnCount();
                    for (int c = 1; c <= colCount; c++) row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    return row;
                }
            }
        }
        return null;
    }

    private int jdbcUpdate(String sql, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }
}
