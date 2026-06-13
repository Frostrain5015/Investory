package com.investory.controller.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.investory.server.DatabaseManager;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class MarketIndexController {

    private static final Logger log = Logger.getLogger(MarketIndexController.class.getName());

    private final ExecutorService indexExecutor;
    private final Semaphore yahooSemaphore = new Semaphore(7, true);

    private static final long TTL_MS = 90_000;
    private static final int TOTAL_BUDGET_SEC = 18;
    private static final int CURL_MAX_TIME_SEC = 6;

    private volatile Snapshot cache;
    private final Object refreshLock = new Object();

    public MarketIndexController() {
        this.indexExecutor = Executors.newCachedThreadPool();
    }

    private enum Source { SINA, YAHOO }

    private record IndexSpec(Source source, String fetchSymbol, String name, String flag,
                             double lat, double lng, String dbSymbol, boolean indicator) {}

    private record Snapshot(List<Map<String, Object>> indices,
                            List<Map<String, Object>> indicators, long builtAt) {
        boolean isStale() { return System.currentTimeMillis() - builtAt > TTL_MS; }
    }

    private static final List<IndexSpec> SPECS = List.of(
        // ── Country indices ──────────────────────────────────────────
        new IndexSpec(Source.SINA,  "s_sh000001", "上证指数",   "CN", 31.23, 121.47, "000001.SH", false),
        new IndexSpec(Source.SINA,  "s_sz399001", "深证成指",   "CN", 31.23, 121.47, "399001.SZ", false),
        new IndexSpec(Source.SINA,  "s_sz399006", "创业板指",   "CN", 31.23, 121.47, "399006.SZ", false),
        new IndexSpec(Source.YAHOO, "^HSI",       "恒生指数",   "HK", 22.30, 114.17, "HSI.HK",    false),
        new IndexSpec(Source.YAHOO, "^HSCE",      "国企指数",   "HK", 22.30, 114.17, "HSCE.HK",   false),
        new IndexSpec(Source.YAHOO, "HSTECH.HK",  "恒生科技",   "HK", 22.30, 114.17, "HSTECH.HK", false),
        new IndexSpec(Source.YAHOO, "^GSPC",      "标普500",    "US", 40.71, -74.01, "GSPC.US",   false),
        new IndexSpec(Source.YAHOO, "^DJI",       "道琼斯",     "US", 40.71, -74.01, "DJI.US",    false),
        new IndexSpec(Source.YAHOO, "^IXIC",      "纳斯达克",   "US", 40.71, -74.01, "IXIC.US",   false),
        new IndexSpec(Source.YAHOO, "^N225",      "日经225",    "JP", 35.68, 139.76, "N225.JP",   false),
        new IndexSpec(Source.YAHOO, "^KS11",      "韩国KOSPI",  "KR", 37.57, 126.98, "KS11.KR",   false),
        new IndexSpec(Source.YAHOO, "^FTSE",      "富时100",    "GB", 52.70, -1.80,  "FTSE.GB",   false),
        new IndexSpec(Source.YAHOO, "^GDAXI",     "德国DAX",    "DE", 52.52, 13.40,  "GDAXI.DE",  false),
        new IndexSpec(Source.YAHOO, "^FCHI",      "法国CAC40",  "FR", 47.50, 4.00,   "FCHI.FR",   false),
        new IndexSpec(Source.YAHOO, "^TWII",      "台湾加权",   "TW", 25.03, 121.57, "TWII.TW",   false),
        new IndexSpec(Source.YAHOO, "^STI",       "新加坡STI",  "SG", 1.35,  103.82, "STI.SG",    false),
        new IndexSpec(Source.YAHOO, "^BSESN",     "印度SENSEX", "IN", 28.61, 77.23,  "BSESN.IN",  false),
        new IndexSpec(Source.YAHOO, "^AXJO",      "澳洲ASX200", "AU", -35.28, 149.13, "AXJO.AU",  false),
        new IndexSpec(Source.YAHOO, "^GSPTSE",    "加拿大TSX",  "CA", 49.28, -123.12, "GSPTSE.CA", false),
        new IndexSpec(Source.YAHOO, "^BVSP",      "巴西Bovespa", "BR", -15.80, -47.86, "BVSP.BR",  false),
        // ── Global indicators ───────────────────────────────────────
        new IndexSpec(Source.YAHOO, "DX-Y.NYB",   "美元指数",    null, 0, 0, "DXY.IDX", true),
        new IndexSpec(Source.YAHOO, "GC=F",       "黄金/美元",   null, 0, 0, "XAU.CMD", true),
        new IndexSpec(Source.YAHOO, "BTC-USD",    "比特币/美元", null, 0, 0, "BTC.CCY", true),
        new IndexSpec(Source.YAHOO, "CL=F",       "WTI 原油",    null, 0, 0, "CL.CMD",  true)
    );

    public void handleGetIndices(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Snapshot snap = cache;
        if (snap == null || snap.isStale()) {
            synchronized (refreshLock) {
                if (cache == null || cache.isStale()) cache = buildSnapshot();
                snap = cache;
            }
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("indices", snap.indices(), "indicators", snap.indicators())));
    }

    private Snapshot buildSnapshot() {
        List<Future<Map<String, Object>>> futures = new ArrayList<>(SPECS.size());
        for (IndexSpec s : SPECS) futures.add(indexExecutor.submit(() -> fetchLive(s)));

        List<Map<String, Object>> indices = new ArrayList<>();
        List<Map<String, Object>> indicators = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TOTAL_BUDGET_SEC);
        for (int i = 0; i < SPECS.size(); i++) {
            IndexSpec s = SPECS.get(i);
            Map<String, Object> result;
            try {
                long remain = deadline - System.nanoTime();
                result = futures.get(i).get(Math.max(0, remain), TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                futures.get(i).cancel(true);
                result = baseMap(s);
                fillback(result, s);
            }
            if (s.indicator()) indicators.add(result); else indices.add(result);
        }
        return new Snapshot(List.copyOf(indices), List.copyOf(indicators), System.currentTimeMillis());
    }

    // ── Per-symbol fetch (live, with per-symbol DB fallback on failure) ──

    private Map<String, Object> baseMap(IndexSpec s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        if (!s.indicator()) { m.put("flag", s.flag()); m.put("lat", s.lat()); m.put("lng", s.lng()); }
        m.put("symbol", s.dbSymbol());
        return m;
    }

    private Map<String, Object> fetchLive(IndexSpec s) {
        Map<String, Object> m = baseMap(s);
        try {
            if (s.source() == Source.SINA) {
                String body = sinaGet("https://hq.sinajs.cn/list=" + s.fetchSymbol());
                int eq = body.indexOf('"');
                if (eq >= 0) {
                    String[] f = body.substring(eq + 1, body.lastIndexOf('"')).split(",");
                    if (f.length >= 4) {
                        m.put("price",     new BigDecimal(f[1]));
                        m.put("change",    new BigDecimal(f[2]));
                        m.put("changePct", new BigDecimal(f[3]));
                        m.put("fetchedAt", java.time.Instant.now().toString());
                    }
                }
            } else {
                String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + s.fetchSymbol() + "?range=1d&interval=5m";
                String body = yahooGet(url);
                JsonObject meta = JsonParser.parseString(body).getAsJsonObject()
                        .getAsJsonObject("chart").getAsJsonArray("result")
                        .get(0).getAsJsonObject().getAsJsonObject("meta");
                BigDecimal price = meta.get("regularMarketPrice").getAsBigDecimal();
                BigDecimal prev  = meta.get("previousClose").getAsBigDecimal();
                m.put("price",     price);
                m.put("change",    price.subtract(prev));
                m.put("changePct", price.subtract(prev).divide(prev, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
                m.put("fetchedAt", java.time.Instant.now().toString());
            }
        } catch (Exception e) {
            log.warning("live fetch " + s.dbSymbol() + " failed: " + e.getMessage());
        }
        if (!m.containsKey("price")) fillback(m, s);
        return m;
    }

    private void fillback(Map<String, Object> m, IndexSpec s) {
        if (s.indicator()) fillFromHistoryIndicators(m, s.dbSymbol());
        else fillFromHistory(m, s.dbSymbol());
    }

    private String sinaGet(String url) throws Exception {
        java.net.URL u = new java.net.URL(url);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
        conn.setRequestProperty("Referer", "https://finance.sina.com.cn");
        conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
        try {
            return new String(conn.getInputStream().readAllBytes());
        } finally {
            conn.disconnect();
        }
    }

    private String yahooGet(String url) throws Exception {
        yahooSemaphore.acquire();
        Process p = null;
        try {
            String proxy = "socks5h://" + System.getProperty("socksProxyHost", "127.0.0.1")
                    + ":" + System.getProperty("socksProxyPort", "7897");
            ProcessBuilder pb = new ProcessBuilder("curl", "-x", proxy, "-s",
                    "--max-time", String.valueOf(CURL_MAX_TIME_SEC),
                    "-H", "User-Agent: Mozilla/5.0", url);
            pb.redirectErrorStream(true);
            p = pb.start();
            String body = new String(p.getInputStream().readAllBytes());
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); throw new Exception("curl timed out after 30s"); }
            int exit = p.exitValue();
            if (exit != 0 || body.isEmpty()) throw new Exception("curl exit " + exit);
            return body;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
            yahooSemaphore.release();
        }
    }

    // ── DB fallback: compute change from last 2 closes in stock_prices ──

    private String toStockSymbol(String dbSymbol) {
        if (dbSymbol == null || !dbSymbol.contains(".")) return dbSymbol;
        String suffix = dbSymbol.substring(0, dbSymbol.lastIndexOf('.'));
        String market = dbSymbol.substring(dbSymbol.lastIndexOf('.') + 1);
        String prefix = switch (market) {
            case "SH" -> "1"; case "SZ" -> "2"; case "HK" -> "116"; case "US" -> "105";
            case "JP" -> "3"; case "KR" -> "6"; case "GB" -> "7"; case "DE" -> "8";
            case "FR" -> "9"; case "TW" -> "10"; case "SG" -> "11"; case "IN" -> "12";
            case "AU" -> "13"; case "CA" -> "14"; case "BR" -> "15";
            default -> null;
        };
        return prefix != null ? prefix + "." + suffix : dbSymbol;
    }

    private void fillFromHistory(Map<String, Object> m, String dbSymbol) {
        String stockSymbol = toStockSymbol(dbSymbol);
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT sp.close FROM stock_prices sp JOIN stocks s ON s.id = sp.stock_id " +
                "WHERE s.symbol = ? ORDER BY sp.trade_date DESC LIMIT 2")) {
            ps.setObject(1, stockSymbol);
            try (ResultSet rs = ps.executeQuery()) {
                List<BigDecimal> closes = new ArrayList<>();
                while (rs.next()) closes.add((BigDecimal) rs.getObject("close"));
                if (closes.size() >= 2) {
                    BigDecimal today  = closes.get(0);
                    BigDecimal yest   = closes.get(1);
                    if (today != null && yest != null && yest.compareTo(BigDecimal.ZERO) != 0) {
                        m.put("price",     today);
                        m.put("change",    today.subtract(yest));
                        m.put("changePct", today.subtract(yest).divide(yest, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
                        m.put("fetchedAt", "close");
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
        m.put("price", BigDecimal.ZERO);
    }

    private void fillFromHistoryIndicators(Map<String, Object> m, String dbSymbol) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT sp.close FROM stock_prices sp JOIN stocks s ON s.id = sp.stock_id " +
                "WHERE s.symbol LIKE ? ORDER BY sp.trade_date DESC LIMIT 2")) {
            ps.setObject(1, "%." + dbSymbol);
            try (ResultSet rs = ps.executeQuery()) {
                List<BigDecimal> closes = new ArrayList<>();
                while (rs.next()) closes.add((BigDecimal) rs.getObject("close"));
                if (closes.size() >= 2) {
                    BigDecimal today  = closes.get(0);
                    BigDecimal yest   = closes.get(1);
                    if (today != null && yest != null && yest.compareTo(BigDecimal.ZERO) != 0) {
                        m.put("price",     today);
                        m.put("change",    today.subtract(yest));
                        m.put("changePct", today.subtract(yest).divide(yest, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
                        m.put("fetchedAt", "close");
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
        m.put("price", BigDecimal.ZERO);
    }

    public void handleGetNews(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        try {
            List<Map<String, Object>> result = jdbcQueryForList(
                "SELECT title, source, url, summary, category, score, country_code, published_at FROM world_news ORDER BY score DESC, published_at DESC LIMIT 25");
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(result));
        } catch (Exception e) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
        }
    }

    public void handleGetWorldData(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("static/world.json");
            if (is != null) {
                data.put("world", new String(is.readAllBytes()));
                is.close();
            }
        } catch (Exception ignored) {}
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(data));
    }

    // ── JDBC helpers ─────────────────────────────────────────────────────

    private List<Map<String, Object>> jdbcQueryForList(String sql, Object... args) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= colCount; c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    result.add(row);
                }
            }
        }
        return result;
    }
}
