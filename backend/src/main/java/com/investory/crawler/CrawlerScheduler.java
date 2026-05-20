package com.investory.crawler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.logging.Logger;

/**
 * Schedules daily close-price syncs via external Python scripts.
 * Scripts are in the project root /script directory and write directly to MySQL.
 */
@Component
public class CrawlerScheduler {

    private static final Logger log = Logger.getLogger(CrawlerScheduler.class.getName());
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String SCRIPT_DIR = "script";

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    // ── A-shares: 15:30 Mon-Fri (BaoStock) ──────────────────────────

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Shanghai")
    public void syncAShares() {
        if (isWeekend()) return;
        runScript("fetch_stocks.py", "A股", "-m", "a");
    }

    // ── HK: 16:30 Mon-Fri (Tencent Finance) ─────────────────────────

    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Shanghai")
    public void syncHKStocks() {
        if (isWeekend()) return;
        runScript("fetch_stocks.py", "港股", "-m", "hk");
    }

    // ── US: 05:00 Tue-Sat (Yahoo Finance) ───────────────────────────

    @Scheduled(cron = "0 0 5 * * TUE-SAT", zone = "Asia/Shanghai")
    public void syncUSStocks() {
        runScript("fetch_stocks.py", "美股", "-m", "us");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private boolean isWeekend() {
        DayOfWeek day = LocalDate.now(SHANGHAI).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private void runScript(String filename, String label, String... args) {
        File script = new File(SCRIPT_DIR, filename);
        if (!script.exists()) {
            log.warning(label + " script not found: " + script.getAbsolutePath());
            return;
        }
        log.info("Starting " + label + " sync: " + filename);
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(pythonExecutable);
            cmd.add(script.getAbsolutePath());
            for (String arg : args) cmd.add(arg);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);
            pb.inheritIO();
            Process p = pb.start();
            p.waitFor();
            log.info(label + " sync completed, exit=" + p.exitValue());
        } catch (Exception e) {
            log.warning(label + " sync error: " + e.getMessage());
        }
    }
}
