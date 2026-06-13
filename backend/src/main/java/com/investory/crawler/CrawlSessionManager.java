package com.investory.crawler;

import com.investory.server.SseClient;
import jakarta.servlet.http.HttpServletResponse;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared crawl session state, survives page refreshes and supports
 * multiple SSE subscribers. Used by both AdminController (manual crawls)
 * and CrawlerScheduler (scheduled crawls).
 */
public class CrawlSessionManager {

    private volatile boolean   active          = false;
    private volatile String    market          = null;
    private volatile String    label           = null;
    private volatile String    startDate       = null;
    private volatile String    endDate         = null;
    private volatile Map<String, Object> currentProgress = null;
    private final LinkedList<String> recentLogs = new LinkedList<>();
    private static final int   MAX_LOGS        = 300;

    private final List<SseClient> subscribers = new CopyOnWriteArrayList<>();

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

    /**
     * Subscribe a new SSE client.
     *
     * @param response the HttpServletResponse to wrap into an SseClient
     * @return the SseClient instance
     */
    public SseClient subscribe(HttpServletResponse response) {
        try {
            SseClient client = new SseClient(response);
            subscribers.add(client);

            // Replay buffered recent logs and current progress
            synchronized (recentLogs) {
                for (String log : recentLogs) {
                    client.send("log", Map.of("msg", log));
                }
            }
            if (currentProgress != null) {
                client.send("progress", currentProgress);
            }

            return client;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSE client", e);
        }
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
        for (SseClient client : subscribers) {
            client.send(event, data);
        }
    }

    // Simple getters
    public boolean isActive() { return active; }
    public String getMarket() { return market; }
    public String getLabel() { return label; }
    public String getStartDate() { return startDate; }
    public String getEndDate() { return endDate; }
}
