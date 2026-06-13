package com.investory.crawler;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class BacktestSessionManager {

    private static final Gson GSON = new Gson();

    private volatile boolean active = false;
    private volatile Map<String, Object> currentProgress = null;
    private final LinkedList<String> recentLogs = new LinkedList<>();
    private static final int MAX_LOGS = 300;

    private final List<HttpServletResponse> subscribers = new CopyOnWriteArrayList<>();

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

    public HttpServletResponse subscribe(HttpServletResponse response) {
        subscribers.add(response);

        synchronized (recentLogs) {
            for (String log : recentLogs) {
                emitSingle(response, "log", Map.of("msg", log));
            }
        }
        if (currentProgress != null) {
            emitSingle(response, "progress", currentProgress);
        }

        return response;
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
        for (HttpServletResponse response : subscribers) {
            emitSingle(response, event, data);
        }
    }

    private void emitSingle(HttpServletResponse response, String event, Object data) {
        try {
            PrintWriter writer = response.getWriter();
            writer.write("event: " + event + "\n");
            writer.write("data: " + GSON.toJson(data) + "\n\n");
            writer.flush();
        } catch (Exception ignored) {
            subscribers.remove(response);
        }
    }

    public boolean isActive() { return active; }
}
