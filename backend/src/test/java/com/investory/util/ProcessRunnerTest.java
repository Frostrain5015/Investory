package com.investory.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    private ProcessBuilder child(String mode) {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), mode);
    }

    @Test
    void capturesAllLinesAndTheExitStatus() throws Exception {
        List<String> lines = new ArrayList<>();
        int code = ProcessRunner.run(child("output"), Duration.ofSeconds(10), lines::add);
        assertEquals(0, code);
        assertEquals(List.of("first", "second"), lines);
    }

    @Test
    void silentSubprocessStillTimesOut() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
                assertThrows(TimeoutException.class, () ->
                        ProcessRunner.run(child("sleep"), Duration.ofMillis(300), line -> {})));
    }

    @Test
    void propagatesOutputConsumerFailures() {
        assertThrows(java.io.IOException.class, () -> ProcessRunner.run(child("output"),
                Duration.ofSeconds(10), line -> { throw new IllegalStateException("consumer failed"); }));
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("sleep")) Thread.sleep(60_000);
            else {
                System.out.println("first");
                System.err.println("second");
            }
        }
    }
}
