package com.investory.crawler;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
@Component
public class AiSessionManager {

    private static final int MAX_BUFFER = 500;

    private static final class UserSession {
        volatile boolean active = false;
        // Latest process handle so cancel() can interrupt mid-generation
        final AtomicReference<Process> process = new AtomicReference<>(null);
        final LinkedList<String> tokenBuffer = new LinkedList<>();
        final LinkedList<String> reasoningBuffer = new LinkedList<>();
        final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    }

    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();

    private UserSession get(long userId) {
        return sessions.computeIfAbsent(userId, k -> new UserSession());
    }

    public synchronized void startSession(long userId) {
        UserSession s = get(userId);
        s.active = true;
        synchronized (s.tokenBuffer) { s.tokenBuffer.clear(); }
        synchronized (s.reasoningBuffer) { s.reasoningBuffer.clear(); }
    }

    public synchronized void clearSession(long userId) {
        UserSession s = sessions.get(userId);
        if (s != null) {
            s.active = false;
            s.process.set(null);
        }
    }

    public void bindProcess(long userId, Process p) {
        get(userId).process.set(p);
    }

    /** Forcibly terminate the running generation for this user, if any. */
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
        UserSession s = get(userId);
        synchronized (s.tokenBuffer) {
            s.tokenBuffer.addLast(token);
            if (s.tokenBuffer.size() > MAX_BUFFER) s.tokenBuffer.removeFirst();
        }
        emitToAll(s, "token", Map.of("msg", token));
    }

    public void emitReasoning(long userId, String chunk) {
        UserSession s = get(userId);
        synchronized (s.reasoningBuffer) {
            s.reasoningBuffer.addLast(chunk);
            if (s.reasoningBuffer.size() > MAX_BUFFER) s.reasoningBuffer.removeFirst();
        }
        emitToAll(s, "reasoning", Map.of("msg", chunk));
    }

    public void emitTool(long userId, String name) {
        emitToAll(get(userId), "tool", Map.of("name", name));
    }

    public void emitToolEnd(long userId, String name) {
        emitToAll(get(userId), "tool_end", Map.of("name", name));
    }

    public void emitToolFail(long userId, String name, String errMsg) {
        emitToAll(get(userId), "tool_fail", Map.of("name", name, "error", errMsg));
    }

    public void emitStrategy(long userId, Map<String, Object> data) {
        emitToAll(get(userId), "strategy", data);
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

    public SseEmitter subscribe(long userId) {
        UserSession s = get(userId);
        SseEmitter emitter = new SseEmitter(0L);
        s.subscribers.add(emitter);
        emitter.onCompletion(() -> s.subscribers.remove(emitter));
        emitter.onTimeout(() -> s.subscribers.remove(emitter));
        emitter.onError(e -> s.subscribers.remove(emitter));

        // Replay buffered reasoning and tokens for late subscribers
        synchronized (s.reasoningBuffer) {
            for (String r : s.reasoningBuffer) emitSingle(emitter, "reasoning", Map.of("msg", r));
        }
        synchronized (s.tokenBuffer) {
            for (String token : s.tokenBuffer) emitSingle(emitter, "token", Map.of("msg", token));
        }
        return emitter;
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

    private void emitToAll(UserSession s, String event, Object data) {
        for (SseEmitter e : s.subscribers) emitSingle(e, event, data);
    }

    private void emitSingle(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception ignored) {
            // emitter will be removed via its onError callback
        }
    }
}
