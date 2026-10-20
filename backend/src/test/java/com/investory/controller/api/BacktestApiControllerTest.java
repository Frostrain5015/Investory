package com.investory.controller.api;

import com.investory.crawler.BacktestSessionManager;
import com.investory.dao.BacktestDao;
import com.investory.service.BacktestExecutionService;
import com.investory.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.OptionalLong;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BacktestApiControllerTest {
    private BacktestSessionManager sessions;
    private BacktestExecutionService execution;
    private MockMvc mvc;
    private static final String REQUEST = """
        {"name":"test","strategyType":"simple","strategy":{},
         "config":{"startDate":"2026-01-01","endDate":"2026-02-01"}}
        """;

    @BeforeEach
    void setUp() {
        sessions = new BacktestSessionManager();
        execution = mock(BacktestExecutionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BacktestApiController(mock(BacktestDao.class), sessions, execution))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private MockHttpSession user(long id) {
        var session = new MockHttpSession();
        session.setAttribute("userId", id);
        session.setAttribute("portfolioId", 5L);
        return session;
    }

    @Test
    void startRequiresAnAuthenticatedUser() throws Exception {
        mvc.perform(post("/api/backtest/start").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(execution);
    }

    @Test
    void busyRunReturnsConflictRatherThanCreatingAnotherResult() throws Exception {
        when(execution.start(eq(1L), eq(5L), any())).thenReturn(OptionalLong.empty());
        mvc.perform(post("/api/backtest/start").session(user(1))
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidRequestIsBadRequestAndDoesNotReachExecution() throws Exception {
        mvc.perform(post("/api/backtest/start").session(user(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("2026-02-01", "invalid-date")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        verifyNoInteractions(execution);
    }

    @Test
    void lateSubscriberReceivesTerminalResultWithoutLeakingToAnotherUser() throws Exception {
        var run = sessions.tryStart(1);
        run.addLog("private-result");
        run.emitDone("finished", 42L);
        run.finish();
        mvc.perform(get("/api/backtest/stream").session(user(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:done")))
                .andExpect(content().string(containsString("\"resultId\":42")));
        mvc.perform(get("/api/backtest/stream").session(user(2)))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("private-result"))));
        mvc.perform(get("/api/backtest/status").session(user(2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.recentLogs").doesNotExist());
    }
}
