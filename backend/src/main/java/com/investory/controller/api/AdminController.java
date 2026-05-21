package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final JdbcTemplate jdbc;

    // Parse progress lines like: "  [313/324 96.6%] WMB.US → 3行"
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    public AdminController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private boolean checkAdmin(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus(HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");

        // Refresh InnoDB stats for accurate row counts and disk sizes
        jdbc.execute("ANALYZE TABLE stock_prices");

        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> markets = jdbc.queryForList("""
            SELECT s.market,
                   COUNT(DISTINCT s.id) AS stock_count,
                   COUNT(sp.id)          AS price_rows,
                   COALESCE(MAX(sp.trade_date), '-') AS latest_date,
                   COALESCE(MIN(sp.trade_date), '-') AS earliest_date
            FROM stocks s
            LEFT JOIN stock_prices sp ON sp.stock_id = s.id
            WHERE s.market IN ('SH','SZ','HK','US')
            GROUP BY s.market
            ORDER BY FIELD(s.market,'SH','SZ','HK','US')
            """);
        // Add index stats separately (aggregate across JP/KR/GB/.../CMD/CCY)
        Map<String, Object> idxStats = jdbc.queryForMap("""
            SELECT 'IDX' AS market,
                   COUNT(DISTINCT s.id) AS stock_count,
                   COUNT(sp.id)          AS price_rows,
                   COALESCE(MAX(sp.trade_date), '-') AS latest_date,
                   COALESCE(MIN(sp.trade_date), '-') AS earliest_date
            FROM stocks s
            LEFT JOIN stock_prices sp ON sp.stock_id = s.id
            WHERE s.market IN ('JP','KR','GB','DE','FR','TW','SG','IN','AU','CA','BR','IDX','CMD','CCY')
            """);
        if (idxStats != null) {
            markets.add(idxStats);
        }
        result.put("markets", markets);

        Map<String, Object> totals = new LinkedHashMap<>();
        long totalStocks = 0, totalRows = 0;
        for (var m : markets) {
            totalStocks += ((Number) m.get("stock_count")).longValue();
            totalRows += ((Number) m.get("price_rows")).longValue();
        }
        totals.put("stock_count", totalStocks);
        totals.put("price_rows", totalRows);
        result.put("totals", totals);

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

    // ── User management ──────────────────────────────────────────────────

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

    @DeleteMapping("/users/{userId}")
    public Map<String, Object> deleteUser(@PathVariable long userId, HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        // Don't allow deleting yourself
        HttpSession session = req.getSession(false);
        if (session != null && Long.valueOf(userId).equals(session.getAttribute("userId"))) {
            return Map.of("error", "不能删除自己");
        }
        jdbc.update("DELETE FROM daily_portfolio_value WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM dividends WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM transactions WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM holdings WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM cash_balances WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        return Map.of("status", "ok");
    }

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

    @GetMapping("/crawl/{market}")
    public SseEmitter startCrawl(@PathVariable String market,
            @RequestParam(defaultValue = "") String start,
            @RequestParam(defaultValue = "") String end,
            HttpServletRequest req) {
        SseEmitter emitter = new SseEmitter(0L);

        if (!checkAdmin(req)) {
            emit(emitter, "error", Map.of("msg", "未授权"));
            emitter.complete();
            return emitter;
        }

        if (!List.of("a", "hk", "us", "idx", "all").contains(market)) {
            emit(emitter, "error", Map.of("msg", "无效市场: " + market));
            emitter.complete();
            return emitter;
        }

        String label = market.equals("all") ? "全市场" : market.toUpperCase();
        // Use custom date range if provided; fallback to 10 days
        final String startDate = start.isBlank() ? java.time.LocalDate.now().minusDays(10).toString() : start;
        final String endDate = end.isBlank() ? java.time.LocalDate.now().toString() : end;

        executor.submit(() -> {
            try {
                emit(emitter, "status", Map.of("msg",
                    String.format("启动 %s 抓取 (%s ~ %s)...", label, startDate, endDate), "market", market));
                // Try multiple paths: working dir/script (cloud), then ../script (local dev)
                File script = new File("script/fetch_stocks.py");
                if (!script.exists()) {
                    script = new File("../script/fetch_stocks.py").getCanonicalFile();
                }
                if (!script.exists()) {
                    emit(emitter, "error", Map.of("msg", "脚本未找到: " + script.getAbsolutePath()));
                    emitter.complete();
                    return;
                }
                File scriptDir = script.getParentFile();

                ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable, script.getAbsolutePath(),
                    "-m", market, "--start", startDate, "--end", endDate
                );
                pb.directory(scriptDir);
                pb.redirectErrorStream(true);

                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = PROGRESS_RE.matcher(line);
                        if (m.find()) {
                            Map<String, Object> prog = new LinkedHashMap<>();
                            prog.put("current", Integer.parseInt(m.group(1)));
                            prog.put("total",   Integer.parseInt(m.group(2)));
                            prog.put("pct",     Double.parseDouble(m.group(3)));
                            prog.put("name",    m.group(4).trim());
                            emit(emitter, "progress", prog);
                        } else if (line.contains("===") || line.contains("完成")) {
                            emit(emitter, "info", Map.of("msg", line.replaceFirst("^.*?INFO\\s*", "").trim()));
                        } else {
                            emit(emitter, "log", Map.of("msg", line.trim()));
                        }
                    }
                }
                int exitCode = p.waitFor();
                if (exitCode == 0) {
                    emit(emitter, "done", Map.of("market", market, "msg", label + " 抓取完成"));
                } else {
                    emit(emitter, "error", Map.of("msg", "脚本退出码: " + exitCode));
                }
            } catch (Exception e) {
                emit(emitter, "error", Map.of("msg", e.getMessage()));
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    private void emit(SseEmitter emitter, String event, Object data) {
        try {
            String jsonStr = json.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event).data(jsonStr));
        } catch (Exception ignored) {}
    }
}
