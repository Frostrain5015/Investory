package com.investory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

@Configuration
public class AsyncConfig {

    @Bean("sseExecutor")
    public ExecutorService sseExecutor() {
        return new ThreadPoolExecutor(
            2, 10,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            r -> { Thread t = new Thread(r, "sse-worker"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean("indexExecutor")
    public ExecutorService indexExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "index-builder"); t.setDaemon(true); return t;
        });
    }
}
