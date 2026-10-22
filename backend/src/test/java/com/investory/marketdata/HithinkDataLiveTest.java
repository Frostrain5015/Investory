package com.investory.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import com.investory.controller.api.MarketIndexController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real read-only calls, never executed during the default test suite. */
@EnabledIfEnvironmentVariable(named = "HITHINK_LIVE_TESTS", matches = "true")
class HithinkDataLiveTest {
    @Test void verifiesIndexBatchThroughWorldMarketEndpoint() throws Exception {
        String key = System.getenv("HITHINK_FINANCE_API_KEY");
        assertNotNull(key, "Live-test API key must be injected into the environment");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var client = new HithinkDataClient(new HithinkDataProperties(true, key, "https://fuyao.aicubes.cn", 5000), mapper);
        var legacy = mock(LegacyWorldMarketClient.class);
        var workers = Executors.newFixedThreadPool(4);
        try {
            var service = new WorldMarketService(client, legacy, workers);
            var mvc = MockMvcBuilders.standaloneSetup(new MarketIndexController(mock(JdbcTemplate.class), service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
            long start = System.nanoTime();
            mvc.perform(get("/api/market/indices")).andExpect(status().isOk())
                .andExpect(jsonPath("$.hithinkStatus").value("active"))
                .andExpect(jsonPath("$.indices.length()").value(20))
                .andExpect(jsonPath("$.indicators.length()").value(4))
                .andExpect(jsonPath("$.indices[0].source").value("hithink_finance_api"))
                .andExpect(jsonPath("$.indices[1].source").value("hithink_finance_api"))
                .andExpect(jsonPath("$.indices[2].source").value("hithink_finance_api"))
                .andExpect(jsonPath("$.indices[0].timestampScope").value("BATCH"))
                .andExpect(jsonPath("$.indices[0].price").isNumber())
                .andExpect(jsonPath("$.indices[3].state").value("unavailable"));
            assertTrue(service.snapshot().indices().subList(0, 3).stream().allMatch(WorldMarketQuote::valid));
            System.out.println("HITHINK_WORLD_LIVE: verifiedIndices=3, api=/api/market/indices, elapsedMs="
                + (System.nanoTime() - start) / 1_000_000 + ", legacyProviders=mocked, timestampScope=BATCH");
        } finally { workers.shutdownNow(); }
    }

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
