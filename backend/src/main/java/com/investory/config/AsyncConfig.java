package com.investory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

/**
 * 异步任务线程池配置类。
 *
 * <p>为应用中不同类型的异步场景各自提供独立的 {@link ExecutorService}，
 * 通过 Spring IoC 容器管理其生命周期，可在任意 Bean 中通过
 * {@code @Qualifier("beanName")} 注入使用。
 *
 * <p>当前定义了两个线程池：
 * <ul>
 *   <li>{@code sseExecutor} — 用于驱动 Server-Sent Events（SSE）推送任务，
 *       支持突发并发，队列有界以防内存溢出。</li>
 *   <li>{@code indexExecutor} — 用于后台单线程构建股票搜索索引，
 *       保证索引更新操作顺序执行，避免并发写入冲突。</li>
 * </ul>
 *
 * <p>两个线程池的线程均设置为守护线程（{@code daemon = true}），
 * 应用关闭时 JVM 不会等待这些线程结束，确保进程能够及时退出。
 */
@Configuration
public class AsyncConfig {

    /**
     * SSE 推送专用线程池。
     *
     * <p>配置参数说明：
     * <ul>
     *   <li>核心线程数 2 — 常驻线程，空闲时不销毁，保证低延迟响应少量并发 SSE 请求。</li>
     *   <li>最大线程数 10 — 当队列满后可扩展至 10 条线程处理突发流量。</li>
     *   <li>空闲超时 60 秒 — 超出核心数的临时线程在空闲 60 秒后自动回收。</li>
     *   <li>有界队列容量 50 — 最多缓冲 50 个待处理任务，防止无限积压导致 OOM。</li>
     *   <li>线程命名 {@code sse-worker} — 方便在线程 dump 和监控工具中快速定位。</li>
     *   <li>拒绝策略 {@link ThreadPoolExecutor.CallerRunsPolicy} — 队列满且线程数达上限时，
     *       由调用方线程直接执行任务，起到反压（back-pressure）效果，避免任务丢失。</li>
     * </ul>
     *
     * @return 配置好的 SSE 专用线程池，Bean 名称为 {@code sseExecutor}
     */
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

    /**
     * 股票搜索索引构建专用单线程执行器。
     *
     * <p>使用 {@link Executors#newSingleThreadExecutor} 创建单线程池，保证索引构建任务
     * 串行执行，消除并发写入导致索引数据不一致的风险。
     *
     * <p>线程命名为 {@code index-builder}，设置为守护线程，
     * 应用关闭时不阻塞 JVM 退出。
     *
     * @return 单线程执行器，Bean 名称为 {@code indexExecutor}
     */
    @Bean("indexExecutor")
    public ExecutorService indexExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "index-builder"); t.setDaemon(true); return t;
        });
    }

    /** AI 聊天子进程执行池（AiApiController） */
    @Bean("aiExecutor")
    public ExecutorService aiExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ai-worker"); t.setDaemon(true); return t;
        });
    }

    /** 回测引擎执行池（BacktestApiController） */
    @Bean("backtestExecutor")
    public ExecutorService backtestExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "backtest-worker"); t.setDaemon(true); return t;
        });
    }

    /** 管理后台执行池（AdminController） */
    @Bean("adminExecutor")
    public ExecutorService adminExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "admin-worker"); t.setDaemon(true); return t;
        });
    }

    /** 量化分析执行池（QuantApiController） */
    @Bean("quantExecutor")
    public ExecutorService quantExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "quant-worker"); t.setDaemon(true); return t;
        });
    }

    /** 实时行情竞速执行池（RealtimeQuoteService） */
    @Bean("quoteExecutor")
    public ExecutorService quoteExecutor() {
        return Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "quote-worker"); t.setDaemon(true); return t;
        });
    }

    /** 全球指数并发请求池（MarketIndexController） */
    @Bean("marketIndexExecutor")
    public ExecutorService marketIndexExecutor() {
        return Executors.newFixedThreadPool(25, r -> {
            Thread t = new Thread(r, "market-index-worker"); t.setDaemon(true); return t;
        });
    }
}
