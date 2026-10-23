package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.dao.QuantCacheDao;
import com.investory.marketdata.HistoricalPriceService;
import com.investory.model.StockPrice;
import com.investory.service.AiModelConfiguration;
import com.investory.crawler.AiSessionManager;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise real controller business SQL against H2 with NO historical-price table. */
class HistoryReadPathTest {
    QuoteReadPathTest fixture;
    HistoricalPriceService history;
    ExecutorService executor;
    @BeforeEach void setup() {
        fixture=new QuoteReadPathTest(); fixture.setup();
        executor=Executors.newSingleThreadExecutor(); history=mock(HistoricalPriceService.class);
        when(history.range(anyLong(),any(),any(),anyString())).thenAnswer(call -> {
            List<StockPrice> bars=new ArrayList<>();
            for(int i=0;i<40;i++) {
                var bar=new StockPrice(); bar.setTradeDate(LocalDate.of(2026,1,1).plusDays(i));
                var price=BigDecimal.valueOf(100+i*i/100.0);
                bar.setOpen(price);bar.setClose(price);bar.setHigh(price);bar.setLow(price);bar.setVolume(100L); bars.add(bar);
            }
            return bars;
        });
    }
    @AfterEach void cleanup() {
        executor.shutdownNow();
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='stock_prices'",Integer.class));
    }
    @Test void candleAndBenchmarkUseExternalHistoryWithExplicitAdjustment() throws Exception {
        var controller=new ChartDataController();
        ReflectionTestUtils.setField(controller,"stockDao",fixture.stocks);
        ReflectionTestUtils.setField(controller,"history",history);
        String result=controller.chart("price","1.600519",40,"2026-01-01","2026-02-09",null,null,"0.000001",fixture.request,new MockHttpServletResponse());
        var root=new ObjectMapper().readTree(result);
        assertFalse(root.has("error"));
        assertTrue(result.contains("2026-01-01"));
        verify(history).range(eq(1L),any(),any(),eq("forward"));
        verify(history).range(eq(2L),any(),any(),eq("none"));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="INVESTORY_HISTORY_LIVE",matches="true")
    void liveJavaToPythonToHithinkChartNeedsNoPriceTable() throws Exception {
        var gateway=new HistoricalPriceService(fixture.stocks,new ObjectMapper(),"python");
        try {
            var controller=new ChartDataController();
            ReflectionTestUtils.setField(controller,"stockDao",fixture.stocks);
            ReflectionTestUtils.setField(controller,"history",gateway);
            var output=controller.chart("price","1.600519",40,"2026-09-01","2026-10-20",null,null,null,fixture.request,new MockHttpServletResponse());
            var bars=new ObjectMapper().readTree(output);
            assertTrue(bars.isArray()); assertTrue(bars.size()>=25);
            assertEquals("2026-09-01",bars.get(0).get("date").asText());
            assertTrue(bars.get(0).get("close").decimalValue().signum()>0);
        } finally { gateway.close(); }
    }
    QuantApiController quant() {
        var controller=new QuantApiController(fixture.jdbc,mock(QuantCacheDao.class),executor);
        ReflectionTestUtils.setField(controller,"history",history);
        ReflectionTestUtils.setField(controller,"quotes",fixture.quotes);
        return controller;
    }
    @Test void contextSummaryUsesBatchQuoteGatewayWithoutPriceSql() {
        var result=quant().contextSummary(fixture.request);
        assertFalse(result.containsKey("error"));
        assertEquals(1000L,result.get("totalValue"));
        verify(fixture.quotes).getQuotes(anyList());
    }
    @Test void cumulativeReturnsRebuildFromRawPricesWithoutPriceTable() throws Exception {
        fixture.jdbc.execute("CREATE TABLE transactions (portfolio_id BIGINT,stock_id BIGINT,type VARCHAR(32),shares DECIMAL(20,4),price DECIMAL(20,4),fee DECIMAL(20,4),trade_date DATE,currency VARCHAR(8))");
        fixture.jdbc.update("INSERT INTO transactions VALUES (1,NULL,'TRANSFER_IN',1000,0,0,'2026-01-01','CNY'),(1,1,'BUY',10,90,0,'2026-01-02','CNY')");
        var controller=new ChartDataController();
        ReflectionTestUtils.setField(controller,"stockDao",fixture.stocks);
        ReflectionTestUtils.setField(controller,"history",history);
        ReflectionTestUtils.setField(controller,"jdbc",fixture.jdbc);
        var dividends=mock(com.investory.dao.DividendDao.class);
        when(dividends.findByPortfolio(1L)).thenReturn(List.of());
        ReflectionTestUtils.setField(controller,"dividendDao",dividends);
        var result=controller.chart("cumulative_return",null,40,"2026-01-02","2026-02-09",null,null,null,fixture.request,new MockHttpServletResponse());
        var values=new ObjectMapper().readTree(result);
        assertEquals(39,values.size());
        assertEquals(1100.1,values.get(0).get("value").asDouble(),0.0001);
        verify(history).range(eq(1L),any(),any(),eq("none"));
    }
    @Test void incompleteQuotesCannotProducePartialPortfolioWeights() {
        when(fixture.quotes.getQuotes(anyList())).thenReturn(Map.of());
        assertTrue(quant().contextSummary(fixture.request).containsKey("error"));
    }
    @Test void correlationUsesAlignedExternalCloses() {
        var result=quant().holdingsCorrelation("0.000001",fixture.request);
        assertEquals(1,result.size()); assertEquals(1.0,result.get(0).get("correlation_30d"));
        verify(history,times(2)).range(anyLong(),any(),any(),eq("forward"));
    }
    @Test void greetingReusesQuotesWithoutOpeningHistoricalStorageOrFetchingBackgroundData() {
        when(fixture.quotes.cachedQuotes(anyList())).thenReturn(Map.of("1.600519",fixture.quote()));
        var controller=new AiApiController(mock(AiSessionManager.class),fixture.jdbc,executor,mock(AiModelConfiguration.class));
        ReflectionTestUtils.setField(controller,"quotes",fixture.quotes);
        var result=controller.morningGreeting(fixture.request);
        assertEquals(true,result.get("show"));
        assertTrue(result.get("message").toString().contains("测试股票一"));
        verify(fixture.quotes).cachedQuotes(anyList()); verify(fixture.quotes,never()).getQuotes(anyList());
    }
}
