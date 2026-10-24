package com.investory.crawler;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AiSessionManagerTest {
    @Test
    void stopBeforeProcessBindIsRememberedAndDoesNotEraseEarlierEvents() throws Exception {
        var manager = new AiSessionManager();
        manager.startSession(42);
        manager.emitActivity(42, "正在分析圣泉集团财报");
        manager.emitToken(42, "已收到的正文");
        assertTrue(manager.cancel(42));
        assertTrue(manager.isActive(42));
        var process = mock(Process.class);
        manager.bindProcess(42, process);
        verify(process).destroyForcibly();
        manager.emitError(42, "后续错误不能覆盖用户停止");
        assertEquals("stopped", manager.terminalNotice(42).get("kind"));
        manager.finishSession(42);
        var mvc = MockMvcBuilders.standaloneSetup(new StreamController(manager)).build();
        String replay = mvc.perform(get("/events")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(replay.contains("正在分析圣泉集团财报"));
        assertTrue(replay.contains("已收到的正文"));
        assertTrue(replay.contains("stopped"));
        manager.stopHeartbeats();
    }

    @RestController
    static class StreamController {
        final AiSessionManager manager;
        StreamController(AiSessionManager manager) { this.manager=manager; }
        @GetMapping(value="/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() { return manager.subscribe(42L); }
    }

    @Test
    void quietToolsHaveTransportHeartbeatWithoutPollutingReplayBuffer() throws Exception {
        var manager=new AiSessionManager();
        manager.startSession(42L);
        var mvc=MockMvcBuilders.standaloneSetup(new StreamController(manager)).build();
        var first=mvc.perform(get("/events")).andExpect(request().asyncStarted()).andReturn();
        assertTrue(first.getResponse().getContentAsString().contains(":connected"));
        assertFalse(manager.hasReplayEvents(42L));
        manager.emitActivity(42L,"正在查询市场行情");
        // More heartbeats than MAX_BUFFER: the original tool must still replay.
        for(int i=0;i<600;i++) manager.sendHeartbeats();
        assertTrue(first.getResponse().getContentAsString().contains(":keep-alive"));
        var replay=mvc.perform(get("/events")).andExpect(request().asyncStarted()).andReturn();
        assertTrue(replay.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("正在查询市场行情"));
        assertFalse(replay.getResponse().getContentAsString().contains(":keep-alive"));
        manager.emitDone(42L);
        assertTrue(replay.getResponse().getContentAsString().contains("event:done"));
    }

    @Test
    void reconnectReplaysOnlyCurrentActivityAndNeverAnExpandableTrace() throws Exception {
        var manager = new AiSessionManager();
        manager.startSession(42);
        manager.emitActivity(42, "正在查看市场行情");
        manager.emitToken(42, "已输出正文");
        manager.emitActivity(42, "正在分析圣泉集团财报");
        var mvc = MockMvcBuilders.standaloneSetup(new StreamController(manager)).build();
        String replay = mvc.perform(get("/events")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(replay.contains("正在查看市场行情"));
        assertTrue(replay.contains("正在分析圣泉集团财报"));
        assertTrue(replay.contains("已输出正文"));
        for (String event : new String[]{"reasoning", "tool", "tool_end", "tool_fail", "kb", "memory"})
            assertFalse(replay.contains("event:" + event));
        manager.stopHeartbeats();
    }

    @Test
    void finishSessionKeepsReplayEventsForLateStreamSubscribers() {
        AiSessionManager manager = new AiSessionManager();
        long userId = 42L;

        manager.startSession(userId);
        manager.emitDone(userId);
        manager.finishSession(userId);

        assertFalse(manager.isActive(userId));
        assertTrue(manager.hasReplayEvents(userId));
    }

    @Test
    void clearSessionRemovesReplayEvents() {
        AiSessionManager manager = new AiSessionManager();
        long userId = 42L;

        manager.startSession(userId);
        manager.emitError(userId, "failed");
        manager.clearSession(userId);

        assertFalse(manager.isActive(userId));
        assertFalse(manager.hasReplayEvents(userId));
    }
}
