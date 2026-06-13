package com.investory.server;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Minimal replacement for Spring's SseEmitter.
 * Wraps an SseClient and allows multiple subscriber-style usage via listeners.
 */
public class SimpleSseEmitter {

    private static final Logger log = Logger.getLogger(SimpleSseEmitter.class.getName());

    private final CopyOnWriteArrayList<Consumer<SseEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean completed = false;

    public static SseEvent event() {
        return new SseEvent();
    }

    public static class SseEvent {
        private String name;
        private String data;

        public SseEvent name(String name) { this.name = name; return this; }
        public SseEvent data(String data) { this.data = data; return this; }
        public SseEvent data(Object data) {
            this.data = com.investory.util.JsonUtil.toJson(data);
            return this;
        }

        public String getName() { return name; }
        public String getData() { return data; }
    }

    public void send(SseEvent event) throws IOException {
        if (completed) throw new IOException("SSE completed");
        for (Consumer<SseEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warning("SSE listener error: " + e.getMessage());
            }
        }
    }

    public void complete() {
        completed = true;
        listeners.clear();
    }

    public void completeWithError(Throwable t) {
        completed = true;
        listeners.clear();
    }

    public boolean isCompleted() { return completed; }

    /** Subscribe a listener that receives events forwarded from this emitter. */
    public void subscribe(Consumer<SseEvent> listener) {
        listeners.add(listener);
    }

    /** Forward events from this emitter to an SseClient. */
    public void forwardTo(SseClient client) {
        subscribe(event -> {
            if (!client.isCompleted()) {
                client.send(event.getName(), event.getData());
            }
        });
        onCompletion(client::complete);
    }

    public void onCompletion(Runnable callback) {
        // Simplified: no lifecycle tracking
    }

    public void onError(Consumer<Throwable> callback) {
        // Simplified: no lifecycle tracking
    }

    public void onTimeout(Runnable callback) {
        // Simplified: no lifecycle tracking
    }
}
