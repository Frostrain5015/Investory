package com.investory.crawler;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared crawl session state, survives page refreshes and supports
 * multiple SSE subscribers. Used by both AdminController (manual crawls)
 * and CrawlerScheduler (scheduled crawls).
 */
@Component
public class CrawlSessionManager {

    private volatile boolean   active          = false;
    private volatile String    market          = null;
    private volatile String    label           = null;
    private volatile String    startDate       = null;
    private volatile String    endDate         = null;
    private volatile Map<String, Object> currentProgress = null;
    private final LinkedList<String> recentLogs = new LinkedList<>();
    private static final int   MAX_LOGS        = 300;

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    // ── State management ──────────────────────────────────────────────

    public synchronized void startSession(String market, String label, String startDate, String endDate) {
        this.active = true;
        this.market = market;
        this.label = label;
        this.startDate = startDate;
        this.endDate = endDate;
        this.currentProgress = null;
        this.recentLogs.clear();
    }

    public synchronized void clearSession() {
        this.active = false;
        this.market = null;
        this.label = null;
        this.startDate = null;
        this.endDate = null;
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

    public void emitStatus(String msg, String market) {
        addLog("[状态] " + msg);
        emitToAll("status", Map.of("msg", msg, "market", market));
    }

    public void emitDone(String market, String msg) {
        emitToAll("done", Map.of("market", market, "msg", msg));
    }

    public void emitStopped(String market, String msg) {
        emitToAll("stopped", Map.of("market", market, "msg", msg));
    }

    public void emitError(String msg) {
        emitToAll("error", Map.of("msg", msg));
    }

    // ── Subscriber management ─────────────────────────────────────────

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));

        // Replay buffered recent logs and current progress
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

    // ── Status snapshot (for initial page load) ────────────────────────

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", active);
        if (!active) return status;

        status.put("market", market);
        status.put("label", label);
        status.put("startDate", startDate);
        status.put("endDate", endDate);
        status.put("progress", currentProgress);
        synchronized (recentLogs) {
            status.put("recentLogs", new ArrayList<>(recentLogs));
        }
        return status;
    }

    // ── Internals ──────────────────────────────────────────────────────

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

    // Simple getters
    public boolean isActive() { return active; }
    public String getMarket() { return market; }
    public String getLabel() { return label; }
    public String getStartDate() { return startDate; }
    public String getEndDate() { return endDate; }
}
