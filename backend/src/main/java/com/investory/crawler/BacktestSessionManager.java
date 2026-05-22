package com.investory.crawler;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class BacktestSessionManager {

    private volatile boolean active = false;
    private volatile Map<String, Object> currentProgress = null;
    private final LinkedList<String> recentLogs = new LinkedList<>();
    private static final int MAX_LOGS = 300;

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public synchronized void startSession() {
        this.active = true;
        this.currentProgress = null;
        this.recentLogs.clear();
    }

    public synchronized void clearSession() {
        this.active = false;
        this.currentProgress = null;
    }

    public void updateProgress(Map<String, Object> progress) {
        this.currentProgress = progress;
        emitToAll("progress", progress);
    }

    public void addLog(String msg) {
        synchronized (recentLogs) {
            recentLogs.addLast(msg);
            if (recentLogs.size() > MAX_LOGS) recentLogs.removeFirst();
        }
        emitToAll("log", Map.of("msg", msg));
    }

    public void emitInfo(String msg) {
        addLog("[信息] " + msg);
        emitToAll("info", Map.of("msg", msg));
    }

    public void emitStatus(String msg) {
        addLog("[状态] " + msg);
        emitToAll("status", Map.of("msg", msg));
    }

    public void emitDone(String msg, long resultId) {
        emitToAll("done", Map.of("msg", msg, "resultId", resultId));
    }

    public void emitError(String msg) {
        emitToAll("error", Map.of("msg", msg));
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));

        synchronized (recentLogs) {
            for (String log : recentLogs) {
                emitSingle(emitter, "log", Map.of("msg", log));
            }
        }
        if (currentProgress != null) {
            emitSingle(emitter, "progress", currentProgress);
        }

        return emitter;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", active);
        if (!active) return status;
        status.put("progress", currentProgress);
        synchronized (recentLogs) {
            status.put("recentLogs", new ArrayList<>(recentLogs));
        }
        return status;
    }

    private void emitToAll(String event, Object data) {
        for (SseEmitter emitter : subscribers) {
            emitSingle(emitter, event, data);
        }
    }

    private void emitSingle(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception ignored) {
            subscribers.remove(emitter);
        }
    }

    public boolean isActive() { return active; }
}
