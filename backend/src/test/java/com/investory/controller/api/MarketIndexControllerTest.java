package com.investory.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MarketIndexControllerTest {
    @Test
    void returnsStoredForeignUnitsPerCnyWithoutInventingTimestamp() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT currency, rate FROM exchange_rates")).thenReturn(List.of(
                Map.of("currency", "USD", "rate", new BigDecimal("0.14")),
                Map.of("currency", "HKD", "rate", new BigDecimal("1.09")),
                Map.of("currency", "CNY", "rate", 2),
                Map.of("currency", "INVALID", "rate", BigDecimal.ZERO)));
        ExecutorService executor = mock(ExecutorService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MarketIndexController(jdbc, executor)).build();
        mvc.perform(get("/api/market/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("CNY"))
                .andExpect(jsonPath("$.rates.CNY").value(1))
                .andExpect(jsonPath("$.rates.USD").value(0.14))
                .andExpect(jsonPath("$.rates.HKD").value(1.09))
                .andExpect(jsonPath("$.rates.INVALID").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").isEmpty());
        verifyNoInteractions(executor);
    }
}
