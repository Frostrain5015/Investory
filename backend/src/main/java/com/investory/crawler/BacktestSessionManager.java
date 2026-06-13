package com.investory.crawler;

import com.investory.server.SseClient;
import jakarta.servlet.http.HttpServletResponse;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class BacktestSessionManager {

    private volatile boolean active = false;
    private volatile Map<String, Object> currentProgress = null;
    private final LinkedList<String> recentLogs = new LinkedList<>();
    private static final int MAX_LOGS = 300;

    private final List<SseClient> subscribers = new CopyOnWriteArrayList<>();

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
        for (SseClient client : subscribers) {
            client.send(event, data);
        }
    }

    public boolean isActive() { return active; }
}
