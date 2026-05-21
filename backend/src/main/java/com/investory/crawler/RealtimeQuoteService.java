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
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Real-time stock price service using a race pattern:
 * fires requests to EastMoney, Sina, Yahoo, and BaoStock simultaneously,
 * returns the first successful response, and cancels the rest.
 * Never writes to the database.
 */
@Service
public class RealtimeQuoteService {

    private static final Logger log = Logger.getLogger(RealtimeQuoteService.class.getName());
    private final HttpClient http = HttpClient.newHttpClient();

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
            () -> new Quote(fetchFromEastMoney(stock), Instant.now()),
            () -> new Quote(fetchFromSina(stock), Instant.now()),
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

    // ── EastMoney ───────────────────────────────────────────────────────

    private BigDecimal fetchFromEastMoney(Stock stock) throws Exception {
        String url = "https://push2.eastmoney.com/api/qt/stock/get?secid=" + stock.getSymbol() + "&fields=f2";
        String body = httpGet(url);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) throw new Exception("no data");
        return safeDecimal(data, "f2");
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

    // ── Sina ────────────────────────────────────────────────────────────

    private BigDecimal fetchFromSina(Stock stock) throws Exception {
        // Sina only supports Chinese A-shares
        if (!"SH".equals(stock.getMarket()) && !"SZ".equals(stock.getMarket())) {
            throw new Exception("Sina does not support non-A-share stocks");
        }
        String code = getTicker(stock);
        String prefix = "SH".equals(stock.getMarket()) ? "sh" : "sz";
        String url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                + "CN_MarketData.getKLineData?symbol=" + prefix + code
                + "&scale=240&ma=no&datalen=1";
        String body = httpGet(url);
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        if (arr.isEmpty()) throw new Exception("no data");
        JsonObject latest = arr.get(arr.size() - 1).getAsJsonObject();
        return new BigDecimal(latest.get("close").getAsString());
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
        String body = httpGet(url);
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

    private BigDecimal safeDecimal(JsonObject obj, String key) throws Exception {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) throw new Exception(key + " is null");
        return new BigDecimal(el.getAsString());
    }
}
