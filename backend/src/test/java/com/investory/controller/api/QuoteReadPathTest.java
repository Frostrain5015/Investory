package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.HoldingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.investory.crawler.LegacyEquityQuoteClient;
import com.investory.marketdata.HithinkDataClient;
import com.investory.marketdata.HithinkDataProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Real business SQL with NO stock_prices table: accidental fallback must fail. */
class QuoteReadPathTest {
    JdbcTemplate jdbc;
    StockDao stocks;
    HoldingDao holdings;
    RealtimeQuoteService quotes;
    HoldingService service;
    Stock first;
    MockHttpServletRequest request;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:quote_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE stocks(id BIGINT PRIMARY KEY, symbol VARCHAR(30), name VARCHAR(100), market VARCHAR(10), currency VARCHAR(10), name_pinyin VARCHAR(100))");
        jdbc.execute("CREATE TABLE holdings(id BIGINT, portfolio_id BIGINT, stock_id BIGINT, total_shares DECIMAL(20,4), avg_cost DECIMAL(20,4), diluted_cost DECIMAL(20,4), total_invested DECIMAL(20,4), total_dividends DECIMAL(20,4), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE watchlist(id BIGINT, user_id BIGINT, stock_id BIGINT, sort_order INT, created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE exchange_rates(currency VARCHAR(10), rate DECIMAL(20,8))");
        jdbc.update("INSERT INTO stocks VALUES (1,'1.600519','测试股票一','SH','CNY','csgpy'), (2,'0.000001','测试股票二','SZ','CNY','csgpe')");
        jdbc.update("INSERT INTO holdings VALUES (1,1,1,10,90,90,900,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO watchlist VALUES (1,7,1,0,CURRENT_TIMESTAMP),(2,7,2,1,CURRENT_TIMESTAMP)");
        stocks = new StockDao(); ReflectionTestUtils.setField(stocks,"jdbc",jdbc);
        holdings = new HoldingDao(); ReflectionTestUtils.setField(holdings,"jdbc",jdbc);
        first = stocks.findById(1);
        quotes = mock(RealtimeQuoteService.class);
        when(quotes.getQuotes(anyList())).thenReturn(Map.of(first.getSymbol(), quote()));
        when(quotes.getQuote(any())).thenReturn(quote());
        service = new HoldingService();
        ReflectionTestUtils.setField(service,"stockDao",stocks);
        ReflectionTestUtils.setField(service,"holdingDao",holdings);
        ReflectionTestUtils.setField(service,"quoteService",quotes);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);
        request = new MockHttpServletRequest();
        request.getSession().setAttribute("portfolioId",1L);
        request.getSession().setAttribute("userId",7L);
    }

    Quote quote() {
        return new Quote(new BigDecimal("100"), Instant.now(), new BigDecimal("99"), BigDecimal.ONE,
            new BigDecimal("1.01"), "hithink_finance_api", Instant.parse("2026-10-22T07:00:00Z"), false);
    }

    @Test void holdingsNeedOnlyBusinessTablesAndProviderSnapshot() {
        var row=service.getSnapshots(1L).get(0);
        assertEquals(new BigDecimal("100"),row.getNativePrice());
        assertEquals(new BigDecimal("1000.00"),row.getNativeMarketValue());
        assertEquals(new BigDecimal("10.00"),row.getChangeToday());
        assertEquals("hithink_finance_api",row.getPriceSource());
        verify(quotes).getQuotes(anyList());
    }

    @Test void unavailableHoldingsUseMarkedCostAndDoNotInventMovement() {
        when(quotes.getQuotes(anyList())).thenReturn(Map.of());
        var row=service.getSnapshots(1L).get(0);
        assertEquals("cost",row.getPriceSource());
        assertEquals(new BigDecimal("90.0000"),row.getNativePrice());
        assertFalse(row.isPriceLive());
        assertNull(row.getChangeToday());
        assertNull(row.getChangePctToday());
        assertNull(row.getNativeChangeToday());
    }

    @Test void incompleteQuoteDoesNotFillPreviousCloseFromDatabase() {
        when(quotes.getQuotes(anyList())).thenReturn(Map.of(first.getSymbol(),new Quote(new BigDecimal("100"),Instant.now())));
        var row=service.getSnapshots(1L).get(0);
        assertNull(row.getChangePctToday());
        assertNull(row.getChangeToday());
    }

    WatchlistController watchlist() {
        var controller=new WatchlistController();
        ReflectionTestUtils.setField(controller,"jdbc",jdbc);
        ReflectionTestUtils.setField(controller,"stockDao",stocks);
        ReflectionTestUtils.setField(controller,"quoteService",quotes);
        return controller;
    }

    @Test void watchlistBatchesAndKeepsMissingPriceNull() {
        var rows=watchlist().getWatchlist(request,true);
        assertEquals(new BigDecimal("100"),rows.get(0).get("price"));
        assertEquals(BigDecimal.ONE,rows.get(0).get("changeToday"));
        assertNull(rows.get(1).get("price"));
        assertEquals("unavailable",rows.get(1).get("priceSource"));
        assertNull(rows.get(1).get("changePctToday"));
        assertNull(rows.get(1).get("quoteMetrics"));
        verify(quotes).getQuotes(argThat(list -> list.size()==2));
    }

    @Test void metricsSerializeInNativeUnitsWithoutConvertingSharesOrInventingMissingFields() throws Exception {
        var metrics=new Quote.SessionMetrics(new BigDecimal("98.25"),new BigDecimal("105"),null,
            new BigDecimal("99"),new BigDecimal("1234567"),new BigDecimal("123456700"));
        Quote enriched=new Quote(new BigDecimal("100"),Instant.now(),new BigDecimal("99"),BigDecimal.ONE,
            new BigDecimal("1.01"),"hithink_finance_api",Instant.now(),false,metrics);
        when(quotes.getQuotes(anyList())).thenReturn(Map.of(first.getSymbol(),enriched));
        var mapper=new ObjectMapper().findAndRegisterModules();
        var rows=mapper.readTree(mapper.writeValueAsString(watchlist().getWatchlist(request,true)));
        assertEquals(1234567,rows.get(0).path("quoteMetrics").path("volume").asInt());
        assertEquals(123456700,rows.get(0).path("quoteMetrics").path("turnover").asInt());
        assertTrue(rows.get(0).path("quoteMetrics").path("low").isNull());
        assertTrue(rows.get(1).path("quoteMetrics").isNull());
        assertEquals(metrics,service.getSnapshots(1L).get(0).getQuoteMetrics());
        assertEquals(metrics,enriched.withLive(true).metrics());
    }

    @Test void metadataReadsDoNotRequestQuotes() {
        service.getSnapshots(1L,false);
        watchlist().getWatchlist(request,false);
        verify(quotes).cachedQuotes(anyList());
        verifyNoMoreInteractions(quotes);
    }

    @Test void stockSearchUsesBatchAndSerializesTimestampsAndNulls() throws Exception {
        var controller=new StockSearchApiController();
        ReflectionTestUtils.setField(controller,"stockDao",stocks);
        ReflectionTestUtils.setField(controller,"quoteService",quotes);
        var rows=new ObjectMapper().readTree(controller.search("测试股票"));
        assertEquals(2,rows.size());
        var available=rows.get(0).get("price").isNull()?rows.get(1):rows.get(0);
        var missing=rows.get(0).get("price").isNull()?rows.get(0):rows.get(1);
        assertEquals(100,available.get("price").asInt());
        assertEquals("2026-10-22T07:00:00Z",available.get("priceTimestamp").asText());
        assertTrue(missing.get("price").isNull());
        verify(quotes).getQuotes(argThat(list -> list.size()==2));
    }

    @Test void stockDetailAndLightQuoteDoNotFallBackToSql() {
        var controller=new StockController();
        ReflectionTestUtils.setField(controller,"stockDao",stocks);
        ReflectionTestUtils.setField(controller,"holdingDao",holdings);
        ReflectionTestUtils.setField(controller,"transactionDao",mock(TransactionDao.class));
        ReflectionTestUtils.setField(controller,"dividendDao",mock(DividendDao.class));
        ReflectionTestUtils.setField(controller,"quoteService",quotes);
        assertEquals(new BigDecimal("100"),controller.detail(first.getSymbol(),request).get("livePrice"));
        when(quotes.getQuote(any())).thenReturn(null);
        var detail=controller.detail(first.getSymbol(),request);
        assertNull(detail.get("livePrice"));
        assertNull(detail.get("livePriceTs"));
        assertEquals("unavailable",detail.get("priceSource"));
        var light=controller.quote(first.getSymbol());
        assertNull(light.get("price"));
        assertEquals("unavailable",light.get("source"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named="HITHINK_LIVE_TESTS",matches="true")
    void liveQuoteReadPathsWorkWithoutHistoricalPriceTable() throws Exception {
        String key=System.getenv("HITHINK_FINANCE_API_KEY");
        assertNotNull(key,"Inject live API key through the environment");
        var provider=spy(new HithinkDataClient(new HithinkDataProperties(true,key,"https://fuyao.aicubes.cn",5000),new ObjectMapper()));
        var legacy=mock(LegacyEquityQuoteClient.class);
        var workers=Executors.newFixedThreadPool(2);
        try {
            quotes=new RealtimeQuoteService(provider,legacy,workers);
            ReflectionTestUtils.setField(service,"quoteService",quotes);
            var holding=service.getSnapshots(1L).get(0);
            assertTrue(holding.getNativePrice().signum()>0);
            assertEquals("hithink_finance_api",holding.getPriceSource());
            var watched=watchlist().getWatchlist(request,true);
            assertEquals(2,watched.size());
            assertTrue(watched.stream().allMatch(row -> "hithink_finance_api".equals(row.get("priceSource"))));
            var search=new StockSearchApiController();
            ReflectionTestUtils.setField(search,"stockDao",stocks);
            ReflectionTestUtils.setField(search,"quoteService",quotes);
            var results=new ObjectMapper().readTree(search.search("测试股票"));
            assertEquals(2,results.size());
            assertTrue(results.get(0).get("price").asDouble()>0);
            assertTrue(results.get(1).get("price").asDouble()>0);
            var controller=new StockController();
            ReflectionTestUtils.setField(controller,"stockDao",stocks);
            ReflectionTestUtils.setField(controller,"holdingDao",holdings);
            ReflectionTestUtils.setField(controller,"transactionDao",mock(TransactionDao.class));
            ReflectionTestUtils.setField(controller,"dividendDao",mock(DividendDao.class));
            ReflectionTestUtils.setField(controller,"quoteService",quotes);
            assertEquals(holding.getNativePrice(),controller.detail(first.getSymbol(),request).get("livePrice"));
            assertEquals(holding.getNativePrice(),controller.quote(first.getSymbol()).get("price"));
            verifyNoInteractions(legacy);
            verify(provider,times(2)).snapshot(anyList());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='stock_prices'",Integer.class));
            System.out.println("HITHINK_QUOTE_PATHS_LIVE: stocks=2, providerRequests=2, paths=holdings/watchlist/search/detail/quote, historicalPriceTable=absent, businessData=H2Fixtures");
        } finally { workers.shutdownNow(); }
    }
}
