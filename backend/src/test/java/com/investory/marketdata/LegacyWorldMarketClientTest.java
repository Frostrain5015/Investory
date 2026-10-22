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
    @Test void databaseFallbackUsesExactIndexIdentityAndActualCloseDate() {
        var jdbc = mock(JdbcTemplate.class);
        var client = new LegacyWorldMarketClient(jdbc, new ObjectMapper());
        when(jdbc.queryForList(anyString(), eq("DXY.IDX"))).thenReturn(List.of(
            Map.of("close", new BigDecimal("98.12"), "trade_date", java.sql.Date.valueOf("2026-10-21")),
            Map.of("close", new BigDecimal("100.12"), "trade_date", java.sql.Date.valueOf("2026-10-20"))));
        var spec = WorldMarketCatalog.SPECS.stream().filter(item -> item.dbSymbol().equals("DXY.IDX")).findFirst().orElseThrow();
        var quote = client.lastClose(spec);
        assertEquals("database", quote.source()); assertEquals("stale", quote.state());
        assertEquals(LocalDate.of(2026,10,21), quote.asOfDate());
        assertEquals(new BigDecimal("-2.00"), quote.change());
        assertNull(quote.quoteTime()); assertNull(quote.receivedAt());
        verify(jdbc).queryForList(contains("s.symbol = ?"), eq("DXY.IDX"));
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
