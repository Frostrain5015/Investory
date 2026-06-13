package com.investory.crawler;

import com.investory.dao.PortfolioDao;
import com.investory.model.Portfolio;
import com.investory.server.AppContext;
import com.investory.server.ConfigLoader;
import com.investory.server.DatabaseManager;
import com.investory.server.SchedulerService;
import com.investory.service.PortfolioValueCalculator;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Schedules daily close-price syncs via external Python scripts.
 */
public class CrawlerScheduler {

    private static final Logger log = Logger.getLogger(CrawlerScheduler.class.getName());
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String SCRIPT_DIR = "script";
    private static final Pattern RESULT_JSON_RE = Pattern.compile("RESULT: (\\{.+\\})");
    private static final Pattern SUMMARY_RE = Pattern.compile("写入\\s+(\\d+)\\s+行.*?(\\d+)\\s+只");

    private final CrawlSessionManager sessionManager;
    private final java.net.Proxy socksProxy;
    private final PortfolioValueCalculator valueCalculator;
    private final PortfolioDao portfolioDao;
    private final String pythonExecutable;

    public CrawlerScheduler() {
        this.sessionManager = AppContext.get(CrawlSessionManager.class);
        this.valueCalculator = AppContext.get(PortfolioValueCalculator.class);
        this.portfolioDao = AppContext.get(PortfolioDao.class);
        this.pythonExecutable = ConfigLoader.get("python.executable", "python3");
        this.socksProxy = buildSocksProxy();
    }

    /**
     * Register all scheduled tasks with the SchedulerService.
     * Called once during application initialization.
     */
    public void scheduleAll() {
        // A-shares: 15:30 Mon-Fri
        SchedulerService.scheduleAtFixedRate("A股同步", this::syncAShares,
                secondsUntil(15, 30), 86400);
        // HK: 16:30 Mon-Fri
        SchedulerService.scheduleAtFixedRate("港股同步", this::syncHKStocks,
                secondsUntil(16, 30), 86400);
        // US: 09:00 Tue-Sat
        SchedulerService.scheduleAtFixedRate("美股同步", this::syncUSStocks,
                secondsUntil(9, 0), 86400);
        // Indices: 10:00 daily
        SchedulerService.scheduleAtFixedRate("指数同步", this::syncIndices,
                secondsUntil(10, 0), 86400);
        // Quant Analysis: 02:00 daily
        SchedulerService.scheduleAtFixedRate("量化分析", this::refreshQuantMetrics,
                secondsUntil(2, 0), 86400);
        // Quant Analysis weekly: 03:00 Sunday
        SchedulerService.scheduleAtFixedRate("量化分析(周)", this::refreshQuantMetricsWeekly,
                secondsUntil(3, 0), 86400);
        // Evening refetch: 19:00 Mon-Fri
        SchedulerService.scheduleAtFixedRate("晚间二次抓取", this::eveningRefetch,
                secondsUntil(19, 0), 86400);
        // Backfill portfolios: 19:30 Mon-Fri
        SchedulerService.scheduleAtFixedRate("净值回填", this::backfillAllPortfolios,
                secondsUntil(19, 30), 86400);
        // Daily picks: 19:45 Mon-Fri
        SchedulerService.scheduleAtFixedRate("今日选股", this::populateDailyPicks,
                secondsUntil(19, 45), 86400);
        // Exchange rates: 09:30 daily
        SchedulerService.scheduleAtFixedRate("汇率刷新", this::refreshExchangeRates,
                secondsUntil(9, 30), 86400);
        // News sync: 07:00 daily
        SchedulerService.scheduleAtFixedRate("新闻同步", this::syncNews,
                secondsUntil(7, 0), 86400);
    }

    private static long secondsUntil(int hour, int minute) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(SHANGHAI);
        java.time.ZonedDateTime target = now.withHour(hour).withMinute(minute).withSecond(0);
        if (target.isBefore(now) || target.isEqual(now)) {
            target = target.plusDays(1);
        }
        return java.time.Duration.between(now, target).getSeconds();
    }

    private static java.net.Proxy buildSocksProxy() {
        String host = System.getProperty("socksProxyHost");
        String port = System.getProperty("socksProxyPort", "1080");
        if (host != null && !host.isBlank()) {
            return new java.net.Proxy(java.net.Proxy.Type.SOCKS, new InetSocketAddress(host, Integer.parseInt(port)));
        }
        return java.net.Proxy.NO_PROXY;
    }

    private String yahooGet(String url) throws Exception {
        java.net.URL u = new java.net.URL(url);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection(socksProxy);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        String body = new String(conn.getInputStream().readAllBytes());
        conn.disconnect();
        if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
        return body;
    }

    // ── Scheduled task methods ──────────────────────────────────────────

    public void syncAShares() {
        if (isWeekend()) return;
        runScript("fetch_stocks.py", "a", "A股");
    }

    public void syncHKStocks() {
        if (isWeekend()) return;
        runScript("fetch_stocks.py", "hk", "港股");
    }

    public void syncUSStocks() {
        runScript("fetch_stocks.py", "us", "美股");
    }

    public void syncIndices() {
        runScript("fetch_stocks.py", "idx", "指数");
    }

    public void refreshQuantMetrics() {
        runQuantScript("metrics");
    }

    public void refreshQuantMetricsWeekly() {
        runQuantScript("metrics");
    }

    public void eveningRefetch() {
        if (isWeekend()) return;
        log.info("晚间二次抓取 A股 + 港股");
        runScript("fetch_stocks.py", "a", "A股(二次)");
        runScript("fetch_stocks.py", "hk", "港股(二次)");
    }

    public void backfillAllPortfolios() {
        if (isWeekend()) return;
        log.info("开始回填所有活跃组合的每日净值");
        List<Portfolio> portfolios = portfolioDao.findAll();
        for (Portfolio p : portfolios) {
            try {
                valueCalculator.backfillFrom(p.getId(), LocalDate.now(SHANGHAI).minusDays(5));
            } catch (Exception e) {
                log.warning("回填组合 " + p.getId() + " 净值失败: " + e.getMessage());
            }
        }
        log.info("回填完成，共处理 " + portfolios.size() + " 个组合");
    }

    public void populateDailyPicks() {
        if (isWeekend()) return;
        File script = new File(SCRIPT_DIR, "ai_agent.py");
        if (!script.exists()) script = new File("../script", "ai_agent.py");
        if (!script.exists()) { log.warning("ai_agent.py not found for daily-picks populate"); return; }
        log.info("开始预扫描今日选股并写入缓存");
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u",
                script.getAbsolutePath(), "--mode", "populate-picks");
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) log.info("[picks] " + line);
            }
            boolean finished = p.waitFor(15, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly(); log.warning("今日选股预扫描超时(15min)"); }
            else log.info("今日选股预扫描完成, exit=" + p.exitValue());
        } catch (Exception e) {
            log.warning("今日选股预扫描出错: " + e.getMessage());
        }
    }

    public void refreshExchangeRates() {
        BigDecimal usdCny = fetchYahooRate("USDCNY=X");
        BigDecimal usdHkd = fetchYahooRate("USDHKD=X");
        if (usdCny == null || usdHkd == null) {
            log.warning("汇率刷新失败：Yahoo Finance 无响应，保留现有数据");
            return;
        }
        BigDecimal usdPerCny = BigDecimal.ONE.divide(usdCny, 8, RoundingMode.HALF_UP);
        BigDecimal hkdPerCny = usdHkd.divide(usdCny, 8, RoundingMode.HALF_UP);

        Connection conn = null;
        try {
            conn = DatabaseManager.getConnection();
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM exchange_rates");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO exchange_rates (currency, rate) VALUES (?, ?)")) {
                ps.setString(1, "USD");
                ps.setBigDecimal(2, usdPerCny);
                ps.executeUpdate();
                ps.setString(1, "HKD");
                ps.setBigDecimal(2, hkdPerCny);
                ps.executeUpdate();
            }
            conn.commit();
            log.info(String.format("汇率刷新完成 USD=%.6f HKD=%.6f",
                    usdPerCny.doubleValue(), hkdPerCny.doubleValue()));
        } catch (Exception e) {
            try { if (conn != null) conn.rollback(); } catch (Exception ignored) {}
            log.warning("汇率刷新事务失败: " + e.getMessage());
        } finally {
            try { if (conn != null) { conn.setAutoCommit(true); conn.close(); } } catch (Exception ignored) {}
        }
    }

    public void syncNews() {
        runScriptNoArgs("fetch_news.py", "news", "世界新闻");
    }

    // ── Helper methods ──────────────────────────────────────────────────

    private void runQuantScript(String mode) {
        File script = new File(SCRIPT_DIR, "analyze_quant.py");
        if (!script.exists()) {
            log.warning("analyze_quant.py not found at: " + script.getAbsolutePath());
            return;
        }
        log.info("Starting quant analysis: mode=" + mode);
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u",
                script.getAbsolutePath(), "--mode", mode);
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[quant] " + line);
                }
            }
            boolean finished = p.waitFor(15, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly(); log.warning("Quant analysis " + mode + " timed out after 15min"); }
            int exit = finished ? p.exitValue() : -1;
            log.info("Quant analysis " + mode + " finished, exit=" + exit);
        } catch (Exception e) {
            log.warning("Quant analysis error: " + e.getMessage());
        }
    }

    private BigDecimal fetchYahooRate(String symbol) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?range=1d&interval=5m";
            String body = yahooGet(url);
            JsonObject meta = JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("chart").getAsJsonArray("result")
                    .get(0).getAsJsonObject().getAsJsonObject("meta");
            return meta.get("regularMarketPrice").getAsBigDecimal();
        } catch (Exception e) {
            log.warning("fetchYahooRate(" + symbol + ") 失败: " + e.getMessage());
            return null;
        }
    }

    private void runScriptNoArgs(String filename, String marketCode, String label) {
        File script = new File(SCRIPT_DIR, filename);
        if (!script.exists()) {
            script = new File("../script", filename);
        }
        if (!script.exists()) {
            log.warning(label + " script not found at any path");
            return;
        }
        log.info("Starting " + label + " sync: " + filename);
        LocalDateTime startedAt = LocalDateTime.now();
        long historyId = insertCrawlHistory(marketCode, startedAt);

        StringBuilder logTail = new StringBuilder();
        int rowsWritten = 0;
        int stocksFailed = 0;
        String status = "error";
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, script.getAbsolutePath());
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logTail.append(line).append("\n");
                    Matcher rjm = RESULT_JSON_RE.matcher(line);
                    if (rjm.find()) {
                        JsonObject r = JsonParser.parseString(rjm.group(1)).getAsJsonObject();
                        rowsWritten = r.get("rows_written").getAsInt();
                        stocksFailed = r.get("stocks_failed").getAsInt();
                    }
                    Matcher m = SUMMARY_RE.matcher(line);
                    if (m.find()) {
                        if (rowsWritten == 0) rowsWritten = Integer.parseInt(m.group(1));
                        if (stocksFailed == 0) stocksFailed = Integer.parseInt(m.group(2));
                    }
                }
            }
            boolean finished = p.waitFor(10, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly(); logTail.append("Error: process timed out after 10min\n"); }
            status = (finished && p.exitValue() == 0) ? "ok" : "error";
        } catch (Exception e) {
            logTail.append("Error: ").append(e.getMessage());
            log.warning(label + " sync error: " + e.getMessage());
        }

        String tail = logTail.toString();
        if (tail.length() > 2000) tail = tail.substring(tail.length() - 2000);
        updateCrawlHistory(historyId, LocalDateTime.now(), rowsWritten, stocksFailed, status, tail);
    }

    private long insertCrawlHistory(String marketCode, LocalDateTime startedAt) {
        String sql = "INSERT INTO crawl_history (market, started_at, status) VALUES (?, ?, 'running')";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, marketCode);
            ps.setObject(2, startedAt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (Exception e) {
            log.warning("insertCrawlHistory failed: " + e.getMessage());
        }
        return 0;
    }

    private void updateCrawlHistory(long id, LocalDateTime endedAt, int rowsWritten, int stocksFailed, String status, String logTail) {
        String sql = "UPDATE crawl_history SET ended_at=?, rows_written=?, stocks_failed=?, status=?, log_tail=? WHERE id=?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, endedAt);
            ps.setInt(2, rowsWritten);
            ps.setInt(3, stocksFailed);
            ps.setString(4, status);
            ps.setString(5, logTail);
            ps.setLong(6, id);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warning("updateCrawlHistory failed: " + e.getMessage());
        }
    }

    private boolean isWeekend() {
        DayOfWeek day = LocalDate.now(SHANGHAI).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private void runScript(String filename, String marketCode, String label) {
        File script = new File(SCRIPT_DIR, filename);
        if (!script.exists()) {
            script = new File("../script", filename);
        }
        if (!script.exists()) {
            log.warning(label + " script not found at any path (tried: "
                + new File(SCRIPT_DIR, filename).getAbsolutePath() + ", "
                + new File("../script", filename).getAbsolutePath() + ")");
            return;
        }

        log.info("Starting " + label + " sync: " + filename);
        LocalDateTime startedAt = LocalDateTime.now();
        long historyId = insertCrawlHistory(marketCode, startedAt);

        String startDate = java.time.LocalDate.now().minusDays(3).toString();
        String endDate = java.time.LocalDate.now().toString();
        sessionManager.startSession(marketCode, label, startDate, endDate);
        sessionManager.emitStatus(
            String.format("启动 %s 定时抓取 (%s ~ %s)...", label, startDate, endDate), marketCode);

        StringBuilder logTail = new StringBuilder();
        int[] rowsWritten = {0};
        int[] stocksFailed = {0};
        String[] status = {"error"};

        java.util.regex.Pattern progressRe = java.util.regex.Pattern.compile(
            "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(pythonExecutable);
            cmd.add(script.getAbsolutePath());
            cmd.add("-m");
            cmd.add(marketCode);
            cmd.add("--days");
            cmd.add("3");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logTail.append(line).append("\n");
                    java.util.regex.Matcher pm = progressRe.matcher(line);
                    if (pm.find()) {
                        java.util.Map<String, Object> prog = new java.util.LinkedHashMap<>();
                        prog.put("current", Integer.parseInt(pm.group(1)));
                        prog.put("total",   Integer.parseInt(pm.group(2)));
                        prog.put("pct",     Double.parseDouble(pm.group(3)));
                        prog.put("name",    pm.group(4).trim());
                        sessionManager.updateProgress(prog);
                    } else if (!line.contains("===") && !line.contains("完成")) {
                        sessionManager.addLog(line.trim());
                    }
                    Matcher rjm = RESULT_JSON_RE.matcher(line);
                    if (rjm.find()) {
                        JsonObject r = JsonParser.parseString(rjm.group(1)).getAsJsonObject();
                        rowsWritten[0] = r.get("rows_written").getAsInt();
                        stocksFailed[0] = r.get("stocks_failed").getAsInt();
                    }
                    Matcher m = SUMMARY_RE.matcher(line);
                    if (m.find()) {
                        if (rowsWritten[0] == 0) rowsWritten[0] = Integer.parseInt(m.group(1));
                        if (stocksFailed[0] == 0) stocksFailed[0] = Integer.parseInt(m.group(2));
                    }
                }
            }
            boolean finished = p.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                logTail.append("Error: process timed out after 30min\n");
                log.warning(label + " process timed out after 30min, killed");
                sessionManager.emitError(label + " 抓取超时（30分钟），已终止");
            }
            int exitCode = finished ? p.exitValue() : -1;
            status[0] = (finished && exitCode == 0) ? "ok" : "error";
            log.info(String.format("%s sync completed, exit=%d, rows=%d, failed=%d",
                label, exitCode, rowsWritten[0], stocksFailed[0]));
        } catch (Exception e) {
            logTail.append("Error: ").append(e.getMessage());
            log.warning(label + " sync error: " + e.getMessage());
            sessionManager.emitError(e.getMessage());
        } finally {
            if ("ok".equals(status[0])) {
                sessionManager.emitDone(marketCode, label + " 定时抓取完成");
            }
            sessionManager.clearSession();

            String tail = logTail.toString();
            if (tail.length() > 6000) tail = tail.substring(tail.length() - 6000);
            updateCrawlHistory(historyId, LocalDateTime.now(), rowsWritten[0], stocksFailed[0], status[0], tail);
        }
    }
}
