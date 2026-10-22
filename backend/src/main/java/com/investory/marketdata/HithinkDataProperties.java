package com.investory.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("investory.hithink")
public record HithinkDataProperties(boolean enabled, String apiKey, String baseUrl, int timeoutMs) {
    public HithinkDataProperties {
        apiKey = apiKey == null ? "" : apiKey.strip();
        baseUrl = baseUrl == null ? "https://fuyao.aicubes.cn" : baseUrl.strip().replaceAll("/+$", "");
        URI uri = URI.create(baseUrl);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("同花顺数据地址须为 HTTPS 服务根地址；HTTP 只用于本机测试");
        }
        if (timeoutMs < 100 || timeoutMs > 30000) throw new IllegalArgumentException("同花顺请求超时须在 100–30000ms 内");
        if (apiKey.chars().anyMatch(c -> Character.isISOControl(c))) throw new IllegalArgumentException("同花顺密钥包含无效字符");
    }

    @Override public String toString() {
        return "HithinkDataProperties[enabled=" + enabled + ", apiKey=[REDACTED], timeoutMs=" + timeoutMs + "]";
    }
}
