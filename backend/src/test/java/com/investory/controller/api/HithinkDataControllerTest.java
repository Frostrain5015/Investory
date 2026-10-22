package com.investory.controller.api;

import com.investory.marketdata.HithinkDataClient;
import com.investory.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HithinkDataControllerTest {
    private HithinkDataClient client;
    private MockMvc mvc;
    private MockHttpSession admin;
    private static final String BASE = "/api/admin/market-data/hithink";
    @BeforeEach void setup() {
        client = mock(HithinkDataClient.class);
        mvc = MockMvcBuilders.standaloneSetup(new HithinkDataController(client))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        admin = new MockHttpSession(); admin.setAttribute("userId", 1L); admin.setAttribute("isAdmin", true);
    }
    @Test void deniesAnonymousAndNonAdminBeforeProviderCall() throws Exception {
        mvc.perform(get(BASE + "/snapshot").param("symbols", "600519.SH")).andExpect(status().isUnauthorized());
        var user = new MockHttpSession(); user.setAttribute("userId", 2L);
        mvc.perform(get(BASE + "/status").session(user)).andExpect(status().isForbidden());
        verifyNoInteractions(client);
    }
    @Test void returnsSafeStatusAndPassesExplicitReadOnlyQueries() throws Exception {
        when(client.status()).thenReturn(new HithinkDataClient.Status(false, false, HithinkDataClient.SOURCE));
        mvc.perform(get(BASE + "/status").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.apiKey").doesNotExist());
        mvc.perform(get(BASE + "/snapshot").session(admin).param("symbols", "600519.SH,000001.SZ")).andExpect(status().isOk());
        verify(client).snapshot(List.of("600519.SH", "000001.SZ"));
        mvc.perform(get(BASE + "/history").session(admin).param("symbol", "600519.SH")
                .param("start", "2026-10-01").param("end", "2026-10-20")).andExpect(status().isOk());
        verify(client).historical("600519.SH", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), "none");
    }
    @Test void handlesDisabledRateLimitedAndBadParametersWithoutGeneric500() throws Exception {
        when(client.snapshot(List.of("600519.SH"))).thenThrow(new HithinkDataClient.DataException("DATA_DISABLED", "未启用"));
        mvc.perform(get(BASE + "/snapshot").session(admin).param("symbols", "600519.SH"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DATA_DISABLED"));
        when(client.search("茅台", 5)).thenThrow(new HithinkDataClient.DataException("DATA_RATE_LIMITED", "稍后重试"));
        mvc.perform(get(BASE + "/search").session(admin).param("query", "茅台")).andExpect(status().isTooManyRequests());
        mvc.perform(get(BASE + "/history").session(admin).param("symbol", "600519.SH")
                .param("start", "invalid").param("end", "2026-10-20")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/search").session(admin)).andExpect(status().isBadRequest());
    }
}
