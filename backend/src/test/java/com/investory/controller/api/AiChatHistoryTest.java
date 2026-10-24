package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.crawler.AiSessionManager;
import com.investory.service.AiModelConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AiChatHistoryTest {
    private JdbcTemplate jdbc;
    private AiApiController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE ai_chat_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, conversation_id BIGINT, role VARCHAR(20), content TEXT)");
        jdbc.execute("CREATE TABLE ai_conversations (id BIGINT PRIMARY KEY, user_id BIGINT)");
        jdbc.update("INSERT INTO ai_conversations VALUES (7,42)");
        controller = new AiApiController(new AiSessionManager(), jdbc, mock(ExecutorService.class), mock(AiModelConfiguration.class));
        request = new MockHttpServletRequest();
        request.getSession().setAttribute("userId", 42L);
    }

    @Test
    void toolContextIsIsolatedByUserAndConversation() {
        controller.saveToolContext(42, 7, "first");
        controller.saveToolContext(42, 8, "second");
        controller.saveToolContext(43, 7, "other user");
        assertEquals("first", controller.loadToolContext(42, 7));
        assertEquals("second", controller.loadToolContext(42, 8));
        assertEquals("", controller.loadToolContext(42, 9));
        controller.saveToolContext(42, 7, "updated");
        assertEquals("updated", controller.loadToolContext(42, 7));
        assertEquals("second", controller.loadToolContext(42, 8));
        assertEquals("other user", controller.loadToolContext(43, 7));
    }

    @Test
    void toolOnlyFailureAndUserStopSurviveBothHistoryEndpoints() throws Exception {
        for (String kind : List.of("error", "stopped")) {
            var tool = new LinkedHashMap<String, Object>(Map.of("kind", "tool", "name", "get_stock_report", "done", false, "callId", kind));
            controller.persistTurn(42, 7, "", new ArrayList<>(List.of(tool)), false, Map.of("kind", kind, "message", "中断提示"));
        }
        for (var response : List.of(controller.getConversation(7, request), controller.history(request))) {
            var messages = (List<?>) response.get("messages");
            assertEquals(4, messages.size());
            for (int index : List.of(0, 2)) {
                var assistant = (Map<?, ?>) messages.get(index);
                assertEquals("assistant", assistant.get("role"));
                assertFalse(assistant.containsKey("thinking"));
                assertEquals("", assistant.get("content"));
            }
            assertEquals("notice", ((Map<?, ?>) messages.get(1)).get("role"));
            assertTrue(((Map<?, ?>) messages.get(3)).get("content").toString().contains("stopped"));
        }
    }

    @Test
    void longTimelineIsChunkedAndReassembledWithoutDroppingOutput() throws Exception {
        String text = "完整正文🧪".repeat(8000);
        var steps = new ArrayList<Map<String, Object>>();
        steps.add(new LinkedHashMap<>(Map.of("kind", "text", "text", text)));
        steps.add(new LinkedHashMap<>(Map.of("kind", "tool", "name", "get_stock_price", "done", true, "summary", "已获取行情")));
        controller.persistTurn(42, 7, text, steps, false, Map.of("kind", "error", "message", "上下文不足"));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_history WHERE role='thinking'", Integer.class) > 1);
        var restored = (List<?>) controller.getConversation(7, request).get("messages");
        var answer = (Map<?, ?>) restored.get(0);
        assertFalse(answer.containsKey("thinking"));
        assertEquals(text, answer.get("content"));
        assertFalse(answer.toString().contains("get_stock_price"));
    }

    @Test
    void artifactOnlyTurnStillGetsAnAssistantAnchorAndEmptyErrorsDoNotInventAnswers() throws Exception {
        controller.persistTurn(42, 7, "", new ArrayList<>(), true, Map.of("kind", "error", "message", "连接中断"));
        controller.persistTurn(42, 7, "", new ArrayList<>(), false, Map.of("kind", "error", "message", "请求失败"));
        assertEquals(List.of("assistant", "notice", "notice"), jdbc.queryForList("SELECT role FROM ai_chat_history ORDER BY id", String.class));
    }
}
