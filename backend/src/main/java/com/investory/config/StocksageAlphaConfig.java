package com.investory.config;

import com.investory.util.StocksageAlphaExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * StockSage Alpha 桥接配置。
 *
 * <p>解析 Python 可执行文件路径和桥接脚本位置，
 * 提供 {@link StocksageAlphaExecutor} Bean，供 Service 层调用 Python 量化引擎。
 */
@Configuration
public class StocksageAlphaConfig {

    @Value("${python.executable:python}")
    private String pythonExecutable;

    /**
     * 创建 StockSage Alpha 桥接执行器。
     *
     * <p>在开发和部署环境中自动解析 Python 脚本的工作目录：
     * <ul>
     *   <li>开发：脚本位于 {@code backend/src/main/python/stocksage_alpha/}</li>
     *   <li>部署：脚本随 JAR 打包在 classpath 中</li>
     * </ul>
     *
     * @return 配置好的 StockSage Alpha 执行器 Bean
     */
    @Bean
    public StocksageAlphaExecutor stocksageAlphaExecutor() {
        return new StocksageAlphaExecutor(pythonExecutable);
    }
}
