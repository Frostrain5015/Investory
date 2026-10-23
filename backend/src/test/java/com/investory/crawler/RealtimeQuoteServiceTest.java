package com.investory.crawler;

import com.investory.marketdata.HithinkDataClient;
import com.investory.model.*;
import com.investory.dao.*;
import com.investory.service.HoldingService;
import com.investory.controller.api.WatchlistController;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RealtimeQuoteServiceTest {
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-22T02:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    HithinkDataClient provider;
    LegacyEquityQuoteClient legacy;
    ExecutorService workers;
    MutableClock clock;
    RealtimeQuoteService service;
    @BeforeEach void setup() {
        provider=mock(HithinkDataClient.class); legacy=mock(LegacyEquityQuoteClient.class);
        workers=Executors.newFixedThreadPool(4); clock=new MutableClock();
        when(provider.status()).thenReturn(new HithinkDataClient.Status(true,true,HithinkDataClient.SOURCE));
        when(provider.snapshot(anyList())).thenAnswer(call -> batch(call.getArgument(0)));
        service=new RealtimeQuoteService(provider,legacy,workers,clock);
    }
    @AfterEach void close(){workers.shutdownNow();}
    private Stock stock(String code) {
        Stock stock=new Stock(); stock.setId(1L); stock.setSymbol(code); stock.setName("测试股票");
        stock.setMarket("SH"); stock.setCurrency("CNY"); return stock;
    }
    private HithinkDataClient.Result<HithinkDataClient.Price> batch(List<String> codes) {
        return new HithinkDataClient.Result<>(HithinkDataClient.SOURCE,"test",clock.instant(),"BATCH",clock.instant(),"CNY","none",
            codes.stream().map(code -> new HithinkDataClient.Price(code,new BigDecimal("100"),new BigDecimal("99"),BigDecimal.ONE,
                new BigDecimal("1.01"),null,null,null,null,null)).toList());
    }
    @Test void batchesFiftyDeduplicatesAliasesAndSharesColdConcurrentReads() throws Exception {
        var callers=Executors.newFixedThreadPool(4);
        try {
            List<Stock> stocks=List.of(stock("1.600519"),stock("600519.SH"),stock("600000.SH"));
            for (var future:callers.invokeAll(Collections.<Callable<Map<String,Quote>>>nCopies(4,()->service.getQuotes(stocks)))) {
                var result=future.get(); assertEquals(3,result.size()); assertEquals(result.get("1.600519"),result.get("600519.SH"));
            }
            verify(provider,times(1)).snapshot(List.of("600519.SH","600000.SH"));
            verifyNoInteractions(legacy);
            var more=new ArrayList<Stock>();
            for (int i=100;i<151;i++) more.add(stock(String.format("600%03d.SH",i)));
            service.getQuotes(more);
            verify(provider,times(3)).snapshot(anyList());
        } finally {callers.shutdownNow();}
    }
    @Test void failureCooldownAppliesToNewSymbolsAndRetainedPricesExpire() {
        Stock first=stock("600519.SH"); var original=service.getQuote(first);
        clock.now=clock.now.plusSeconds(31);
        doThrow(new HithinkDataClient.DataException("DATA_RATE_LIMITED","limited")).when(provider).snapshot(anyList());
        var retained=service.getQuote(first);
        assertFalse(retained.live()); assertEquals(original.fetchedAt(),retained.fetchedAt());
        assertNull(service.getQuote(stock("600000.SH")));
        verify(provider,times(2)).snapshot(anyList());
        clock.now=clock.now.plus(Duration.ofDays(2));
        assertNull(service.getQuote(first));
    }
    @Test void metadataCacheNeverFetchesAndExpiresRetainedQuotes() {
        Stock item=stock("600519.SH");
        assertTrue(service.cachedQuotes(List.of(item)).isEmpty());
        verify(provider,never()).snapshot(anyList());
        var quote=service.getQuote(item);
        assertEquals(quote,service.cachedQuotes(List.of(item)).get(item.getSymbol()));
        clock.now=clock.now.plusSeconds(180);
        assertFalse(service.cachedQuotes(List.of(item)).get(item.getSymbol()).live());
        clock.now=clock.now.plus(Duration.ofDays(2));
        assertTrue(service.cachedQuotes(List.of(item)).isEmpty());
        verify(provider,times(1)).snapshot(anyList());
    }
    @Test void partialDataFallsBackOnlyForMissingRowsAndIndicesAreExcluded() {
        doReturn(batch(List.of("600519.SH"))).when(provider).snapshot(anyList());
        var result=service.getQuotes(List.of(stock("600519.SH"),stock("600000.SH"),stock("000001.SH")));
        assertEquals(1,result.size());
        verify(provider).snapshot(List.of("600519.SH","600000.SH"));
        verify(legacy,times(2)).getQuote(any());
        assertNull(RealtimeQuoteService.aShareCode(stock("000001.SH")));
        assertNull(RealtimeQuoteService.aShareCode(stock("510300.SH")));
    }
    @Test void oldBatchAndMarketCloseAreNotAdvertisedAsLive() {
        var old=batch(List.of("600519.SH"));
        doReturn(old).when(provider).snapshot(anyList());
        clock.now=clock.now.plusSeconds(180);
        assertFalse(service.getQuote(stock("600519.SH")).live());
        clock.now=Instant.parse("2026-10-22T08:00:00Z");
        doAnswer(call -> batch(call.getArgument(0))).when(provider).snapshot(anyList());
        assertFalse(service.getQuote(stock("600519.SH")).live());
    }
    @Test void slowResponsesDoNotMakeThirtySecondPollingReuseThePreviousCycle() {
        doAnswer(call -> { clock.now=clock.now.plusSeconds(18); return batch(call.getArgument(0)); }).when(provider).snapshot(anyList());
        service.getQuote(stock("600519.SH"));
        clock.now=clock.now.plusSeconds(12);
        service.getQuote(stock("600519.SH"));
        verify(provider,times(2)).snapshot(anyList());
    }
    @Test void holdingsAndWatchlistShareProviderQuoteAndDoNotUseStaleDatabaseBaseline() {
        Stock stock=stock("1.600519");
        StockDao stocks=mock(StockDao.class); when(stocks.findById(1L)).thenReturn(stock);
        HoldingDao holdings=mock(HoldingDao.class);
        Holding holding=new Holding(); holding.setStockId(1L); holding.setTotalShares(BigDecimal.TEN);
        holding.setAvgCost(new BigDecimal("90")); holding.setDilutedCost(new BigDecimal("90"));
        holding.setTotalInvested(new BigDecimal("900")); holding.setTotalDividends(BigDecimal.ZERO);
        when(holdings.findByPortfolio(1L)).thenReturn(List.of(holding));
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        HoldingService holdingService=new HoldingService();
        ReflectionTestUtils.setField(holdingService,"holdingDao",holdings);
        ReflectionTestUtils.setField(holdingService,"stockDao",stocks);
        ReflectionTestUtils.setField(holdingService,"quoteService",service);
        ReflectionTestUtils.setField(holdingService,"jdbc",jdbc);
        var snapshot=holdingService.getSnapshots(1L).get(0);
        assertEquals(new BigDecimal("100"),snapshot.getNativePrice());
        assertEquals(new BigDecimal("10.00"),snapshot.getChangeToday());
        assertEquals(BigDecimal.ONE,snapshot.getNativeChangeToday());
        assertTrue(snapshot.isPriceLive());
        when(jdbc.queryForList(anyString(),eq(7L))).thenReturn(List.of(new HashMap<>(Map.of("stock_id",1L))));
        WatchlistController watchlist=new WatchlistController();
        ReflectionTestUtils.setField(watchlist,"jdbc",jdbc); ReflectionTestUtils.setField(watchlist,"stockDao",stocks);
        ReflectionTestUtils.setField(watchlist,"quoteService",service);
        var request=new MockHttpServletRequest(); request.getSession().setAttribute("userId",7L);
        var row=watchlist.getWatchlist(request,true).get(0);
        assertEquals(snapshot.getNativePrice(),row.get("price"));
        assertEquals(BigDecimal.ONE,row.get("changeToday"));
        assertEquals(new BigDecimal("1.01"),row.get("changePctToday"));
        holdingService.getSnapshots(1L,false);
        watchlist.getWatchlist(request,false);
        verify(provider,times(1)).snapshot(anyList());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(holdings,never()).upsert(any());
    }
}
