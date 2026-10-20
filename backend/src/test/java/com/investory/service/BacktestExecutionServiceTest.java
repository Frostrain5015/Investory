package com.investory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.crawler.BacktestSessionManager;
import com.investory.dao.BacktestDao;
import com.investory.model.BacktestRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BacktestExecutionServiceTest {
    private BacktestRequest request() {
        return new BacktestRequest("test", "simple", Map.of(),
                Map.of("startDate", "2026-01-01", "endDate", "2026-02-01"));
    }

    @Test
    void busyUserDoesNotCreateAnOrphanResult() throws Exception {
        BacktestDao dao = mock(BacktestDao.class);
        when(dao.insert(anyLong(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(7L);
        ExecutorService executor = mock(ExecutorService.class);
        var service = new BacktestExecutionService(dao, new BacktestSessionManager(), new ObjectMapper(), executor, "unused", 900);
        assertEquals(7, service.start(1, 1L, request()).orElseThrow());
        assertTrue(service.start(1, 1L, request()).isEmpty());
        verify(dao, times(1)).insert(anyLong(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectedTaskReleasesTheUserAndPersistsValidErrorJson() throws Exception {
        BacktestDao dao = mock(BacktestDao.class);
        when(dao.insert(anyLong(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(7L);
        ExecutorService executor = mock(ExecutorService.class);
        String detail = "rejected\n\"quoted\"";
        when(executor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException(detail));
        BacktestSessionManager sessions = new BacktestSessionManager();
        var service = new BacktestExecutionService(dao, sessions, new ObjectMapper(), executor, "unused", 900);
        assertThrows(RejectedExecutionException.class, () -> service.start(1, 1L, request()));
        assertEquals(false, sessions.getStatus(1).get("active"));
        ArgumentCaptor<String> metrics = ArgumentCaptor.forClass(String.class);
        verify(dao).updateResult(eq(7L), eq("[]"), metrics.capture(), eq("[]"));
        assertEquals(detail, new ObjectMapper().readTree(metrics.getValue()).get("error").asText());
    }

    @Test
    void rejectsInvalidDatesAndModesBeforeStartingWork() {
        assertThrows(IllegalArgumentException.class, () -> new BacktestRequest("test", "unknown", Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new BacktestRequest("test", "simple", Map.of(),
                Map.of("startDate", "2026-02-01", "endDate", "2026-01-01")));
    }
}
