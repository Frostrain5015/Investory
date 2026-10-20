package com.investory.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Drains subprocess output concurrently so silent or stalled children still time out. */
public final class ProcessRunner {
    private ProcessRunner() {}

    public static int run(ProcessBuilder builder, Duration timeout, Consumer<String> onLine)
            throws IOException, InterruptedException, TimeoutException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Process timeout must be positive");
        }
        Process process = builder.redirectErrorStream(true).start();
        AtomicReference<Exception> readFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader output = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = output.readLine()) != null) onLine.accept(line);
            } catch (Exception e) {
                readFailure.set(e);
                terminate(process);
            }
        }, "process-output-" + process.pid());
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Subprocess exceeded " + timeout.toSeconds() + " seconds");
            }
            reader.join(5_000);
            if (reader.isAlive()) throw new IOException("Subprocess output did not close");
            if (readFailure.get() != null) throw new IOException("Cannot read subprocess output", readFailure.get());
            return process.exitValue();
        } finally {
            terminate(process);
            close(process.getInputStream());
            close(process.getOutputStream());
            close(process.getErrorStream());
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        if (process.isAlive()) process.destroyForcibly();
    }

    private static void close(java.io.Closeable stream) {
        try { stream.close(); }
        catch (IOException ignored) { /* Cleanup must not hide a timeout or the original read failure. */ }
    }
}
