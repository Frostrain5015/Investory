package com.investory.crawler;

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

    // ── A-shares: 15:30 Mon-Fri (BaoStock) ──────────────────────────

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Shanghai")
    public void syncAShares() {
        if (isWeekend()) return;
        runScript("fetch_a_stock.py", "A股");
    }

    // ── HK: 16:30 Mon-Fri (Tencent Finance) ─────────────────────────

    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Shanghai")
    public void syncHKStocks() {
        if (isWeekend()) return;
        runScript("fetch_hk_stock.py", "港股");
    }

    // ── US: 05:00 Tue-Sat (Yahoo Finance) ───────────────────────────

    @Scheduled(cron = "0 0 5 * * TUE-SAT", zone = "Asia/Shanghai")
    public void syncUSStocks() {
        runScript("fetch_us_stock_yf.py", "美股");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private boolean isWeekend() {
        DayOfWeek day = LocalDate.now(SHANGHAI).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private void runScript(String filename, String label) {
        File script = new File(SCRIPT_DIR, filename);
        if (!script.exists()) {
            log.warning(label + " script not found: " + script.getAbsolutePath());
            return;
        }
        log.info("Starting " + label + " sync: " + filename);
        try {
            ProcessBuilder pb = new ProcessBuilder("python", script.getAbsolutePath());
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);
            pb.inheritIO(); // stdout/stderr go to application log
            Process p = pb.start();
            p.waitFor();
            log.info(label + " sync completed, exit=" + p.exitValue());
        } catch (Exception e) {
            log.warning(label + " sync error: " + e.getMessage());
        }
    }
}
