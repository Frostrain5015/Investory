package com.investory.crawler;

import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
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
        volatile Map<String, String> terminalNotice = Map.of();
        volatile boolean cancelled = false;
        volatile ScheduledFuture<?> watchdog;
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
    // Separate from the default cron scheduler, which can be occupied by crawls.
    private final ScheduledExecutorService heartbeatWorker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ai-stream-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void startHeartbeats() {
        heartbeatWorker.scheduleWithFixedDelay(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stopHeartbeats() { heartbeatWorker.shutdownNow(); }

    private UserSession get(long userId) {
        return sessions.computeIfAbsent(userId, k -> new UserSession());
    }

    public synchronized void startSession(long userId) {
        UserSession s = get(userId);
        s.active = true;
        s.cancelled = false;
        s.terminalNotice = Map.of();
        synchronized (s.eventLog) { s.eventLog.clear(); }
    }

    public synchronized void clearSession(long userId) {
        UserSession s = sessions.get(userId);
        if (s != null) {
            s.active = false;
            s.process.set(null);
            if (s.watchdog != null) s.watchdog.cancel(false);
            synchronized (s.eventLog) { s.eventLog.clear(); }
        }
    }

    public synchronized void finishSession(long userId) {
        UserSession s = sessions.get(userId);
        if (s != null) {
            s.active = false;
            s.process.set(null);
            if (s.watchdog != null) s.watchdog.cancel(false);
        }
    }

    public void bindProcess(long userId, Process p) {
        UserSession s = get(userId);
        synchronized (s) {
            if (s.cancelled) p.destroyForcibly();
            else {
                s.process.set(p);
                // readLine may block, so waitFor AFTER stdout is not a timeout.
                s.watchdog = heartbeatWorker.schedule(() -> {
                    if (s.process.get() == p && p.isAlive()) {
                        emitError(userId, "AI 对话超时");
                        p.destroyForcibly();
                    }
                }, 10, TimeUnit.MINUTES);
            }
        }
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
        synchronized (s) {
            if (!s.active) return false;
            s.cancelled = true;
            emitNotice(userId, "你已停止这次回复。", "stopped");
            Process p = s.process.getAndSet(null);
            if (p != null && p.isAlive()) p.destroyForcibly();
            // Stay active until the worker saves the partial turn in finally.
            return true;
        }
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

    public void emitTool(long userId, String name, String category, String callId, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("category", category);
        if (callId != null && !callId.isEmpty()) data.put("callId", callId);
        if (detail != null && !detail.isEmpty()) data.put("detail", detail);
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
        emitNotice(userId, msg, "error");
    }

    private void emitNotice(long userId, String msg, String kind) {
        UserSession s = get(userId);
        synchronized (s) {
            if (!s.terminalNotice.isEmpty()) return;
            String message = msg == null || msg.isBlank() ? "回复未完成，请稍后重试" : msg;
            s.terminalNotice = Map.of("message", message, "kind", kind);
            emitToAll(s, "error", Map.of("msg", message, "kind", kind));
        }
    }

    public Map<String, String> terminalNotice(long userId) {
        UserSession s = sessions.get(userId);
        return s == null ? Map.of() : s.terminalNotice;
    }

    public SseEmitter subscribe(long userId) {
        UserSession s = get(userId);
        SseEmitter emitter = new SseEmitter(0L);
        s.subscribers.add(emitter);
        emitter.onCompletion(() -> s.subscribers.remove(emitter));
        emitter.onTimeout(() -> s.subscribers.remove(emitter));
        emitter.onError(e -> s.subscribers.remove(emitter));
        // Commit the stream immediately, even while the engine has no output.
        sendComment(s, emitter, "connected");

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

    /** Proxy idle timeouts must not turn a quiet tool call into a broken chat. */
    public void sendHeartbeats() {
        for (UserSession session : sessions.values()) {
            for (SseEmitter emitter : session.subscribers) {
                sendComment(session, emitter, "keep-alive");
            }
        }
    }

    private void sendComment(UserSession session, SseEmitter emitter, String comment) {
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (Exception failure) {
            session.subscribers.remove(emitter);
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
