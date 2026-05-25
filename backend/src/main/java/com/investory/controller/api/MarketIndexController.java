package com.investory.controller.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/market")
public class MarketIndexController {

    private static final Logger log = Logger.getLogger(MarketIndexController.class.getName());
    private final ExecutorService indexExecutor = Executors.newFixedThreadPool(25);

    @Autowired private JdbcTemplate jdbc;

    @GetMapping("/indices")
    public Map<String, Object> getIndices() {
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        // ── Country indices ──────────────────────────────────────────
        futures.add(indexExecutor.submit(() -> fetchSinaIndex("s_sh000001", "上证指数",   "CN", 31.23, 121.47, "000001.SH")));
        futures.add(indexExecutor.submit(() -> fetchSinaIndex("s_sz399001", "深证成指",   "CN", 31.23, 121.47, "399001.SZ")));
        futures.add(indexExecutor.submit(() -> fetchSinaIndex("s_sz399006", "创业板指",   "CN", 31.23, 121.47, "399006.SZ")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^HSI",       "恒生指数",  "HK", 22.30, 114.17, "HSI.HK")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^HSCE",      "国企指数",  "HK", 22.30, 114.17, "HSCE.HK")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("HSTECH.HK",  "恒生科技",  "HK", 22.30, 114.17, "HSTECH.HK")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^GSPC",      "标普500",   "US", 40.71, -74.01, "GSPC.US")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^DJI",       "道琼斯",    "US", 40.71, -74.01, "DJI.US")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^IXIC",      "纳斯达克",  "US", 40.71, -74.01, "IXIC.US")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^N225",      "日经225",   "JP", 35.68, 139.76, "N225.JP")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^KS11",      "韩国KOSPI", "KR", 37.57, 126.98, "KS11.KR")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^FTSE",      "富时100",   "GB", 52.70, -1.80, "FTSE.GB")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^GDAXI",     "德国DAX",   "DE", 52.52, 13.40, "GDAXI.DE")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^FCHI",      "法国CAC40", "FR", 47.50, 4.00, "FCHI.FR")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^TWII",      "台湾加权",   "TW", 25.03, 121.57, "TWII.TW")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^STI",       "新加坡STI", "SG", 1.35, 103.82, "STI.SG")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^BSESN",     "印度SENSEX","IN", 28.61, 77.23, "BSESN.IN")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^AXJO",      "澳洲ASX200","AU", -35.28, 149.13, "AXJO.AU")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^GSPTSE",    "加拿大TSX", "CA", 49.28, -123.12, "GSPTSE.CA")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndex("^BVSP",      "巴西Bovespa","BR", -15.80, -47.86, "BVSP.BR")));
        // ── Global indicators ───────────────────────────────────────
        futures.add(indexExecutor.submit(() -> fetchYahooIndicator("DX-Y.NYB", "美元指数",  "DXY.IDX")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndicator("GC=F",     "黄金/美元", "XAU.CMD")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndicator("BTC-USD",  "比特币/美元","BTC.CCY")));
        futures.add(indexExecutor.submit(() -> fetchYahooIndicator("CL=F",     "WTI 原油",  "CL.CMD")));

        List<Map<String, Object>> indices = new ArrayList<>();
        List<Map<String, Object>> indicators = new ArrayList<>();
        int i = 0;
        for (Future<Map<String, Object>> f : futures) {
            try {
                if (i < 20) indices.add(f.get(4, TimeUnit.SECONDS));
                else indicators.add(f.get(4, TimeUnit.SECONDS));
            } catch (Exception ignored) {}
            i++;
        }
        return Map.of("indices", indices, "indicators", indicators);
    }

    // ── Sina (A-share, direct) ──────────────────────────────────────

    private Map<String, Object> fetchSinaIndex(String sinaSymbol, String name, String flag, double lat, double lng, String dbSymbol) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("flag", flag); m.put("lat", lat); m.put("lng", lng);
        m.put("symbol", dbSymbol);
        try {
            String url = "https://hq.sinajs.cn/list=" + sinaSymbol;
            java.net.URL u = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestProperty("Referer", "https://finance.sina.com.cn");
            conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
            String body = new String(conn.getInputStream().readAllBytes());
            conn.disconnect();
            int eq = body.indexOf('"');
            if (eq >= 0) {
                String[] fields = body.substring(eq + 1, body.lastIndexOf('"')).split(",");
                if (fields.length >= 4) {
                    m.put("price",     new BigDecimal(fields[1]));
                    m.put("change",    new BigDecimal(fields[2]));
                    m.put("changePct", new BigDecimal(fields[3]));
                    m.put("fetchedAt", java.time.Instant.now().toString());
                }
            }
        } catch (Exception ignored) {}
        if (!m.containsKey("price")) fillFromHistory(m, dbSymbol);
        return m;
    }

    private Map<String, Object> fetchYahooIndex(String symbol, String name, String flag, double lat, double lng, String dbSymbol) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("flag", flag); m.put("lat", lat); m.put("lng", lng);
        m.put("symbol", dbSymbol);
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?range=1d&interval=5m";
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
        } catch (Exception e) { log.warning("Yahoo index " + dbSymbol + " failed: " + e.getMessage()); }
        if (!m.containsKey("price")) fillFromHistory(m, dbSymbol);
        return m;
    }

    private Map<String, Object> fetchYahooIndicator(String yfSymbol, String name, String dbSymbol) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("symbol", dbSymbol);
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + yfSymbol + "?range=1d&interval=5m";
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
        } catch (Exception e) { log.warning("Yahoo indicator " + dbSymbol + " failed: " + e.getMessage()); }
        if (!m.containsKey("price")) fillFromHistoryIndicators(m, dbSymbol);
        return m;
    }

    private String yahooGet(String url) throws Exception {
        String proxy = "socks5h://" + System.getProperty("socksProxyHost", "127.0.0.1")
                + ":" + System.getProperty("socksProxyPort", "7897");
        ProcessBuilder pb = new ProcessBuilder("curl", "-x", proxy, "-s", "--max-time", "12",
                "-H", "User-Agent: Mozilla/5.0", url);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String body = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0 || body.isEmpty()) throw new Exception("curl exit " + exit);
        return body;
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
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sp.close FROM stock_prices sp JOIN stocks s ON s.id = sp.stock_id " +
                "WHERE s.symbol = ? ORDER BY sp.trade_date DESC LIMIT 2", stockSymbol);
            if (rows.size() >= 2) {
                BigDecimal today  = (BigDecimal) rows.get(0).get("close");
                BigDecimal yest   = (BigDecimal) rows.get(1).get("close");
                if (today != null && yest != null && yest.compareTo(BigDecimal.ZERO) != 0) {
                    m.put("price",     today);
                    m.put("change",    today.subtract(yest));
                    m.put("changePct", today.subtract(yest).divide(yest, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
                    m.put("fetchedAt", "close");
                    return;
                }
            }
        } catch (Exception ignored) {}
        m.put("price", BigDecimal.ZERO);
    }

    private void fillFromHistoryIndicators(Map<String, Object> m, String dbSymbol) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sp.close FROM stock_prices sp JOIN stocks s ON s.id = sp.stock_id " +
                "WHERE s.symbol LIKE ? ORDER BY sp.trade_date DESC LIMIT 2",
                "%." + dbSymbol);
            if (rows.size() >= 2) {
                BigDecimal today  = (BigDecimal) rows.get(0).get("close");
                BigDecimal yest   = (BigDecimal) rows.get(1).get("close");
                if (today != null && yest != null && yest.compareTo(BigDecimal.ZERO) != 0) {
                    m.put("price",     today);
                    m.put("change",    today.subtract(yest));
                    m.put("changePct", today.subtract(yest).divide(yest, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
                    m.put("fetchedAt", "close");
                    return;
                }
            }
        } catch (Exception ignored) {}
        m.put("price", BigDecimal.ZERO);
    }

    // remaining fillFromHistory, fetchYahooPrice, news, world.json methods unchanged
    // (same as original)
    private BigDecimal fetchYahooPrice(String symbol) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?range=1d&interval=5m";
            String body = yahooGet(url);
            return JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("chart").getAsJsonArray("result")
                    .get(0).getAsJsonObject().getAsJsonObject("meta")
                    .get("regularMarketPrice").getAsBigDecimal();
        } catch (Exception e) { log.warning("Yahoo price " + symbol + " failed: " + e.getMessage()); return BigDecimal.ZERO; }
    }

    @GetMapping("/news")
    public List<Map<String, Object>> getNews() {
        try {
            return jdbc.queryForList(
                "SELECT title, source, url, summary, category, score, country_code, published_at FROM world_news ORDER BY score DESC, published_at DESC LIMIT 25");
        } catch (Exception e) { return List.of(); }
    }

    @GetMapping("/world-data")
    public Map<String, Object> getWorldData() {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("static/world.json");
            if (is != null) {
                data.put("world", new String(is.readAllBytes()));
                is.close();
            }
        } catch (Exception ignored) {}
        return data;
    }
}
