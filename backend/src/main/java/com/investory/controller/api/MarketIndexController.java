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
        ExecutorService ex = Executors.newFixedThreadPool(9);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        futures.add(ex.submit(() -> fetchSinaIndex("s_sh000001", "上证指数",   "CN", 33, 56)));
        futures.add(ex.submit(() -> fetchSinaIndex("s_sz399001", "深证成指",   "CN", 31, 50)));
        futures.add(ex.submit(() -> fetchSinaIndex("s_sz399006", "创业板指",   "CN", 26, 46)));
        futures.add(ex.submit(() -> fetchYahooIndex("^HSI",       "恒生指数",  "HK", 20, 42)));
        futures.add(ex.submit(() -> fetchYahooIndex("^HSCE",      "国企指数",  "HK", 18, 44)));
        futures.add(ex.submit(() -> fetchYahooIndex("HSTECH.HK",  "恒生科技",  "HK", 15, 48)));
        futures.add(ex.submit(() -> fetchYahooIndex("^GSPC",      "标普500",   "US", 60, -25)));
        futures.add(ex.submit(() -> fetchYahooIndex("^DJI",       "道琼斯",    "US", 55, -20)));
        futures.add(ex.submit(() -> fetchYahooIndex("^IXIC",      "纳斯达克",  "US", 50, -15)));

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
