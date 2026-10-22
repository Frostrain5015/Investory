package com.investory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiModelConfigurationTest {
    private JdbcTemplate jdbc;
    private AiModelConfiguration configuration;
    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class);
        configuration = new AiModelConfiguration(jdbc);
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of());
    }
    private void saved(Integer context) {
        var row = new HashMap<String, Object>();
        row.put("slot_id", 1); row.put("provider", "openai_compat"); row.put("base_url", "https://first.example/v1");
        row.put("model", "exact-model-id"); row.put("api_key", "private-key"); row.put("context_window", context);
        when(jdbc.queryForList(anyString(), eq(7L), eq(1))).thenReturn(List.of(row));
    }
    @Test void unconfiguredAndLegacyRowsNeverFallBackToSharedModels() {
        assertFalse(configuration.load(7, 1).configured());
        assertEquals("", configuration.load(7, 1).model());
        assertThrows(IllegalArgumentException.class, () -> configuration.require(7, 1));
        saved(null);
        assertFalse(configuration.load(7, 1).configured());
        assertTrue(!configuration.load(7, 1).apiKey().isBlank());
        assertThrows(IllegalArgumentException.class, () -> configuration.require(7, 1));
    }
    @Test void credentialsAreRetainedOnlyAtTheSameEndpointAndCanBeCleared() {
        saved(8192);
        assertEquals("private-key", configuration.resolveKey(7, 1, "openai_compat", "https://first.example/v1", null));
        assertEquals("", configuration.resolveKey(7, 1, "openai_compat", "https://other.example/v1", null));
        assertEquals("", configuration.resolveKey(7, 1, "anthropic", "https://first.example/v1", null));
        assertEquals("", configuration.resolveKey(7, 2, "openai_compat", "https://first.example/v1", null));
        assertEquals("", configuration.resolveKey(7, 1, "openai_compat", "https://first.example/v1", ""));
        assertFalse(configuration.require(7, 1).toString().contains("private-key"));
    }
    @Test void acceptsNoKeyLocalModelsAndPreservesExactModelId() {
        var view = configuration.save(7, new AiModelConfiguration.SaveRequest(1, "openai_compat",
            " local/model:4bit ", "http://localhost:11434/v1/", "", 8192));
        verify(jdbc).update(anyString(), eq(7L), eq(1), eq("openai_compat"), eq("local/model:4bit"),
            eq("http://localhost:11434/v1"), eq(""), eq(8192));
    }
    @Test void rejectsFourthSlotAndUnconfiguredSelections() {
        for (Integer id : Arrays.asList(null, 0, 4)) {
            assertThrows(IllegalArgumentException.class, () -> configuration.save(7,
                new AiModelConfiguration.SaveRequest(id, "openai_compat", "model", "http://localhost:8000/v1", "", 8192)));
        }
        assertThrows(IllegalArgumentException.class, () -> configuration.select(7, 1));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void rejectsInvalidContextsAndCredentialBearingUrlsBeforeSaving() {
        for (Integer context : Arrays.asList(null, 0, 1023, 1048577))
            assertThrows(IllegalArgumentException.class, () -> configuration.save(7,
                new AiModelConfiguration.SaveRequest(1, "openai_compat", "model", "http://localhost:8000/v1", "", context)));
        for (String url : List.of("", "file:///tmp/model", "https://key@example.com/v1", "https://example.com?key=secret"))
            assertThrows(IllegalArgumentException.class, () -> AiModelConfiguration.normalizeBase(url));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
