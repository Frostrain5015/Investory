package com.investory.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded read-only provider. No database writes, automatic polling, model or trading calls. */
@Service
public class HithinkDataClient {
    public static final String SOURCE = "hithink_finance_api";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private final HithinkDataProperties settings;
    private final ObjectMapper json;
    private final HttpClient http;

    public HithinkDataClient(HithinkDataProperties settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.timeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record Status(boolean enabled, boolean configured, String source) { }
    public record Result<T>(String source, String requestId, Instant providerTimestamp, String timestampScope,
                            Instant receivedAt, String currency, String adjustment, List<T> items) { }
    public record Price(String symbol, BigDecimal price, BigDecimal previousClose, BigDecimal change,
                        BigDecimal changePct, BigDecimal open, BigDecimal high, BigDecimal low,
                        BigDecimal volume, BigDecimal turnover) { }
    public record Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
                      BigDecimal close, BigDecimal volume, BigDecimal turnover) { }
    public record Instrument(String symbol, String ticker, String name, String market, String assetType) { }
    public static class DataException extends RuntimeException {
        private final String code;
        public DataException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public Status status() { return new Status(settings.enabled(), !settings.apiKey().isBlank(), SOURCE); }

    public Result<Price> snapshot(List<String> symbols) {
        return snapshotFor(symbols, false);
    }

    /** Index endpoints are distinct from equities, even when codes look alike. */
    public Result<Price> indexSnapshot(List<String> symbols) {
        return snapshotFor(symbols, true);
    }

    private Result<Price> snapshotFor(List<String> symbols, boolean index) {
        if (symbols == null || symbols.isEmpty() || symbols.size() > 50) throw new IllegalArgumentException("每次须指定 1–50 个 A 股标的");
        var codes = new LinkedHashSet<String>();
        for (String symbol : symbols) codes.add(index ? normalizeIndexSymbol(symbol) : normalizeSymbol(symbol));
        JsonNode root = get(index ? "/api/a-share-index/prices/snapshot" : "/api/a-share/prices/snapshot", Map.of("thscodes", String.join(",", codes)));
        var prices = new ArrayList<Price>();
        var seen = new LinkedHashSet<String>();
        for (JsonNode row : items(root)) {
            String code;
            try { code = index ? normalizeIndexSymbol(text(row, "thscode")) : normalizeReturnedSymbol(row); }
            catch (IllegalArgumentException error) { throw invalidResponse(); }
            if (!codes.contains(code) || !seen.add(code)) throw invalidResponse();
            prices.add(new Price(code, decimal(row, "last_price"), decimal(row, "prev_price"),
                    decimal(row, "price_change"), decimal(row, "price_change_ratio_pct"),
                    decimal(row, "open_price"), decimal(row, "high_price"), decimal(row, "low_price"),
                    decimal(row, "volume"), decimal(row, "turnover")));
        }
        // The provider timestamp is batch-wide, NOT a guarantee of each security's freshness.
        return result(root, "BATCH", "none", prices);
    }

    public Result<Bar> historical(String symbol, LocalDate start, LocalDate end, String adjustment) {
        String code = normalizeSymbol(symbol);
        if (start == null || end == null || end.isBefore(start) || end.isAfter(start.plusYears(10)))
            throw new IllegalArgumentException("历史行情起止日期须有效，单次跨度不超过十年");
        if (!List.of("none", "forward", "backward").contains(adjustment == null ? "" : adjustment))
            throw new IllegalArgumentException("复权方式须为 none、forward 或 backward");
        var query = new LinkedHashMap<String, String>();
        query.put("thscode", code); query.put("interval", "1d"); query.put("adjust", adjustment);
        query.put("start", String.valueOf(start.atStartOfDay(SHANGHAI).toInstant().toEpochMilli()));
        query.put("end", String.valueOf(end.atStartOfDay(SHANGHAI).toInstant().toEpochMilli()));
        JsonNode root = get("/api/a-share/prices/historical", query);
        // REST's documented response need not echo request parameters. Validate echoes when present.
        JsonNode data = root.path("data");
        if ((data.has("thscode") && !code.equals(data.path("thscode").asText()))
                || (data.has("interval") && !"1d".equals(data.path("interval").asText()))
                || (data.has("adjust") && !adjustment.equals(data.path("adjust").asText()))) throw invalidResponse();
        var bars = new ArrayList<Bar>();
        LocalDate previous = null;
        for (JsonNode row : items(root)) {
            Instant date = timestamp(row, "date_ms");
            if (date == null) throw invalidResponse();
            LocalDate day = date.atZone(SHANGHAI).toLocalDate();
            if (day.isBefore(start) || day.isAfter(end) || (previous != null && !day.isAfter(previous))) throw invalidResponse();
            previous = day;
            bars.add(new Bar(day, decimal(row, "open_price"), decimal(row, "high_price"), decimal(row, "low_price"),
                    decimal(row, "close_price"), decimal(row, "volume"), decimal(row, "turnover")));
        }
        return result(root, "SERIES", adjustment, bars);
    }

    public Result<Instrument> search(String query, int limit) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 100 || limit < 1 || limit > 20)
            throw new IllegalArgumentException("请输入 1–100 字的检索内容，结果数须为 1–20");
        JsonNode root = get("/api/meta/tickers/search", Map.of("q", query.strip(), "asset_type", "a-share", "limit", String.valueOf(limit)));
        var instruments = new ArrayList<Instrument>();
        for (JsonNode row : items(root)) {
            if (instruments.size() >= limit) break;
            instruments.add(new Instrument(normalizeReturnedSymbol(row), text(row, "ticker"), text(row, "name"),
                    text(row, "exchange"), text(row, "asset_type")));
        }
        return result(root, "UNKNOWN", null, instruments);
    }

    public static String normalizeSymbol(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("标的不能为空");
        String code = symbol.strip().toUpperCase(Locale.ROOT);
        if (code.matches("[01]\\.\\d{6}")) code = code.substring(2) + (code.startsWith("1.") ? ".SH" : ".SZ");
        if (!code.matches("\\d{6}\\.(SH|SZ|BJ)")) throw new IllegalArgumentException("请使用完整 A 股代码，例如 600519.SH；可先按名称检索");
        return code;
    }

    public static String normalizeIndexSymbol(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("指数代码不能为空");
        String code = symbol.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("\\d{6}\\.(SH|SZ|TI)"))
            throw new IllegalArgumentException("请使用同花顺支持的完整 A 股指数代码，例如 000001.SH");
        return code;
    }

    private JsonNode get(String path, Map<String, String> params) {
        if (!settings.enabled()) throw new DataException("DATA_DISABLED", "同花顺数据通道未启用");
        if (settings.apiKey().isBlank()) throw new DataException("DATA_NOT_CONFIGURED", "同花顺数据通道尚未配置密钥");
        String query = params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(settings.baseUrl() + path + "?" + query))
                .timeout(Duration.ofMillis(settings.timeoutMs())).header("X-api-key", settings.apiKey())
                .header("Accept", "application/json").GET().build();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> response = pending.get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
            byte[] body = response.body();
            if (response.statusCode() == 429) throw new DataException("DATA_RATE_LIMITED", "同花顺数据请求受限，请稍后重试");
            if (response.statusCode() != 200) throw new DataException("DATA_UPSTREAM_ERROR", "同花顺数据服务暂不可用");
            if (body.length > MAX_BODY_BYTES) throw invalidResponse();
            JsonNode root = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body);
            if (root == null || !root.isObject() || !root.path("code").isIntegralNumber()) throw invalidResponse();
            int code = root.path("code").intValue();
            if (code == 4001) throw new DataException("DATA_RATE_LIMITED", "同花顺数据请求受限，请稍后重试");
            if (code == 2001 || code == 2003 || code == 2004)
                throw new DataException("DATA_ACCESS_DENIED", "同花顺数据密钥或接口权限不满足要求");
            if (code != 0) throw new DataException("DATA_PROVIDER_ERROR", "同花顺数据服务未完成本次查询");
            if (!root.path("data").isObject()) throw invalidResponse();
            return root;
        } catch (InterruptedException error) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new DataException("DATA_INTERRUPTED", "同花顺数据查询已中断");
        } catch (IOException | ExecutionException | TimeoutException error) {
            pending.cancel(true);
            throw new DataException("DATA_UNAVAILABLE", "同花顺数据查询超时、连接失败或响应无效");
        }
    }

    private <T> Result<T> result(JsonNode root, String scope, String adjustment, List<T> rows) {
        return new Result<>(SOURCE, text(root, "request_id"), timestamp(root.path("data"), "timestamp"), scope,
                Instant.now(), "CNY", adjustment, List.copyOf(rows));
    }
    private static JsonNode items(JsonNode root) {
        JsonNode rows = root.path("data").path("item");
        if (!rows.isArray()) throw invalidResponse();
        return rows;
    }
    private static String normalizeReturnedSymbol(JsonNode row) {
        try { return normalizeSymbol(text(row, "thscode")); }
        catch (IllegalArgumentException error) { throw invalidResponse(); }
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalidResponse();
        return value.textValue();
    }
    private static BigDecimal decimal(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalidResponse();
        return value.decimalValue();
    }
    private static Instant timestamp(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalidResponse();
        try { return Instant.ofEpochMilli(value.longValue()); }
        catch (java.time.DateTimeException error) { throw invalidResponse(); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static DataException invalidResponse() { return new DataException("DATA_INVALID_RESPONSE", "同花顺数据返回格式或口径不符合约定"); }
}
