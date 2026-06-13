package com.investory.crawler;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-user AI streaming session manager.
 *
 * Each authenticated user gets an isolated {@link UserSession} with its own
 * SSE subscribers and replay buffers, so concurrent chats never cross-talk.
 * Anonymous users (userId = 0) share a single "anon" bucket — they shouldn't
 * be hitting authenticated AI endpoints in practice.
 */
public class AiSessionManager {

    private static final Gson GSON = new Gson();
    private static final int MAX_BUFFER = 500;

    private static final class UserSession {
        volatile boolean active = false;
        final AtomicReference<Process> process = new AtomicReference<>(null);
        final LinkedList<Map<String, Object>> eventLog = new LinkedList<>();
        final List<HttpServletResponse> subscribers = new CopyOnWriteArrayList<>();
    }

    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();

    private UserSession get(long userId) {
        return sessions.computeIfAbsent(userId, k -> new UserSession());
    }

    public synchronized void startSession(long userId) {
        UserSession s = get(userId);
        s.active = true;
        synchronized (s.eventLog) { s.eventLog.clear(); }
    }

    public synchronized void clearSession(long userId) {
        UserSession s = sessions.get(userId);
        if (s != null) {
            s.active = false;
            s.process.set(null);
            synchronized (s.eventLog) { s.eventLog.clear(); }
        }
    }

    public synchronized void finishSession(long userId) {
        UserSession s = sessions.get(userId);
        if (s != null) {
            s.active = false;
            s.process.set(null);
        }
    }

    public void bindProcess(long userId, Process p) {
        get(userId).process.set(p);
    }

    public boolean writeAnswer(long userId, String answer) {
        UserSession s = sessions.get(userId);
        if (s == null) return false;
        Process p = s.process.get();
        if (p == null || !p.isAlive()) return false;
        try {
            p.getOutputStream().write((answer + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            p.getOutputStream().flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean cancel(long userId) {
        UserSession s = sessions.get(userId);
        if (s == null) return false;
        Process p = s.process.getAndSet(null);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            emitError(userId, "已停止生成");
            s.active = false;
            return true;
        }
        return false;
    }

    public void emitToken(long userId, String token) {
        emitToAll(get(userId), "token", Map.of("msg", token));
    }

    public void emitReasoning(long userId, String chunk) {
        emitToAll(get(userId), "reasoning", Map.of("msg", chunk));
    }

    public void emitKb(long userId, String topic) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        emitToAll(get(userId), "kb", data);
    }

    public void emitMemory(long userId, String count) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        emitToAll(get(userId), "memory", data);
    }

    public void emitTool(long userId, String name, String category, String callId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("category", category);
        if (callId != null && !callId.isEmpty()) data.put("callId", callId);
        emitToAll(get(userId), "tool", data);
    }

    public void emitToolEnd(long userId, String callId, String name, String summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        if (callId != null && !callId.isEmpty()) data.put("callId", callId);
        if (summary != null && !summary.isEmpty()) data.put("summary", summary);
        emitToAll(get(userId), "tool_end", data);
    }

    public void emitToolFail(long userId, String callId, String name, String errMsg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        if (callId != null && !callId.isEmpty()) data.put("callId", callId);
        data.put("error", errMsg);
        emitToAll(get(userId), "tool_fail", data);
    }

    public void emitStrategy(long userId, Map<String, Object> data) {
        emitToAll(get(userId), "strategy", data);
    }

    public void emitArtifact(long userId, Map<String, Object> data) {
        emitToAll(get(userId), "artifact", data);
    }

    public void emitAsk(long userId, Map<String, Object> data) {
        emitToAll(get(userId), "ask", data);
    }

    public void emitSuggestions(long userId, List<?> data) {
        emitToAll(get(userId), "suggestions", Map.of("items", data));
    }

    public void emitDone(long userId) {
        emitToAll(get(userId), "done", Map.of("msg", ""));
    }

    public void emitConfirm(long userId, String jsonStr) {
        emitToAll(get(userId), "confirm", Map.of("data", jsonStr));
    }

    public void emitError(long userId, String msg) {
        emitToAll(get(userId), "error", Map.of("msg", msg));
    }

    public HttpServletResponse subscribe(long userId, HttpServletResponse response) {
        UserSession s = get(userId);
        s.subscribers.add(response);

        // Replay the full ordered event log for late subscribers / reconnects.
        synchronized (s.eventLog) {
            for (Map<String, Object> ev : s.eventLog) {
                emitSingle(response, String.valueOf(ev.get("event")), ev.get("data"));
            }
        }
        return response;
    }

    public Map<String, Object> getStatus(long userId) {
        UserSession s = sessions.get(userId);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", s != null && s.active);
        return status;
    }

    public boolean isActive(long userId) {
        UserSession s = sessions.get(userId);
        return s != null && s.active;
    }

    public boolean hasReplayEvents(long userId) {
        UserSession s = sessions.get(userId);
        if (s == null) return false;
        synchronized (s.eventLog) {
            return !s.eventLog.isEmpty();
        }
    }

    private void emitToAll(UserSession s, String event, Object data) {
        synchronized (s.eventLog) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("event", event);
            entry.put("data", data);
            s.eventLog.addLast(entry);
            if (s.eventLog.size() > MAX_BUFFER) s.eventLog.removeFirst();
        }
        for (HttpServletResponse response : s.subscribers) emitSingle(response, event, data);
    }

    private void emitSingle(HttpServletResponse response, String event, Object data) {
        try {
            PrintWriter writer = response.getWriter();
            writer.write("event: " + event + "\n");
            writer.write("data: " + GSON.toJson(data) + "\n\n");
            writer.flush();
        } catch (Exception ignored) {
        }
    }
}
