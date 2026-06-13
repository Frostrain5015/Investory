package com.investory.server;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;

/**
 * Simple SSE (Server-Sent Events) client wrapper.
 * Replaces Spring's SseEmitter with standard Jakarta Servlet SSE over HttpServletResponse.
 */
public class SseClient {

    private static final Logger log = Logger.getLogger(SseClient.class.getName());
    private static final Gson gson = new Gson();

    private final HttpServletResponse response;
    private PrintWriter writer;
    private volatile boolean completed = false;

    public SseClient(HttpServletResponse response) {
        this.response = response;
    }

    /** Initialize the SSE response headers and get the writer. */
    public void init() throws IOException {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        writer = response.getWriter();
        writer.flush();
    }

    /** Send an SSE event with the given name and data object (serialized as JSON). */
    public synchronized void send(String event, Object data) {
        if (completed || writer == null) return;
        try {
            writer.write("event: " + event + "\n");
            writer.write("data: " + gson.toJson(data) + "\n\n");
            writer.flush();
        } catch (IOException e) {
            log.warning("SSE send failed for event " + event + ": " + e.getMessage());
            completed = true;
        }
    }

    /** Send a comment line (ignored by the client but useful for keep-alive). */
    public synchronized void sendComment(String comment) {
        if (completed || writer == null) return;
        try {
            writer.write(": " + comment + "\n");
            writer.flush();
        } catch (IOException e) {
            completed = true;
        }
    }

    /** Complete the SSE stream. */
    public synchronized void complete() {
        if (completed) return;
        completed = true;
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) {}
    }

    public boolean isCompleted() { return completed; }
}
