package com.investory.crawler;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** One independent run per user; completed events remain briefly available for late SSE clients. */
@Component
public class BacktestSessionManager {
    private static final long RETENTION_MS = 30 * 60 * 1000L;
    private final Map<Long, Run> runs = new ConcurrentHashMap<>();

    public synchronized Run tryStart(long userId) {
        long now = System.currentTimeMillis();
        runs.entrySet().removeIf(entry -> entry.getValue().expired(now));
        Run current = runs.get(userId);
        if (current != null && current.isActive()) return null;
        Run run = new Run();
        runs.put(userId, run);
        return run;
    }

    public Map<String, Object> getStatus(long userId) {
        Run run = runs.get(userId);
        return run == null ? Map.of("active", false) : run.getStatus();
    }

    public SseEmitter subscribe(long userId) {
        Run run = runs.get(userId);
        if (run == null) {
            SseEmitter empty = new SseEmitter();
            empty.complete();
            return empty;
        }
        return run.subscribe();
    }

    public static final class Run {
        private record Event(String name, Object data) {}
        private final LinkedList<Event> events = new LinkedList<>();
        private final LinkedList<String> logs = new LinkedList<>();
        private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        private boolean active = true;
        private long finishedAt;
        private Map<String, Object> progress;

        public synchronized boolean isActive() { return active; }

        private synchronized boolean expired(long now) {
            return !active && now - finishedAt > RETENTION_MS;
        }

        public synchronized void updateProgress(Map<String, Object> value) {
            progress = Map.copyOf(value);
            emit("progress", progress);
        }

        public synchronized void addLog(String message) {
            logs.add(message);
            if (logs.size() > 300) logs.removeFirst();
            emit("log", Map.of("msg", message));
        }

        public synchronized void emitInfo(String message) {
            addLog("[信息] " + message);
            emit("info", Map.of("msg", message));
        }

        public synchronized void emitStatus(String message) {
            addLog("[状态] " + message);
            emit("status", Map.of("msg", message));
        }

        public synchronized void emitDone(String message, long resultId) {
            emit("done", Map.of("msg", message, "resultId", resultId));
        }

        public synchronized void emitError(String message) {
            emit("error", Map.of("msg", message));
        }

        public synchronized void finish() {
            active = false;
            finishedAt = System.currentTimeMillis();
            for (SseEmitter subscriber : subscribers) subscriber.complete();
            subscribers.clear();
        }

        private synchronized Map<String, Object> getStatus() {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("active", active);
            status.put("progress", progress);
            status.put("recentLogs", new ArrayList<>(logs));
            return status;
        }

        private synchronized SseEmitter subscribe() {
            SseEmitter emitter = new SseEmitter(0L);
            subscribers.add(emitter);
            emitter.onCompletion(() -> subscribers.remove(emitter));
            emitter.onTimeout(() -> subscribers.remove(emitter));
            emitter.onError(error -> subscribers.remove(emitter));
            for (Event event : events) send(emitter, event);
            if (!active) {
                emitter.complete();
                subscribers.remove(emitter);
            }
            return emitter;
        }

        private void emit(String name, Object data) {
            Event event = new Event(name, data);
            events.add(event);
            if (events.size() > 300) events.removeFirst();
            for (SseEmitter subscriber : subscribers) send(subscriber, event);
        }

        private void send(SseEmitter emitter, Event event) {
            try {
                emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
            } catch (Exception disconnected) {
                subscribers.remove(emitter);
            }
        }
    }
}
