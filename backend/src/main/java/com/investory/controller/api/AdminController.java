package com.investory.controller.api;

import com.google.gson.Gson;
import com.investory.crawler.CrawlSessionManager;
import com.investory.server.AppContext;
import com.investory.server.ConfigLoader;
import com.investory.server.DatabaseManager;
import com.investory.server.SseClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdminController {

    private static final Gson gson = new Gson();
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    private final ExecutorService executor;
    private final CrawlSessionManager session;
    private final String pythonExecutable = ConfigLoader.get("python.executable", "python3");

    private volatile Process currentProcess = null;
    private volatile boolean stopRequested = false;
    private volatile boolean pauseRequested = false;
    private final Object pauseLock = new Object();

    public AdminController() {
        this.executor = AppContext.get(ExecutorService.class);
        this.session = AppContext.get(CrawlSessionManager.class);
    }

    private boolean checkAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && Boolean.TRUE.equals(s.getAttribute("isAdmin"));
    }

    public void handleGetStatus(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }

        update("ANALYZE TABLE stock_prices");

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> markets = queryForList("""
            SELECT CASE WHEN s.market IN ('SH','SZ') THEN 'A' ELSE s.market END AS market,
                   COUNT(DISTINCT s.id) AS stock_count, COUNT(sp.id) AS price_rows,
                   COALESCE(MAX(sp.trade_date), '-') AS latest_date, COALESCE(MIN(sp.trade_date), '-') AS earliest_date
            FROM stocks s LEFT JOIN stock_prices sp ON sp.stock_id = s.id
            WHERE s.market IN ('SH','SZ','HK','US')
            GROUP BY CASE WHEN s.market IN ('SH','SZ') THEN 'A' ELSE s.market END ORDER BY market""");
        Map<String, Object> idxStats = queryOneMap("""
            SELECT 'IDX' AS market, COUNT(DISTINCT s.id) AS stock_count, COUNT(sp.id) AS price_rows,
                   COALESCE(MAX(sp.trade_date), '-') AS latest_date, COALESCE(MIN(sp.trade_date), '-') AS earliest_date
            FROM stocks s LEFT JOIN stock_prices sp ON sp.stock_id = s.id
            WHERE s.market IN ('JP','KR','GB','DE','FR','TW','SG','IN','AU','CA','BR','IDX','CMD','CCY')""");
        if (idxStats != null) markets.add(idxStats);
        result.put("markets", markets);

        long totalStocks = 0, totalRows = 0;
        for (var m : markets) { totalStocks += ((Number) m.get("stock_count")).longValue(); totalRows += ((Number) m.get("price_rows")).longValue(); }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("stock_count", totalStocks); totals.put("price_rows", totalRows);
        result.put("totals", totals);

        List<Map<String, Object>> tables = queryForList("""
            SELECT table_name, ROUND(data_length/1024/1024, 1) AS data_mb,
                   ROUND(index_length/1024/1024, 1) AS index_mb,
                   ROUND((data_length+index_length)/1024/1024, 1) AS total_mb, table_rows
            FROM information_schema.TABLES WHERE table_schema = DATABASE()
            ORDER BY (data_length+index_length) DESC""");
        result.put("tables", tables);
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleGetUsers(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("[{\"error\":\"unauthorized\"}]"); return; }
        resp.getWriter().write(gson.toJson(queryForList("""
            SELECT u.id, u.username, u.email, u.created_at,
                   (SELECT COUNT(*) FROM transactions t JOIN portfolios p ON t.portfolio_id = p.id WHERE p.user_id = u.id) AS txn_count,
                   (SELECT COUNT(*) FROM portfolios WHERE user_id = u.id) AS portfolio_count
            FROM users u ORDER BY u.id""")));
    }

    public void handleImpersonate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }
        long userId = Long.parseLong((String) req.getAttribute("userId"));
        List<Map<String, Object>> userRow = queryForList("SELECT id, username FROM users WHERE id = ?", userId);
        if (userRow.isEmpty()) { resp.getWriter().write("{\"error\":\"user not found\"}"); return; }
        List<Map<String, Object>> portfolios = queryForList("SELECT id FROM portfolios WHERE user_id = ? ORDER BY id LIMIT 1", userId);

        HttpSession session = req.getSession();
        session.setAttribute("userId", userId);
        session.setAttribute("username", userRow.get(0).get("username"));
        if (!portfolios.isEmpty()) session.setAttribute("portfolioId", ((Number) portfolios.get(0).get("id")).longValue());
        resp.getWriter().write("{\"status\":\"ok\",\"username\":\"" + userRow.get(0).get("username") + "\"}");
    }

    public void handleDeleteUser(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }
        long userId = Long.parseLong((String) req.getAttribute("userId"));
        HttpSession s = req.getSession(false);
        if (s != null && Long.valueOf(userId).equals(s.getAttribute("userId"))) {
            resp.getWriter().write("{\"error\":\"不能删除自己\"}"); return; }
        update("DELETE FROM daily_portfolio_value WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        update("DELETE FROM dividends WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        update("DELETE FROM transactions WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        update("DELETE FROM holdings WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        update("DELETE FROM cash_balances WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        update("DELETE FROM watchlist WHERE user_id = ?", userId);
        update("DELETE FROM ai_settings WHERE user_id = ?", userId);
        update("DELETE FROM ai_chat_history WHERE user_id = ?", userId);
        update("DELETE FROM backtest_results WHERE user_id = ?", userId);
        update("DELETE FROM backtest_strategies WHERE user_id = ?", userId);
        update("DELETE FROM portfolios WHERE user_id = ?", userId);
        update("DELETE FROM users WHERE id = ?", userId);
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    public void handleGetCrawlHistory(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("[{\"error\":\"unauthorized\"}]"); return; }
        resp.getWriter().write(gson.toJson(queryForList("""
            SELECT m.market, m.started_at, m.ended_at, m.rows_written, m.stocks_failed, m.status
            FROM crawl_history m INNER JOIN (SELECT market, MAX(id) AS max_id FROM crawl_history GROUP BY market) latest ON m.id = latest.max_id
            ORDER BY m.market""")));
    }

    public void handleCrawlStatus(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }
        resp.getWriter().write(gson.toJson(session.getStatus()));
    }

    public void handleStartCrawl(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setHeader("X-Accel-Buffering", "no");
        resp.setHeader("Cache-Control", "no-cache");
        String market = (String) req.getAttribute("market");
        String start = req.getParameter("start");
        String end = req.getParameter("end");
        String reconnectStr = req.getParameter("reconnect");
        boolean reconnect = "true".equals(reconnectStr);

        SseClient client = new SseClient(resp);
        client.init();

        if (!checkAdmin(req)) { client.send("error", Map.of("msg", "未授权")); client.complete(); return; }

        if (!List.of("a", "sh", "sz", "hk", "us", "idx", "all").contains(market)) {
            client.send("error", Map.of("msg", "无效市场: " + market)); client.complete(); return; }

        if (reconnect && session.isActive() && market.equals(session.getMarket())) {
            SseClient subClient = session.subscribe(resp);
            req.startAsync();
            while (!subClient.isCompleted()) { try { Thread.sleep(1000); } catch (InterruptedException e) { break; } }
            return;
        }

        Map<String, String> LABELS = Map.of("all", "全市场", "a", "A股", "sh", "A股(沪)", "sz", "A股(深)", "hk", "港股", "us", "美股", "idx", "指数");
        String label = LABELS.getOrDefault(market, market.toUpperCase());
        final String startDate = start.isBlank() ? java.time.LocalDate.now().minusDays(10).toString() : start;
        final String endDate = end.isBlank() ? java.time.LocalDate.now().toString() : end;

        session.startSession(market, label, startDate, endDate);
        SseClient subClient = session.subscribe(resp);

        executor.submit(() -> {
            try {
                File script = new File("script/fetch_stocks.py");
                if (!script.exists()) script = new File("../script/fetch_stocks.py").getCanonicalFile();
                if (!script.exists()) { session.emitError("脚本未找到: " + script.getAbsolutePath()); session.clearSession(); return; }
                File scriptDir = script.getParentFile();
                boolean isFirstStart = true;

                while (!stopRequested) {
                    if (isFirstStart) { session.emitStatus(String.format("启动 %s 抓取 (%s ~ %s)...", label, startDate, endDate), market); isFirstStart = false; }

                    ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u", script.getAbsolutePath(), "-m", market, "--start", startDate, "--end", endDate);
                    pb.directory(scriptDir); pb.redirectErrorStream(true); pb.environment().put("PYTHONUNBUFFERED", "1");

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
                                prog.put("total", Integer.parseInt(m.group(2)));
                                prog.put("pct", Double.parseDouble(m.group(3)));
                                prog.put("name", m.group(4).trim());
                                session.updateProgress(prog);
                            } else if (line.contains("===") || line.contains("完成")) {
                                session.emitInfo(line.replaceFirst("^.*?INFO\\s*", "").trim());
                            } else { session.addLog(line.trim()); }
                        }
                    }
                    boolean finished = p.waitFor(30, TimeUnit.MINUTES);
                    if (!finished) { p.destroyForcibly(); session.emitError(label + " 抓取超时（30分钟），已终止"); }
                    int exitCode = finished ? p.exitValue() : -1;
                    currentProcess = null;

                    if (stopRequested) { session.emitStopped(market, label + " 抓取已停止"); break; }
                    if (exitCode == 0) { session.emitDone(market, label + " 抓取完成"); break; }

                    if (pauseRequested) {
                        session.emitInfo("已暂停");
                        synchronized (pauseLock) {
                            while (pauseRequested && !stopRequested) { try { pauseLock.wait(1000); } catch (InterruptedException e) { break; } }
                        }
                        if (stopRequested) { session.emitStopped(market, label + " 抓取已停止"); break; }
                        session.emitInfo("继续抓取...");
                        continue;
                    }
                    session.emitError("脚本退出码: " + exitCode);
                    break;
                }
            } catch (Exception e) { session.emitError(e.getMessage()); }
            finally { stopRequested = false; pauseRequested = false; currentProcess = null; session.clearSession(); }
        });

        req.startAsync();
        while (!subClient.isCompleted()) { try { Thread.sleep(1000); } catch (InterruptedException e) { break; } }
    }

    public void handleStopCrawl(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }
        stopRequested = true;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        Process p = currentProcess;
        if (p != null) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); }
        resp.getWriter().write("{\"status\":\"stopping\"}");
    }

    public void handleClearCrawlHistory(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }
        int deleted = update("DELETE FROM crawl_history");
        resp.getWriter().write("{\"status\":\"ok\",\"deleted\":" + deleted + "}");
    }

    public void handlePauseCrawl(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }
        if (currentProcess == null) { resp.getWriter().write("{\"status\":\"no_process\"}"); return; }
        pauseRequested = true;
        currentProcess.destroyForcibly();
        resp.getWriter().write("{\"status\":\"paused\"}");
    }

    public void handleResumeCrawl(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        if (!checkAdmin(req)) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }
        if (currentProcess == null) { resp.getWriter().write("{\"status\":\"no_process\"}"); return; }
        synchronized (pauseLock) { pauseRequested = false; pauseLock.notifyAll(); }
        resp.getWriter().write("{\"status\":\"resumed\"}");
    }

    // ── DB helpers ────────────────────────────────────────────────────────

    private int update(String sql, Object... params) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private Map<String, Object> queryOneMap(String sql, Object... params) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ResultSetMetaData rsmd = rs.getMetaData();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rsmd.getColumnCount(); i++) row.put(rsmd.getColumnLabel(i), rs.getObject(i));
                    return row;
                }
            }
        } catch (SQLException e) { return null; }
        return null;
    }

    private List<Map<String, Object>> queryForList(String sql, Object... params) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int colCount = rsmd.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) row.put(rsmd.getColumnLabel(i), rs.getObject(i));
                    results.add(row);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return results;
    }
}
