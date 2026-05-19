package com.investory.controller.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/market")
public class MarketIndexController {

    private final HttpClient http = HttpClient.newHttpClient();

    @GetMapping("/indices")
    public List<Map<String, Object>> getIndices() {
        ExecutorService ex = Executors.newFixedThreadPool(21);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        // China → Shanghai
        futures.add(ex.submit(() -> fetchSinaIndex("s_sh000001", "上证指数",   "CN", 31.23, 121.47)));
        futures.add(ex.submit(() -> fetchSinaIndex("s_sz399001", "深证成指",   "CN", 31.23, 121.47)));
        futures.add(ex.submit(() -> fetchSinaIndex("s_sz399006", "创业板指",   "CN", 31.23, 121.47)));
        // Hong Kong → 香港
        futures.add(ex.submit(() -> fetchYahooIndex("^HSI",       "恒生指数",  "HK", 22.30, 114.17)));
        futures.add(ex.submit(() -> fetchYahooIndex("^HSCE",      "国企指数",  "HK", 22.30, 114.17)));
        futures.add(ex.submit(() -> fetchYahooIndex("HSTECH.HK",  "恒生科技",  "HK", 22.30, 114.17)));
        // US → New York
        futures.add(ex.submit(() -> fetchYahooIndex("^GSPC",      "标普500",   "US", 40.71, -74.00)));
        futures.add(ex.submit(() -> fetchYahooIndex("^DJI",       "道琼斯",    "US", 40.71, -74.00)));
        futures.add(ex.submit(() -> fetchYahooIndex("^IXIC",      "纳斯达克",  "US", 40.71, -74.00)));
        // Japan → Tokyo
        futures.add(ex.submit(() -> fetchYahooIndex("^N225",      "日经225",   "JP", 35.68, 139.76)));
        // Korea → Seoul
        futures.add(ex.submit(() -> fetchYahooIndex("^KS11",      "韩国KOSPI", "KR", 37.57, 126.98)));
        // UK → London
        futures.add(ex.submit(() -> fetchYahooIndex("^FTSE",      "富时100",   "GB", 51.51, -0.13)));
        // Germany → Berlin
        futures.add(ex.submit(() -> fetchYahooIndex("^GDAXI",     "德国DAX",   "DE", 52.52, 13.40)));
        // France → Paris
        futures.add(ex.submit(() -> fetchYahooIndex("^FCHI",      "法国CAC40", "FR", 48.86, 2.35)));
        // Taiwan → Taipei
        futures.add(ex.submit(() -> fetchYahooIndex("^TWII",      "台湾加权",   "TW", 25.03, 121.57)));
        // Singapore
        futures.add(ex.submit(() -> fetchYahooIndex("^STI",       "新加坡STI", "SG", 1.35, 103.82)));
        // India → New Delhi
        futures.add(ex.submit(() -> fetchYahooIndex("^BSESN",     "印度SENSEX","IN", 28.61, 77.23)));
        // Australia → Canberra
        futures.add(ex.submit(() -> fetchYahooIndex("^AXJO",      "澳洲ASX200","AU", -35.28, 149.13)));
        // Canada → Toronto
        futures.add(ex.submit(() -> fetchYahooIndex("^GSPTSE",    "加拿大TSX", "CA", 43.65, -79.38)));
        // Brazil → Brasília
        futures.add(ex.submit(() -> fetchYahooIndex("^BVSP",      "巴西Bovespa","BR", -15.80, -47.86)));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Future<Map<String, Object>> f : futures) {
            try { result.add(f.get(4, TimeUnit.SECONDS)); } catch (Exception ignored) {}
        }
        ex.shutdownNow();
        return result;
    }

    private Map<String, Object> fetchSinaIndex(String code, String name, String flag, double lat, double lng) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("flag", flag);
        m.put("lat", lat);
        m.put("lng", lng);
        try {
            String url = "https://hq.sinajs.cn/list=" + code;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Referer", "https://finance.sina.com.cn/").build();
            String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            String[] parts = body.split("\"");
            if (parts.length >= 2) {
                String[] fields = parts[1].split(",");
                if (fields.length >= 4) {
                    m.put("price",    new BigDecimal(fields[1]));
                    m.put("change",   new BigDecimal(fields[2]));
                    m.put("changePct", new BigDecimal(fields[3]));
                }
            }
        } catch (Exception e) { m.put("price", BigDecimal.ZERO); }
        return m;
    }

    private Map<String, Object> fetchYahooIndex(String symbol, String name, String flag, double lat, double lng) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("flag", flag);
        m.put("lat", lat);
        m.put("lng", lng);
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?range=1d&interval=5m";
            java.net.URL u = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            String body = new String(conn.getInputStream().readAllBytes());
            conn.disconnect();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject meta = root.getAsJsonObject("chart").getAsJsonArray("result")
                .get(0).getAsJsonObject().getAsJsonObject("meta");
            BigDecimal price = meta.get("regularMarketPrice").getAsBigDecimal();
            BigDecimal prev  = meta.get("previousClose").getAsBigDecimal();
            m.put("price", price);
            m.put("change", price.subtract(prev));
            m.put("changePct", price.subtract(prev).divide(prev, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
        } catch (Exception e) { m.put("price", BigDecimal.ZERO); }
        return m;
    }
}
