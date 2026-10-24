package com.investory.crawler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.investory.dao.PortfolioDao;
import com.investory.model.Portfolio;
import com.investory.service.PortfolioValueCalculator;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Business maintenance: analysis, portfolio snapshots, news and FX refreshes.
 * Results are saved to the crawl_history table for the admin dashboard.
 */
@Component
public class CrawlerScheduler {

    private static final Logger log = Logger.getLogger(CrawlerScheduler.class.getName());
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String SCRIPT_DIR = "script";

    // Structured JSON summary (new format) — parsed with higher priority
    private static final Pattern RESULT_JSON_RE = Pattern.compile("RESULT: (\\{.+\\})");
    // Legacy summary lines like "港股完成: 写入 962 行，无数据(停牌/错误) 0 只" (fallback)
    private static final Pattern SUMMARY_RE = Pattern.compile(
        "写入\\s+(\\d+)\\s+行.*?(\\d+)\\s+只");

    private final JdbcTemplate jdbc;
    private final java.net.Proxy socksProxy;
    @org.springframework.beans.factory.annotation.Autowired
    private PortfolioValueCalculator valueCalculator;
    @org.springframework.beans.factory.annotation.Autowired
    private PortfolioDao portfolioDao;

    public CrawlerScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.socksProxy = buildSocksProxy();
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

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    // ── Quant Analysis ───────────────────────────────────────────────────

    // 每日 02:00（上海时间）刷新持仓股票的分位数/Beta/波动率缓存
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Shanghai")
    public void refreshQuantMetrics() {
        runQuantScript("metrics");
    }

    // 每周日 03:00 额外全量刷新一次，确保长假后数据不过期
    @Scheduled(cron = "0 0 3 * * SUN", zone = "Asia/Shanghai")
    public void refreshQuantMetricsWeekly() {
        runQuantScript("metrics");
    }

    // ── 每日收盘后回填所有活跃组合的净值 ─────────────────────────────────

    @Scheduled(cron = "0 30 19 * * MON-FRI", zone = "Asia/Shanghai")
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

    // ── Helpers ──────────────────────────────────────────────────────

    // ── Exchange Rate Refresh ─────────────────────────────────────────

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Shanghai")
    @org.springframework.transaction.annotation.Transactional
    public void refreshExchangeRates() {
        BigDecimal usdCny = fetchYahooRate("USDCNY=X");
        BigDecimal usdHkd = fetchYahooRate("USDHKD=X");
        if (usdCny == null || usdHkd == null) {
            log.warning("汇率刷新失败：Yahoo Finance 无响应，保留现有数据");
            return;
        }
        BigDecimal usdPerCny = BigDecimal.ONE.divide(usdCny, 8, RoundingMode.HALF_UP);
        BigDecimal hkdPerCny = usdHkd.divide(usdCny, 8, RoundingMode.HALF_UP);
        jdbc.update("DELETE FROM exchange_rates");
        jdbc.update("INSERT INTO exchange_rates (currency, rate) VALUES ('USD', ?), ('HKD', ?)",
                usdPerCny, hkdPerCny);
        log.info(String.format("汇率刷新完成 USD=%.6f HKD=%.6f",
                usdPerCny.doubleValue(), hkdPerCny.doubleValue()));
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

    // ── News Sync ─────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Shanghai")
    public void syncNews() {
        runScriptNoArgs("fetch_news.py", "news", "世界新闻");
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
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
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
        jdbc.update("UPDATE crawl_history SET ended_at=?, rows_written=?, stocks_failed=?, status=?, log_tail=? WHERE id=?",
                LocalDateTime.now(), rowsWritten, stocksFailed, status, tail, historyId);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Insert a crawl_history row and return its auto-generated id (same connection). */
    private long insertCrawlHistory(String marketCode, LocalDateTime startedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO crawl_history (market, started_at, status) VALUES (?, ?, 'running')",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, marketCode);
            ps.setObject(2, startedAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : 0;
    }

    private boolean isWeekend() {
        DayOfWeek day = LocalDate.now(SHANGHAI).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

}
