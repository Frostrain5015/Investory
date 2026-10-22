package com.investory.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real read-only calls, never executed during the default test suite. */
@EnabledIfEnvironmentVariable(named = "HITHINK_LIVE_TESTS", matches = "true")
class HithinkDataLiveTest {
    @Test void verifiesActualJavaAdapterAgainstProvider() {
        String key = System.getenv("HITHINK_FINANCE_API_KEY");
        assertNotNull(key, "Live-test API key must be injected into the environment");
        var client = new HithinkDataClient(new HithinkDataProperties(true, key, "https://fuyao.aicubes.cn", 10000), new ObjectMapper());
        var search = client.search("贵州茅台", 2);
        assertTrue(search.items().stream().anyMatch(item -> item.symbol().equals("600519.SH")));
        var snapshot = client.snapshot(List.of("600519.SH", "605589.SH", "000001.SZ"));
        assertEquals(3, snapshot.items().size());
        assertTrue(snapshot.items().stream().allMatch(item -> item.price() != null && item.price().signum() > 0));
        var history = client.historical("600519.SH", LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 21), "none");
        assertFalse(history.items().isEmpty());
        System.out.println("HITHINK_LIVE: search=" + search.items().size() + ", quotes=" + snapshot.items().size()
                + ", dailyBars=" + history.items().size() + ", timestampScope=" + snapshot.timestampScope()
                + ", receivedAt=" + snapshot.receivedAt());
    }
}
