package com.investory.crawler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.investory.dao.StockDao;
import com.investory.model.Quote;
import com.investory.model.Stock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Real-time stock price service using a race pattern:
 * fires requests to EastMoney, Tencent (A-shares), and Yahoo (global),
 * returns the first successful response, and cancels the rest.
 * Never writes to the database.
 */
@Service
public class RealtimeQuoteService {

    private static final Logger log = Logger.getLogger(RealtimeQuoteService.class.getName());
    // Direct connection for Chinese APIs (EastMoney, Tencent)
    private final HttpClient http = HttpClient.newHttpClient();
    // SOCKS proxy for international APIs (Yahoo)
    private final HttpClient httpWithProxy = buildProxiedClient();

    private static HttpClient buildProxiedClient() {
        String host = System.getProperty("socksProxyHost");
        String port = System.getProperty("socksProxyPort", "1080");
        if (host != null && !host.isBlank()) {
            return HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(host, Integer.parseInt(port))))
                    .build();
        }
        return HttpClient.newHttpClient();
    }

    @Autowired private StockDao stockDao;

    /** Returns true if the given stock's market is currently in a trading session (weekday + session hours). */
    private boolean isMarketOpen(Stock stock) {
        String market = stock.getMarket();
        ZonedDateTime now = switch (market) {
            case "SH", "SZ" -> ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
            case "HK"        -> ZonedDateTime.now(ZoneId.of("Asia/Hong_Kong"));
            case "US"        -> ZonedDateTime.now(ZoneId.of("America/New_York"));
            default          -> null;
        };
        if (now == null) return false;
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        int t = now.getHour() * 60 + now.getMinute();
        return switch (market) {
            // A-shares: 09:30-11:30, 13:00-15:00 CST
            case "SH", "SZ" -> (t >= 570 && t <= 690) || (t >= 780 && t <= 900);
            // HK: 09:30-12:00, 13:00-16:00 HKT
            case "HK"        -> (t >= 570 && t <= 720) || (t >= 780 && t <= 960);
            // US: 09:30-16:00 ET
            case "US"        -> t >= 570 && t <= 960;
            default          -> false;
        };
    }

    /** Get the best available real-time price with fetch timestamp. Returns null if all sources fail. */
    public Quote getQuote(Stock stock) {
        if (!isMarketOpen(stock)) return null;
        List<Callable<Quote>> tasks = List.of(
            () -> new Quote(fetchFromSina(stock), Instant.now()),
            () -> new Quote(fetchFromTencent(stock), Instant.now()),
            () -> new Quote(fetchFromYahoo(stock), Instant.now())
        );
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            return executor.invokeAny(tasks, 3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.fine("All real-time sources failed for " + stock.getSymbol() + ": " + e.getMessage());
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Convenience wrapper — returns only the price, null if all sources fail. */
    public BigDecimal getPrice(Stock stock) {
        Quote q = getQuote(stock);
        return q != null ? q.price() : null;
    }

    // ── Sina (real-time) ────────────────────────────────────────────────

    private BigDecimal fetchFromSina(Stock stock) throws Exception {
        if (!"SH".equals(stock.getMarket()) && !"SZ".equals(stock.getMarket())) {
            throw new Exception("Sina only supports A-shares");
        }
        String code = getTicker(stock);
        String prefix = "SH".equals(stock.getMarket()) ? "sh" : "sz";
        String url = "https://hq.sinajs.cn/list=" + prefix + code;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.sina.com.cn")
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        String body = resp.body();
        int q = body.indexOf('"');
        if (q < 0) throw new Exception("no quote");
        // var hq_str_sh600519="name,open,prevClose,price,high,low,..."
        String[] fields = body.substring(q + 1, body.lastIndexOf('"')).split(",");
        if (fields.length < 4) throw new Exception("not enough fields");
        return new BigDecimal(fields[3]);
    }

    // ── Symbol helpers ───────────────────────────────────────────────────

    /** Extract the raw ticker from the symbol, which may be in either
     *  EastMoney format (\"1.600519\") or human-readable format (\"XPEV.US\"). */
    private static String getTicker(Stock stock) {
        String symbol = stock.getSymbol();
        int dot = symbol.indexOf('.');
        if (dot < 0) return symbol;
        // SH/SZ stocks use EastMoney market-prefix format: market_code.ticker
        if ("SH".equals(stock.getMarket()) || "SZ".equals(stock.getMarket())) {
            return symbol.substring(dot + 1);
        }
        // US/HK stocks use human-readable format: ticker.market
        return symbol.substring(0, dot);
    }

    // ── Tencent ──────────────────────────────────────────────────────────

    private BigDecimal fetchFromTencent(Stock stock) throws Exception {
        // Tencent real-time quote API (China-local, A-shares only)
        if (!"SH".equals(stock.getMarket()) && !"SZ".equals(stock.getMarket())) {
            throw new Exception("Tencent does not support non-A-share stocks");
        }
        String code = getTicker(stock);
        String prefix = "SH".equals(stock.getMarket()) ? "sh" : "sz";
        String url = "https://qt.gtimg.cn/q=" + prefix + code;
        String body = httpGet(url);
        // Response format: v_sh600519="1~name~code~price~prevClose~open~..."
        int quoteIdx = body.indexOf('"');
        if (quoteIdx < 0) throw new Exception("no quote");
        String content = body.substring(quoteIdx + 1, body.lastIndexOf('"'));
        String[] fields = content.split("~");
        if (fields.length < 4) throw new Exception("not enough fields");
        // fields[3]=currentPrice, fields[4]=prevClose
        return new BigDecimal(fields[3]);
    }

    // ── Yahoo Finance ────────────────────────────────────────────────────

    private static String toYahooSymbol(Stock stock) {
        String ticker = getTicker(stock);
        return switch (stock.getMarket()) {
            case "SH" -> ticker + ".SS";
            case "SZ" -> ticker + ".SZ";
            case "HK" -> String.format("%04d", Integer.parseInt(ticker)) + ".HK";
            default   -> ticker;
        };
    }

    private BigDecimal fetchFromYahoo(Stock stock) throws Exception {
        String yfSymbol = toYahooSymbol(stock);
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + yfSymbol + "?range=1d&interval=5m";
        String body = httpGetViaProxy(url);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject chart = root.getAsJsonObject("chart");
        if (chart == null) throw new Exception("no chart");
        JsonArray result = chart.getAsJsonArray("result");
        if (result == null || result.isEmpty()) throw new Exception("no result");
        JsonObject meta = result.get(0).getAsJsonObject().getAsJsonObject("meta");
        if (meta == null) throw new Exception("no meta");
        return meta.get("regularMarketPrice").getAsBigDecimal();
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        return resp.body();
    }

    private String httpGetViaProxy(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .build();
        HttpResponse<String> resp = httpWithProxy.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        return resp.body();
    }

}
