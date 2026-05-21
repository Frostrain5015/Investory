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

    @GetMapping("/crawl/{market}")
    public SseEmitter startCrawl(@PathVariable String market,
            @RequestParam(defaultValue = "36500") int days,
            HttpServletRequest req) {
        SseEmitter emitter = new SseEmitter(0L);

        if (!checkAdmin(req)) {
            emit(emitter, "error", Map.of("msg", "未授权"));
            emitter.complete();
            return emitter;
        }

        if (!List.of("a", "hk", "us", "all").contains(market)) {
            emit(emitter, "error", Map.of("msg", "无效市场: " + market));
            emitter.complete();
            return emitter;
        }

        String label = market.equals("all") ? "全市场" : market.toUpperCase();
        String daysBack = String.valueOf(Math.max(1, Math.min(days, 36500)));

        executor.submit(() -> {
            try {
                emit(emitter, "status", Map.of("msg",
                    String.format("启动 %s 抓取 (近 %s 天)...", label, daysBack), "market", market));
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
                    "-m", market, "--days", daysBack
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
