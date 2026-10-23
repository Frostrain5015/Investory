package com.investory.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.dao.StockDao;
import com.investory.model.Stock;
import com.investory.model.StockPrice;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoricalPriceServiceTest {
    final LocalDate start = LocalDate.of(2025,1,1), end = LocalDate.of(2025,1,10);
    static class Gateway extends HistoricalPriceService {
        AtomicInteger calls = new AtomicInteger();
        boolean fail;
        Gateway(StockDao stocks) { super(stocks,new ObjectMapper(),"python"); }
        @Override protected Series fetch(String symbol, LocalDate from, LocalDate to, String adjust) throws Exception {
            calls.incrementAndGet(); Thread.sleep(70);
            if (fail) throw new HistoryUnavailable();
            StockPrice bar = new StockPrice(); bar.setTradeDate(from); bar.setClose(BigDecimal.TEN);
            return new Series(symbol,"fixture",adjust,List.of(bar));
        }
    }
    @Test void concurrentUsersShareOneFetchAndMemoryCache() throws Exception {
        var gateway = new Gateway(mock(StockDao.class));
        var readers = Executors.newFixedThreadPool(5);
        try {
            List<Future<HistoricalPriceService.Series>> jobs = new ArrayList<>();
            for (int i=0;i<5;i++) jobs.add(readers.submit(() -> gateway.series("600519.SH",start,end,"none")));
            for (var job:jobs) assertEquals(BigDecimal.TEN,job.get(5,TimeUnit.SECONDS).prices().get(0).getClose());
            gateway.series("600519.SH",start,end,"none");
            assertEquals(1,gateway.calls.get());
        } finally { readers.shutdownNow(); gateway.close(); }
    }
    @Test void adjustmentAndExactExchangeHaveSeparateKeys() {
        var gateway = new Gateway(mock(StockDao.class));
        try {
            gateway.series("000001.SH",start,end,"none"); gateway.series("000001.SZ",start,end,"none");
            gateway.series("000001.SZ",start,end,"forward");
            assertEquals(3,gateway.calls.get());
        } finally { gateway.close(); }
    }
    @Test void aliasesShareTheSameJavaCache() {
        var gateway=new Gateway(mock(StockDao.class));
        try {
            gateway.series("1.600519",start,end,"none"); gateway.series("600519.SH",start,end,"none");
            gateway.series("116.00700",start,end,"none"); gateway.series("00700.HK",start,end,"none");
            assertEquals(2,gateway.calls.get());
        } finally { gateway.close(); }
    }
    @Test void providerFailureDoesNotPoisonCache() {
        var gateway = new Gateway(mock(StockDao.class));
        try {
            gateway.fail=true;
            assertThrows(HistoricalPriceService.HistoryUnavailable.class,()->gateway.series("600519.SH",start,end,"none"));
            gateway.fail=false;
            assertEquals(1,gateway.series("600519.SH",start,end,"none").prices().size());
            assertEquals(2,gateway.calls.get());
        } finally { gateway.close(); }
    }
    @Test void rangeResolvesOnlyMetadataAndReturnsCopies() {
        var stocks=mock(StockDao.class); Stock stock=new Stock(); stock.setSymbol("1.600519"); when(stocks.findById(7L)).thenReturn(stock);
        var gateway=new Gateway(stocks);
        try {
            var first=gateway.range(7L,start,end,"none"); first.get(0).setClose(BigDecimal.ZERO);
            assertEquals(BigDecimal.TEN,gateway.range(7L,start,end,"none").get(0).getClose());
            verify(stocks,times(2)).findById(7L);
            assertEquals(1,gateway.calls.get());
        } finally { gateway.close(); }
    }
    @Test void rejectsNullPricesUnorderedBarsAndNegativeVolume() throws Exception {
        var gateway=new Gateway(mock(StockDao.class)); var json=new ObjectMapper();
        try {
            String bar="{\"date\":\"2025-01-01\",\"open\":10,\"high\":12,\"low\":9,\"close\":11,\"volume\":100}";
            String template="{\"source\":\"fixture\",\"adjust\":\"none\",\"points\":[%s]}";
            assertEquals(1,gateway.parse("600519.SH",start,end,json.readTree(template.formatted(bar))).prices().size());
            for(String bad:List.of(bar.replace("\"close\":11","\"close\":null"),bar.replace("\"volume\":100","\"volume\":-1"),bar+","+bar)) {
                var root=json.readTree(template.formatted(bad));
                assertThrows(HistoricalPriceService.HistoryUnavailable.class,()->gateway.parse("600519.SH",start,end,root));
            }
        } finally { gateway.close(); }
    }
}
