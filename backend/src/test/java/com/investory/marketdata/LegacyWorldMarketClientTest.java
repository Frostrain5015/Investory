package com.investory.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyWorldMarketClientTest {
    @Test void historicalFallbackUsesExactIdentityAndActualCloseDate() {
        var history = mock(HistoricalPriceService.class);
        var client = new LegacyWorldMarketClient(history, new ObjectMapper());
        var first = new com.investory.model.StockPrice(); first.setClose(new BigDecimal("100.12")); first.setTradeDate(LocalDate.of(2026,10,20));
        var last = new com.investory.model.StockPrice(); last.setClose(new BigDecimal("98.12")); last.setTradeDate(LocalDate.of(2026,10,21));
        when(history.series(eq("DXY.IDX"), any(), any(), eq("none"))).thenReturn(new HistoricalPriceService.Series("DXY.IDX", "yahoo", "none", List.of(first,last)));
        var spec = WorldMarketCatalog.SPECS.stream().filter(item -> item.dbSymbol().equals("DXY.IDX")).findFirst().orElseThrow();
        var quote = client.lastClose(spec);
        assertEquals("yahoo", quote.source()); assertEquals("stale", quote.state());
        assertEquals(LocalDate.of(2026,10,21), quote.asOfDate());
        assertEquals(new BigDecimal("-2.00"), quote.change());
        assertNull(quote.quoteTime()); assertNull(quote.receivedAt());
        verify(history).series(eq("DXY.IDX"),any(),any(),eq("none"));
    }
    @Test void yahooUsesExchangeTimeAndMissingChangesStayNull() throws Exception {
        var spec = WorldMarketCatalog.SPECS.get(6);
        var root = new ObjectMapper().readTree("{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":100,\"regularMarketTime\":1792620000}}]}}");
        var received = Instant.parse("2026-10-22T02:00:00Z");
        var quote = LegacyWorldMarketClient.parseYahoo(spec, root, received);
        assertNull(quote.change()); assertNull(quote.changePct());
        assertEquals(Instant.ofEpochSecond(1792620000L), quote.quoteTime());
        assertNotEquals(received, quote.quoteTime());
    }
}
