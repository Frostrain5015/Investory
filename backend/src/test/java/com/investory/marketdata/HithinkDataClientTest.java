package com.investory.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HithinkDataClientTest {
    private HttpServer server;
    private String baseUrl;
    private String response = "{\"code\":0,\"data\":{\"timestamp\":null,\"item\":[]}}";
    private int status = 200;
    private int delayMs;
    private String path;
    private String query;
    private String key;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            path = exchange.getRequestURI().getPath();
            query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            key = exchange.getRequestHeaders().getFirst("X-api-key");
            if (delayMs > 0) { try { Thread.sleep(delayMs); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 302) exchange.getResponseHeaders().add("Location", baseUrl + "/redirected");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void indexQuotesUseTheDedicatedEndpointAndPreserveNullsAndBatchTime() {
        response = "{\"code\":0,\"data\":{\"timestamp\":1792633654000,\"item\":["
            + "{\"thscode\":\"000001.SH\",\"last_price\":3683.49,\"price_change\":-29.54,\"price_change_ratio_pct\":-0.795577},"
            + "{\"thscode\":\"881101.TI\",\"last_price\":null}]}}";
        var result = client().indexSnapshot(List.of("000001.SH", "881101.TI"));
        assertEquals("/api/a-share-index/prices/snapshot", path);
        assertTrue(query.contains("thscodes=000001.SH,881101.TI"));
        assertEquals("-0.795577", result.items().get(0).changePct().toPlainString());
        assertNull(result.items().get(1).price());
        assertEquals("BATCH", result.timestampScope());
        assertEquals(Instant.ofEpochMilli(1792633654000L), result.providerTimestamp());
        assertThrows(IllegalArgumentException.class, () -> client().indexSnapshot(List.of("GSPC.US")));
        assertEquals(1, requests.get());
    }
    private HithinkDataClient client() { return client(true, "test-secret", 2000); }
    private HithinkDataClient client(boolean enabled, String apiKey, int timeout) {
        return new HithinkDataClient(new HithinkDataProperties(enabled, apiKey, baseUrl, timeout), new ObjectMapper());
    }

    @Test void authenticatesAndNormalizesWithoutInventingNullPricesOrPerSecurityFreshness() {
        response = """
                {"code":0,"request_id":"request-1","data":{"timestamp":1792546269000,"item":[
                  {"thscode":"600519.SH","last_price":1234.567890123456789,"prev_price":1250,
                   "price_change_ratio_pct":-1.234,"volume":null},
                  {"thscode":"000001.SZ","last_price":null,"price_change":null}]}}
                """;
        var result = client().snapshot(List.of("1.600519", "000001.sz", "600519.SH"));
        assertEquals("test-secret", key);
        assertEquals("/api/a-share/prices/snapshot", path);
        assertEquals("thscodes=600519.SH,000001.SZ", query);
        assertEquals("BATCH", result.timestampScope());
        assertEquals(Instant.ofEpochMilli(1792546269000L), result.providerTimestamp());
        assertNotEquals(result.receivedAt(), result.providerTimestamp());
        assertEquals("1234.567890123456789", result.items().get(0).price().toPlainString());
        assertEquals("-1.234", result.items().get(0).changePct().toPlainString());
        assertNull(result.items().get(1).price());
        assertNull(result.items().get(0).volume());
    }

    @Test void validatesParametersBeforeMakingRequests() {
        assertThrows(IllegalArgumentException.class, () -> client().snapshot(List.of()));
        assertThrows(IllegalArgumentException.class, () -> client().snapshot(List.of("600519")));
        assertThrows(IllegalArgumentException.class, () -> client().snapshot(List.of("AAPL.US")));
        assertThrows(IllegalArgumentException.class, () -> client().search("  ", 5));
        assertThrows(IllegalArgumentException.class, () -> client().historical("600519.SH", LocalDate.of(2026, 1, 1), LocalDate.of(2025, 1, 1), "none"));
        assertThrows(IllegalArgumentException.class, () -> client().historical("600519.SH", LocalDate.of(2010, 1, 1), LocalDate.of(2026, 1, 1), "none"));
        assertThrows(IllegalArgumentException.class, () -> client().historical("600519.SH", LocalDate.now(), LocalDate.now(), "auto"));
        assertEquals(0, requests.get());
    }

    @Test void disabledAndUnconfiguredClientsNeverContactProviderOrRevealKey() {
        var disabled = client(false, "test-secret", 1000);
        assertEquals("DATA_DISABLED", assertThrows(HithinkDataClient.DataException.class,
                () -> disabled.snapshot(List.of("600519.SH"))).code());
        assertEquals("DATA_NOT_CONFIGURED", assertThrows(HithinkDataClient.DataException.class,
                () -> client(true, "", 1000).snapshot(List.of("600519.SH"))).code());
        assertFalse(new HithinkDataProperties(true, "test-secret", baseUrl, 1000).toString().contains("test-secret"));
        assertEquals(0, requests.get());
    }

    @Test void checksBusinessErrorsDespiteHttp200AndNeverEchoesProviderBody() {
        response = "{\"code\":2004,\"message\":\"test-secret provider error\"}";
        var error = assertThrows(HithinkDataClient.DataException.class, () -> client().snapshot(List.of("600519.SH")));
        assertEquals("DATA_ACCESS_DENIED", error.code());
        assertFalse(error.toString().contains("test-secret"));
        assertEquals(1, requests.get());
        response = "{\"code\":4001,\"message\":\"throttle\"}";
        assertEquals("DATA_RATE_LIMITED", assertThrows(HithinkDataClient.DataException.class,
                () -> client().snapshot(List.of("600519.SH"))).code());
    }

    @Test void rejectsBrokenEnvelopesAndWrongSecuritiesInsteadOfReturningFakeSuccess() {
        for (String body : List.of("{}", "{\"code\":\"0\",\"data\":{}}", "{\"code\":0,\"data\":{\"item\":null}}",
                "{\"code\":0,\"data\":{\"item\":[{\"thscode\":\"000001.SZ\",\"last_price\":20}]}}")) {
            response = body;
            assertEquals("DATA_INVALID_RESPONSE", assertThrows(HithinkDataClient.DataException.class,
                    () -> client().snapshot(List.of("600519.SH"))).code());
        }
    }

    @Test void usesShanghaiCalendarBoundsAndExplicitAdjustmentForHistoricalData() {
        response = """
                {"code":0,"data":{"timestamp":1792425600000,"item":[
                {"date_ms":1792425600000,"open_price":10.1,"close_price":10.2,"volume":null}]}}
                """;
        var start = LocalDate.of(2026, 10, 20);
        var result = client().historical("600519.SH", start, start, "none");
        assertTrue(query.contains("interval=1d"));
        assertTrue(query.contains("adjust=none"));
        assertTrue(query.contains("start=1792425600000"));
        assertEquals(start, result.items().get(0).date());
        assertEquals("SERIES", result.timestampScope());
        assertEquals("none", result.adjustment());
        assertNull(result.items().get(0).volume());
    }

    @Test void rejectsDatesOutsideWindowAndMismatchedAdjustmentEchoes() {
        response = "{\"code\":0,\"data\":{\"adjust\":\"forward\",\"item\":[]}}";
        assertThrows(HithinkDataClient.DataException.class,
                () -> client().historical("600519.SH", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), "none"));
        response = "{\"code\":0,\"data\":{\"item\":[{\"date_ms\":1792425600000}]}}";
        assertThrows(HithinkDataClient.DataException.class,
                () -> client().historical("600519.SH", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22), "none"));
    }

    @Test void searchesOnlyASharesAndPreservesExchangeAndChineseName() {
        response = """
                {"code":0,"data":{"timestamp":null,"item":[{"thscode":"600519.SH","ticker":"600519",
                "name":"贵州茅台","exchange":"SH","asset_type":"a-share"}]}}
                """;
        var result = client().search("贵州茅台", 2);
        assertTrue(query.contains("q=贵州茅台"));
        assertTrue(query.contains("asset_type=a-share"));
        assertEquals("贵州茅台", result.items().get(0).name());
        assertEquals("SH", result.items().get(0).market());
        assertNull(result.providerTimestamp());
    }

    @Test void doesNotFollowRedirectOrAutomaticallyRetry429() {
        status = 302;
        assertEquals("DATA_UPSTREAM_ERROR", assertThrows(HithinkDataClient.DataException.class,
                () -> client().snapshot(List.of("600519.SH"))).code());
        assertEquals(1, requests.get());
        status = 429;
        assertEquals("DATA_RATE_LIMITED", assertThrows(HithinkDataClient.DataException.class,
                () -> client().snapshot(List.of("600519.SH"))).code());
        assertEquals(2, requests.get());
    }

    @Test void boundsEndToEndRequestWait() {
        delayMs = 1000;
        long began = System.nanoTime();
        assertEquals("DATA_UNAVAILABLE", assertThrows(HithinkDataClient.DataException.class,
                () -> client(true, "test-secret", 100).snapshot(List.of("600519.SH"))).code());
        assertTrue((System.nanoTime() - began) / 1_000_000 < 900);
    }
}
