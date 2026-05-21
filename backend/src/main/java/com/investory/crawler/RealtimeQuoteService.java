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
import java.time.Instant;
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

    /** Get the best available real-time price with fetch timestamp. Returns null if all sources fail. */
    public Quote getQuote(Stock stock) {
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

    // ── Sina ────────────────────────────────────────────────────────────

    private BigDecimal fetchFromSina(Stock stock) throws Exception {
        String code = stock.getSymbol().substring(stock.getSymbol().indexOf('.') + 1);
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

    private String toYahooSymbol(Stock stock) {
        String code = stock.getSymbol().substring(stock.getSymbol().indexOf('.') + 1);
        return switch (stock.getMarket()) {
            case "SH" -> code + ".SS";
            case "SZ" -> code + ".SZ";
            case "HK" -> code + ".HK";
            case "US" -> code;
            default   -> code;
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
