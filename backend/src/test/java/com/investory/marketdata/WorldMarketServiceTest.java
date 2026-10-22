package com.investory.marketdata;

import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorldMarketServiceTest {
    private HithinkDataClient hithink;
    private LegacyWorldMarketClient legacy;
    private ExecutorService executor;
    private MutableClock clock;
    private WorldMarketService service;
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-22T02:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @BeforeEach void setup() {
        hithink = mock(HithinkDataClient.class); legacy = mock(LegacyWorldMarketClient.class);
        executor = Executors.newFixedThreadPool(4); clock = new MutableClock();
        when(hithink.status()).thenReturn(new HithinkDataClient.Status(true, true, HithinkDataClient.SOURCE));
        when(hithink.indexSnapshot(anyList())).thenReturn(batch("000001.SH", "399001.SZ", "399006.SZ"));
        when(legacy.fetch(any())).thenAnswer(call -> quote(call.getArgument(0), "yahoo"));
        service = new WorldMarketService(hithink, legacy, executor, clock);
    }
    @AfterEach void shutdown() { executor.shutdownNow(); }
    private WorldMarketQuote quote(WorldMarketCatalog.Spec spec, String source) {
        return WorldMarketQuote.of(spec, new BigDecimal("100.12"), new BigDecimal("-2.10"), new BigDecimal("-2.05"),
            source, clock.instant().minusSeconds(20), "EXCHANGE", null, clock.instant());
    }
    private HithinkDataClient.Result<HithinkDataClient.Price> batch(String... symbols) {
        return new HithinkDataClient.Result<>(HithinkDataClient.SOURCE, "request", clock.instant().minusSeconds(10), "BATCH",
            clock.instant(), "CNY", "none", Arrays.stream(symbols).map(symbol -> new HithinkDataClient.Price(symbol,
                new BigDecimal("3000.12"), null, new BigDecimal("-3"), new BigDecimal("-0.1"), null,null,null,null,null)).toList());
    }
    @Test void replacesOnlyThreeVerifiedIndicesWithOneSharedBatch() throws Exception {
        var one = service.snapshot();
        assertEquals(20, one.indices().size()); assertEquals(4, one.indicators().size());
        assertEquals(3, one.indices().stream().filter(q -> HithinkDataClient.SOURCE.equals(q.source())).count());
        assertEquals("BATCH", one.indices().get(0).timestampScope());
        assertEquals("active", one.hithinkStatus());
        assertSame(one, service.snapshot());
        verify(hithink, times(1)).indexSnapshot(List.of("000001.SH", "399001.SZ", "399006.SZ"));
        verify(legacy, never()).fetch(argThat(spec -> spec != null && spec.supportsHithink()));
        verify(legacy, times(21)).fetch(any());
        var callers = Executors.newFixedThreadPool(5);
        try { for (Future<WorldMarketService.Snapshot> result : callers.invokeAll(Collections.<Callable<WorldMarketService.Snapshot>>nCopies(5, service::snapshot))) assertSame(one, result.get()); }
        finally { callers.shutdownNow(); }
        verify(hithink, times(1)).indexSnapshot(anyList());
    }
    @Test void fillsOnlyMissingHithinkRowsWithoutRetryingTheBatch() {
        when(hithink.indexSnapshot(anyList())).thenReturn(batch("000001.SH"));
        var result = service.snapshot();
        assertEquals("partial", result.hithinkStatus());
        assertEquals("hithink_finance_api", result.indices().get(0).source());
        assertEquals("DATA_MISSING_ITEMS", result.indices().get(1).fallbackReason());
        verify(hithink, times(1)).indexSnapshot(anyList());
    }
    @Test void slowRefreshExpiresFromRequestStartForThirtySecondPolling() {
        doAnswer(call -> { clock.now=clock.now.plusSeconds(18); return batch("000001.SH", "399001.SZ", "399006.SZ"); })
            .when(hithink).indexSnapshot(anyList());
        service.snapshot();
        clock.now=clock.now.plusSeconds(12);
        service.snapshot();
        verify(hithink,times(2)).indexSnapshot(anyList());
    }
    @Test void keepsLastGoodQuotesAndTheirOriginalTimesOnFailureThenExpiresThem() {
        var first = service.snapshot();
        clock.now = clock.now.plusSeconds(91);
        when(hithink.indexSnapshot(anyList())).thenThrow(new HithinkDataClient.DataException("DATA_RATE_LIMITED", "limited"));
        doReturn(null).when(legacy).fetch(any());
        var second = service.snapshot();
        assertEquals("fallback", second.hithinkStatus());
        assertEquals("DATA_RATE_LIMITED", second.hithinkError());
        assertEquals(first.indices().get(0).receivedAt(), second.indices().get(0).receivedAt());
        assertEquals(first.indices().get(0).quoteTime(), second.indices().get(0).quoteTime());
        assertEquals("stale", second.indices().get(0).state());
        assertSame(second, service.snapshot());
        verify(hithink, times(2)).indexSnapshot(anyList());
        clock.now = clock.now.plus(Duration.ofDays(2));
        var expired = service.snapshot();
        assertNull(expired.indices().get(0).price());
        assertEquals("unavailable", expired.indices().get(0).state());
    }
    @Test void disabledChannelMakesNoProviderCallAndNullsDoNotBecomeZero() {
        when(hithink.status()).thenReturn(new HithinkDataClient.Status(false, false, HithinkDataClient.SOURCE));
        doReturn(null).when(legacy).fetch(any());
        var result = service.snapshot();
        assertEquals("disabled", result.hithinkStatus());
        assertNull(result.indicators().get(0).price());
        assertNull(result.indicators().get(0).change());
        verify(hithink, never()).indexSnapshot(anyList());
    }
}
