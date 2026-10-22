package com.investory.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.util.ProcessRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.Charset;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Temporary providers for instruments not exposed by the current Hithink contract. */
@Service
public class LegacyWorldMarketClient {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Semaphore yahooLimit = new Semaphore(7, true);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public LegacyWorldMarketClient(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public WorldMarketQuote fetch(WorldMarketCatalog.Spec spec) {
        try {
            WorldMarketQuote quote = spec.source() == WorldMarketCatalog.Source.SINA ? sina(spec) : yahoo(spec);
            return quote.valid() ? quote : null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception error) { return null; }
    }

    private WorldMarketQuote sina(WorldMarketCatalog.Spec spec) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("https://hq.sinajs.cn/list=" + spec.fetchSymbol()))
            .timeout(Duration.ofSeconds(4)).header("Referer", "https://finance.sina.com.cn").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(Charset.forName("GB18030")));
        if (response.statusCode() != 200) throw new IllegalStateException("Sina request failed");
        String body = response.body();
        String[] fields = body.substring(body.indexOf('"') + 1, body.lastIndexOf('"')).split(",");
        // This compact endpoint provides no exchange timestamp. Do not invent one.
        return WorldMarketQuote.of(spec, new BigDecimal(fields[1]), new BigDecimal(fields[2]),
            new BigDecimal(fields[3]), "sina", null, "UNKNOWN", null, Instant.now());
    }

    private WorldMarketQuote yahoo(WorldMarketCatalog.Spec spec) throws Exception {
        yahooLimit.acquire();
        try {
            List<String> command = new ArrayList<>(List.of("curl", "--silent", "--show-error", "--fail",
                "--connect-timeout", "2", "--max-time", "4", "-H", "User-Agent: Mozilla/5.0"));
            String host = System.getProperty("socksProxyHost", "");
            if (!host.isBlank()) {
                command.add("--proxy");
                command.add("socks5h://" + host + ":" + System.getProperty("socksProxyPort", "1080"));
            }
            // No implicit 127.0.0.1:7897. Without an explicit JVM proxy, curl uses
            // its normal direct/environment configuration on the deployment host.
            command.add("https://query1.finance.yahoo.com/v8/finance/chart/" + spec.fetchSymbol() + "?range=1d&interval=5m");
            StringBuilder body = new StringBuilder();
            int exit = ProcessRunner.run(new ProcessBuilder(command), Duration.ofSeconds(6), line -> {
                if (body.length() + line.length() > 1024 * 1024) throw new IllegalArgumentException("Response too large");
                body.append(line);
            });
            if (exit != 0) return null;
            return parseYahoo(spec, json.readTree(body.toString()), Instant.now());
        } finally { yahooLimit.release(); }
    }

    static WorldMarketQuote parseYahoo(WorldMarketCatalog.Spec spec, JsonNode root, Instant receivedAt) {
        JsonNode meta = root.path("chart").path("result").path(0).path("meta");
        BigDecimal price = decimal(meta.get("regularMarketPrice"));
        BigDecimal previous = decimal(meta.get("previousClose"));
        if (previous == null) previous = decimal(meta.get("chartPreviousClose"));
        BigDecimal change = price != null && previous != null ? price.subtract(previous) : null;
        BigDecimal pct = change != null && previous.signum() > 0
            ? change.multiply(BigDecimal.valueOf(100)).divide(previous, 6, RoundingMode.HALF_UP) : null;
        JsonNode timestamp = meta.get("regularMarketTime");
        Instant quoteTime = timestamp != null && timestamp.isIntegralNumber() && timestamp.canConvertToLong()
            ? Instant.ofEpochSecond(timestamp.longValue()) : null;
        return WorldMarketQuote.of(spec, price, change, pct, "yahoo", quoteTime,
            quoteTime == null ? "UNKNOWN" : "EXCHANGE", null, receivedAt);
    }

    public WorldMarketQuote lastClose(WorldMarketCatalog.Spec spec) {
        try {
            // fetch_stocks.py stores index identities as 000001.SH / GSPC.US /
            // DXY.IDX etc. Do not convert them to equity prefixes or use LIKE.
            var rows = jdbc.queryForList("SELECT sp.close, sp.trade_date FROM stock_prices sp JOIN stocks s ON s.id = sp.stock_id "
                + "WHERE s.symbol = ? ORDER BY sp.trade_date DESC LIMIT 2", spec.dbSymbol());
            if (rows.isEmpty()) return null;
            BigDecimal price = number(rows.get(0).get("close"));
            BigDecimal previous = rows.size() > 1 ? number(rows.get(1).get("close")) : null;
            BigDecimal change = price != null && previous != null ? price.subtract(previous) : null;
            BigDecimal pct = change != null && previous.signum() > 0
                ? change.multiply(BigDecimal.valueOf(100)).divide(previous, 6, RoundingMode.HALF_UP) : null;
            Object rawDate = rows.get(0).get("trade_date");
            LocalDate date = rawDate instanceof java.sql.Date day ? day.toLocalDate()
                : rawDate instanceof LocalDate day ? day : rawDate == null ? null : LocalDate.parse(rawDate.toString().substring(0, 10));
            WorldMarketQuote quote = WorldMarketQuote.of(spec, price, change, pct, "database", null, "CLOSE", date, null);
            return quote.valid() ? quote.stale("DATABASE_LAST_CLOSE") : null;
        } catch (Exception error) { return null; }
    }
    private static BigDecimal decimal(JsonNode value) { return value != null && value.isNumber() ? value.decimalValue() : null; }
    private static BigDecimal number(Object value) { return value instanceof Number n ? new BigDecimal(n.toString()) : null; }
}
