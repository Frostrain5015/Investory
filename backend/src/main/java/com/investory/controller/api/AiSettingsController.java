package com.investory.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/ai")
public class AiSettingsController {

    private static final ObjectMapper json = new ObjectMapper();
    private static final String DEFAULT_PROVIDER = "openai_compat";
    private static final String DEFAULT_MODEL = "qwen-plus-latest";
    private static final String DEFAULT_OPENAI_COMPAT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    private final JdbcTemplate jdbc;

    @Value("${ai.default.key:}")
    private String defaultKey;

    @Autowired
    public AiSettingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean isSupportedProvider(String provider) {
        return DEFAULT_PROVIDER.equals(provider) || "anthropic".equals(provider);
    }

    private String stripTrailingSlash(String value) {
        String s = value == null ? "" : value.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private long getUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object uid = s.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings(HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "not authenticated");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT provider, model, base_url, LENGTH(api_key) > 0 AS has_key FROM ai_settings WHERE user_id = ?", userId);
        if (rows.isEmpty()) {
            return Map.of(
                "provider", DEFAULT_PROVIDER,
                "model", DEFAULT_MODEL,
                "baseUrl", DEFAULT_OPENAI_COMPAT_BASE_URL,
                "hasKey", false
            );
        }

        Map<String, Object> row = rows.get(0);
        return Map.of(
            "provider", str(row.get("provider")),
            "model", str(row.get("model")),
            "baseUrl", str(row.get("base_url")),
            "hasKey", row.getOrDefault("has_key", false)
        );
    }

    @PostMapping("/models")
    public Map<String, Object> listModels(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "not authenticated");

        String provider = str(body.get("provider")).trim();
        String baseUrl = str(body.get("baseUrl")).trim();
        String apiKey = str(body.get("apiKey")).trim();

        if (!isSupportedProvider(provider)) return Map.of("error", "请选择 API 格式");

        if (apiKey.isBlank()) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT api_key FROM ai_settings WHERE user_id = ?", userId);
            if (!rows.isEmpty()) apiKey = str(rows.get(0).get("api_key")).trim();
        }
        if (apiKey.isBlank()) apiKey = defaultKey == null ? "" : defaultKey.trim();
        if (apiKey.isBlank()) return Map.of("error", "请先填写 API Key");

        try {
            List<String> models = "anthropic".equals(provider)
                ? fetchAnthropicModels(baseUrl, apiKey)
                : fetchOpenAiCompatibleModels(baseUrl, apiKey);
            if (models.isEmpty()) return Map.of("error", "未获取到可用模型");
            return Map.of("status", "ok", "models", models, "count", models.size());
        } catch (Exception e) {
            return Map.of("error", e.getMessage() == null ? "模型列表获取失败" : e.getMessage());
        }
    }

    private List<String> fetchOpenAiCompatibleModels(String baseUrl, String apiKey) throws Exception {
        String base = stripTrailingSlash(baseUrl.isBlank() ? OPENAI_BASE_URL : baseUrl);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/models"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json")
            .GET()
            .build();
        return fetchModels(req);
    }

    private List<String> fetchAnthropicModels(String baseUrl, String apiKey) throws Exception {
        String base = stripTrailingSlash(baseUrl.isBlank() ? DEFAULT_ANTHROPIC_BASE_URL : baseUrl);
        String url = base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "application/json")
            .GET()
            .build();
        return fetchModels(req);
    }

    private List<String> fetchModels(HttpRequest req) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return switch (resp.statusCode()) {
                case 401, 403 -> throwError("API Key 无效或没有模型列表权限");
                default -> throwError("模型列表请求失败: HTTP " + resp.statusCode());
            };
        }

        JsonNode root = json.readTree(resp.body());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collectModelIds(root.path("data"), ids);
        collectModelIds(root.path("models"), ids);
        if (root.isArray()) collectModelIds(root, ids);

        List<String> models = new ArrayList<>(ids);
        models.sort(String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    private List<String> throwError(String message) {
        throw new IllegalStateException(message);
    }

    private void collectModelIds(JsonNode node, Set<String> ids) {
        if (node == null || node.isMissingNode() || !node.isArray()) return;
        for (JsonNode item : node) {
            if (item.isTextual()) {
                ids.add(item.asText());
            } else if (item.hasNonNull("id")) {
                ids.add(item.get("id").asText());
            } else if (item.hasNonNull("model")) {
                ids.add(item.get("model").asText());
            }
        }
    }

    @DeleteMapping("/settings")
    public Map<String, Object> resetSettings(HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "not authenticated");
        jdbc.update("DELETE FROM ai_settings WHERE user_id = ?", userId);
        return Map.of("status", "ok");
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "not authenticated");

        String provider = str(body.get("provider")).trim();
        String model = str(body.get("model")).trim();
        String baseUrl = str(body.get("baseUrl")).trim();
        String apiKey = str(body.get("apiKey")).trim();

        if (!isSupportedProvider(provider)) return Map.of("error", "请选择 API 格式");
        if (model.isBlank()) return Map.of("error", "model required");

        // Upsert: only update api_key if provided (non-empty)
        if (apiKey != null && !apiKey.isBlank()) {
            jdbc.update(
                "INSERT INTO ai_settings (user_id, provider, model, base_url, api_key) VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE provider=VALUES(provider), model=VALUES(model), base_url=VALUES(base_url), api_key=VALUES(api_key)",
                userId, provider, model, baseUrl, apiKey);
        } else {
            jdbc.update(
                "INSERT INTO ai_settings (user_id, provider, model, base_url) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE provider=VALUES(provider), model=VALUES(model), base_url=VALUES(base_url)",
                userId, provider, model, baseUrl);
        }

        return Map.of("status", "ok");
    }

}
