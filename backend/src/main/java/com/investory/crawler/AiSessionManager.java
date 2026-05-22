package com.investory.crawler;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AiSessionManager {

    private volatile boolean active = false;
    private final LinkedList<String> tokenBuffer = new LinkedList<>();
    private static final int MAX_BUFFER = 500;

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public synchronized void startSession() {
        this.active = true;
        this.tokenBuffer.clear();
    }

    public synchronized void clearSession() {
        this.active = false;
    }

    public void emitToken(String token) {
        synchronized (tokenBuffer) {
            tokenBuffer.addLast(token);
            if (tokenBuffer.size() > MAX_BUFFER) tokenBuffer.removeFirst();
        }
        emitToAll("token", Map.of("msg", token));
    }

    public void emitTool(String name) {
        emitToAll("tool", Map.of("name", name));
    }

    public void emitStrategy(Map<String, Object> data) {
        emitToAll("strategy", data);
    }

    public void emitSuggestions(List<?> data) {
        emitToAll("suggestions", Map.of("items", data));
    }

    public void emitDone() {
        emitToAll("done", Map.of("msg", ""));
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

        // Replay buffered tokens for late subscribers
        synchronized (tokenBuffer) {
            for (String token : tokenBuffer) {
                emitSingle(emitter, "token", Map.of("msg", token));
            }
        }

        return emitter;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", active);
        return status;
    }

    private void emitToAll(String event, Object data) {
        for (SseEmitter e : subscribers) {
            emitSingle(e, event, data);
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
