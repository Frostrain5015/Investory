package com.investory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 盈亏鉴（Investory）应用程序入口类。
 *
 * <p>标注了以下核心注解：
 * <ul>
 *   <li>{@link SpringBootApplication} — 组合注解，等价于同时声明
 *       {@code @Configuration}、{@code @EnableAutoConfiguration} 和
 *       {@code @ComponentScan}，触发 Spring Boot 自动配置机制，
 *       并扫描 {@code com.investory} 包及其子包下的所有组件。</li>
 *   <li>{@link EnableScheduling} — 启用 Spring 定时任务支持，
 *       使 {@code @Scheduled} 注解（如爬虫定时抓取任务）在启动后自动生效。</li>
 * </ul>
 *
 * <p>应用默认以嵌入式 Tomcat 启动，监听 {@code 8443} 端口（HTTPS），
 * 上下文路径为 {@code /investory}（见 {@code application.properties}）。
 */
@SpringBootApplication
@EnableScheduling
public class InvestoryApplication {

    /**
     * 应用程序主方法，JVM 启动入口。
     *
     * <p>委托 {@link SpringApplication#run} 完成以下工作：
     * <ol>
     *   <li>创建并刷新 Spring IoC 容器（ApplicationContext）。</li>
     *   <li>执行自动配置，加载数据源、MVC、安全等所有自动装配模块。</li>
     *   <li>启动嵌入式 Tomcat 服务器并开始监听端口。</li>
     *   <li>触发所有 {@code @Scheduled} 定时任务的调度器。</li>
     * </ol>
     *
     * @param args 命令行参数，可通过 {@code --key=value} 格式覆盖 application.properties 中的配置
     */
    public static void main(String[] args) {
        SpringApplication.run(InvestoryApplication.class, args);
    }
}
