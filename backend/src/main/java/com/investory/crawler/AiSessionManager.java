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
        // Single ordered event log. Every emitted SSE event (token, reasoning,
        // tool, tool_end, tool_fail, ask, confirm, strategy, done, error…) is
        // appended here so late subscribers can replay the FULL stream in the
        // exact order it happened. The frontend opens its EventSource only after
        // the /chat POST returns, by which point the Python process may already
        // have emitted ask/tool/confirm/done — replaying just token+reasoning
        // (the old behaviour) dropped those and stalled ask_user forever.
        final LinkedList<Map<String, Object>> eventLog = new LinkedList<>();
        final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
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

    /**
     * Write the user's answer back to the Python process stdin.
     * Called from the answer endpoint when the user clicks an ask_user option.
     */
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
        emitToAll(get(userId), "token", Map.of("msg", token));
    }

    public void emitReasoning(long userId, String chunk) {
        emitToAll(get(userId), "reasoning", Map.of("msg", chunk));
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

        // Replay the full ordered event log for late subscribers / reconnects.
        // This is what makes ask_user, confirm cards, tool steps and the final
        // `done` reliably appear even when the Python process emitted them before
        // this EventSource finished connecting.
        synchronized (s.eventLog) {
            for (Map<String, Object> ev : s.eventLog) {
                emitSingle(emitter, String.valueOf(ev.get("event")), ev.get("data"));
            }
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

    public boolean hasReplayEvents(long userId) {
        UserSession s = sessions.get(userId);
        if (s == null) return false;
        synchronized (s.eventLog) {
            return !s.eventLog.isEmpty();
        }
    }

    private void emitToAll(UserSession s, String event, Object data) {
        // Record into the ordered log first so a subscriber connecting mid-flight
        // replays this event too, then fan out to current subscribers.
        synchronized (s.eventLog) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("event", event);
            entry.put("data", data);
            s.eventLog.addLast(entry);
            if (s.eventLog.size() > MAX_BUFFER) s.eventLog.removeFirst();
        }
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
