package com.investory.crawler;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BacktestSessionManagerTest {
    @Test
    void isolatesUsersAndRejectsDuplicateActiveRuns() {
        BacktestSessionManager manager = new BacktestSessionManager();
        BacktestSessionManager.Run first = manager.tryStart(1);
        assertNotNull(first);
        first.addLog("private run");
        first.updateProgress(Map.of("pct", 42));
        assertNull(manager.tryStart(1));
        assertEquals(Map.of("active", false), manager.getStatus(2));
        assertNotNull(manager.tryStart(2));
        assertFalse(manager.getStatus(2).toString().contains("private run"));
        assertNull(manager.getStatus(2).get("progress"));
    }

    @Test
    void previousRunCannotFinishOrAppendLogsToItsReplacement() {
        BacktestSessionManager manager = new BacktestSessionManager();
        BacktestSessionManager.Run old = manager.tryStart(1);
        old.finish();
        BacktestSessionManager.Run replacement = manager.tryStart(1);
        replacement.addLog("new run");
        old.addLog("late old log");
        old.finish();
        assertTrue(replacement.isActive());
        assertFalse(manager.getStatus(1).toString().contains("late old log"));
    }

    @Test
    void concurrentStartsOnlyReserveOneRunForAnAccount() throws Exception {
        BacktestSessionManager manager = new BacktestSessionManager();
        var workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(workers.submit(() -> {
                start.await();
                if (manager.tryStart(1) != null) accepted.incrementAndGet();
                return null;
            }));
            start.countDown();
            for (var task : tasks) task.get();
            assertEquals(1, accepted.get());
        } finally {
            workers.shutdownNow();
        }
    }
}
