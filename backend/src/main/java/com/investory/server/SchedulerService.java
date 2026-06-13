package com.investory.server;

import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Manages scheduled background tasks using ScheduledExecutorService.
 * Replaces Spring's @Scheduled annotation.
 */
public class SchedulerService {

    private static final Logger log = Logger.getLogger(SchedulerService.class.getName());
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "scheduler-worker");
        t.setDaemon(true);
        return t;
    });

    private SchedulerService() {}

    /**
     * Schedule a task at a fixed rate.
     *
     * @param name     task name for logging
     * @param task     the runnable task
     * @param initialDelaySeconds initial delay in seconds
     * @param periodSeconds       period in seconds
     * @return the ScheduledFuture for cancellation
     */
    public static ScheduledFuture<?> scheduleAtFixedRate(String name, Runnable task,
                                                          long initialDelaySeconds, long periodSeconds) {
        log.info("Scheduled task '" + name + "' every " + periodSeconds + "s (delay=" + initialDelaySeconds + "s)");
        return scheduler.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warning("Scheduled task '" + name + "' failed: " + e.getMessage());
            }
        }, initialDelaySeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /**
     * Schedule a one-time task with a fixed delay.
     */
    public static ScheduledFuture<?> schedule(String name, Runnable task, long delaySeconds) {
        log.info("Scheduled one-time task '" + name + "' in " + delaySeconds + "s");
        return scheduler.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warning("Scheduled task '" + name + "' failed: " + e.getMessage());
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Check if current time matches a cron-like schedule and run if so.
     * Simplified: accepts hour:minute and day-of-week constraints.
     *
     * @param taskName    name for logging
     * @param task        the runnable task
     * @param hour        hour (0-23)
     * @param minute      minute (0-59)
     * @param dayOfWeekFilter null = every day, otherwise day-of-week name (MON, TUE, etc.)
     */
    public static void runIfScheduled(String taskName, Runnable task,
                                       int hour, int minute, String dayOfWeekFilter) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        if (now.getHour() != hour || now.getMinute() != minute) return;
        if (dayOfWeekFilter != null) {
            String today = now.getDayOfWeek().name().substring(0, 3); // MON, TUE, ...
            if (!dayOfWeekFilter.contains(today)) return;
        }
        task.run();
    }

    public static void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
