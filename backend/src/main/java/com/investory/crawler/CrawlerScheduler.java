package com.investory.crawler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Schedules daily close-price syncs via external Python scripts.
 * Results are saved to the crawl_history table for the admin dashboard.
 */
@Component
public class CrawlerScheduler {

    private static final Logger log = Logger.getLogger(CrawlerScheduler.class.getName());
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String SCRIPT_DIR = "script";

    // Parse summary lines like "港股完成: 写入 962 行，无数据(停牌/错误) 0 只"
    private static final Pattern SUMMARY_RE = Pattern.compile(
        "写入\\s+(\\d+)\\s+行.*?无数据.*?(\\d+)\\s+只");

    private final JdbcTemplate jdbc;

    public CrawlerScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    // ── A-shares: 15:30 Mon-Fri (BaoStock) ──────────────────────────

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Shanghai")
    public void syncAShares() {
        if (isWeekend()) return;
        runScript("fetch_stocks.py", "a", "A股");
    }

    // ── HK: 16:30 Mon-Fri (Yahoo Finance) ───────────────────────────

    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Shanghai")
    public void syncHKStocks() {
        if (isWeekend()) return;
        runScript("fetch_stocks.py", "hk", "港股");
    }

    // ── US: 09:00 Tue-Sat (Yahoo Finance) ───────────────────────────

    @Scheduled(cron = "0 0 9 * * TUE-SAT", zone = "Asia/Shanghai")
    public void syncUSStocks() {
        runScript("fetch_stocks.py", "us", "美股");
    }

    // ── Indices: 10:00 daily (Yahoo + Sina) ──────────────────────────

    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Shanghai")
    public void syncIndices() {
        runScript("fetch_stocks.py", "idx", "指数");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private boolean isWeekend() {
        DayOfWeek day = LocalDate.now(SHANGHAI).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private void runScript(String filename, String marketCode, String label) {
        File script = new File(SCRIPT_DIR, filename);
        if (!script.exists()) {
            log.warning(label + " script not found: " + script.getAbsolutePath());
            return;
        }

        // Try alternate path for local dev
        if (!script.exists()) {
            script = new File("../script", filename);
        }
        if (!script.exists()) {
            log.warning(label + " script not found at any path");
            return;
        }

        log.info("Starting " + label + " sync: " + filename);
        LocalDateTime startedAt = LocalDateTime.now();
        jdbc.update("INSERT INTO crawl_history (market, started_at, status) VALUES (?, ?, 'running')",
            marketCode, startedAt);
        long historyId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        StringBuilder logTail = new StringBuilder();
        int rowsWritten = 0;
        int stocksFailed = 0;
        String status = "error";

        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(pythonExecutable);
            cmd.add(script.getAbsolutePath());
            cmd.add("-m");
            cmd.add(marketCode);
            cmd.add("--days");
            cmd.add("3"); // daily cron fetches last 3 days

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logTail.append(line).append("\n");
                    // Parse summary line
                    Matcher m = SUMMARY_RE.matcher(line);
                    if (m.find()) {
                        rowsWritten = Integer.parseInt(m.group(1));
                        stocksFailed = Integer.parseInt(m.group(2));
                    }
                }
            }
            int exitCode = p.waitFor();
            status = exitCode == 0 ? "ok" : "error";
            log.info(String.format("%s sync completed, exit=%d, rows=%d, failed=%d",
                label, exitCode, rowsWritten, stocksFailed));
        } catch (Exception e) {
            logTail.append("Error: ").append(e.getMessage());
            log.warning(label + " sync error: " + e.getMessage());
        }

        // Trim log tail to last 2000 chars
        String tail = logTail.toString();
        if (tail.length() > 2000) tail = tail.substring(tail.length() - 2000);

        jdbc.update(
            "UPDATE crawl_history SET ended_at=?, rows_written=?, stocks_failed=?, status=?, log_tail=? WHERE id=?",
            LocalDateTime.now(), rowsWritten, stocksFailed, status, tail, historyId);
    }
}
