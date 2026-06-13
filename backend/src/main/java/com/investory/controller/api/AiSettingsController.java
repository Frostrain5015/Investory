package com.investory.controller.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.investory.server.ConfigLoader;
import com.investory.server.DatabaseManager;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.*;

public class AiSettingsController {

    private static final String DEFAULT_PROVIDER = "openai_compat";
    private static final String DEFAULT_MODEL = "deepseek-v4-pro";
    private static final String DEFAULT_OPENAI_COMPAT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    private final String defaultKey = ConfigLoader.get("ai.default.key", "");

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

    public void handleGetSettings(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not authenticated")));
            return;
        }

        List<Map<String, Object>> rows = jdbcQueryForList(
            "SELECT provider, model, base_url, LENGTH(api_key) > 0 AS has_key FROM ai_settings WHERE user_id = ?", userId);
        Map<String, Object> result;
        if (rows.isEmpty()) {
            result = Map.of(
                "provider", DEFAULT_PROVIDER,
                "model", DEFAULT_MODEL,
                "baseUrl", DEFAULT_OPENAI_COMPAT_BASE_URL,
                "hasKey", false
            );
        } else {
            Map<String, Object> row = rows.get(0);
            result = Map.of(
                "provider", str(row.get("provider")),
                "model", str(row.get("model")),
                "baseUrl", str(row.get("base_url")),
                "hasKey", row.getOrDefault("has_key", false)
            );
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleListModels(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not authenticated")));
            return;
        }

        // Read JSON body
        String jsonBody = new String(req.getReader().readAllBytes());
        var gson = new com.google.gson.Gson();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = gson.fromJson(jsonBody, Map.class);

        String provider = str(body.get("provider")).trim();
        String baseUrl = str(body.get("baseUrl")).trim();
        String apiKey = str(body.get("apiKey")).trim();

        if (!isSupportedProvider(provider)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "请选择 API 格式")));
            return;
        }

        if (apiKey.isBlank()) {
            List<Map<String, Object>> rows = jdbcQueryForList(
                "SELECT api_key FROM ai_settings WHERE user_id = ?", userId);
            if (!rows.isEmpty()) apiKey = str(rows.get(0).get("api_key")).trim();
        }
        if (apiKey.isBlank()) apiKey = defaultKey == null ? "" : defaultKey.trim();
        if (apiKey.isBlank()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "请先填写 API Key")));
            return;
        }

        try {
            List<String> models = "anthropic".equals(provider)
                ? fetchAnthropicModels(baseUrl, apiKey)
                : fetchOpenAiCompatibleModels(baseUrl, apiKey);
            if (models.isEmpty()) {
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(JsonUtil.toJson(Map.of("error", "未获取到可用模型")));
                return;
            }
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok", "models", models, "count", models.size())));
        } catch (Exception e) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", e.getMessage() == null ? "模型列表获取失败" : e.getMessage())));
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

        var json = new com.google.gson.Gson();
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collectModelIds(root.getAsJsonArray("data"), ids);
        collectModelIds(root.getAsJsonArray("models"), ids);

        List<String> models = new ArrayList<>(ids);
        models.sort(String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    private List<String> throwError(String message) {
        throw new IllegalStateException(message);
    }

    private void collectModelIds(com.google.gson.JsonArray node, Set<String> ids) {
        if (node == null || node.isJsonNull()) return;
        for (var element : node) {
            if (element.isJsonPrimitive()) {
                ids.add(element.getAsString());
            } else if (element.isJsonObject()) {
                var obj = element.getAsJsonObject();
                if (obj.has("id") && !obj.get("id").isJsonNull()) {
                    ids.add(obj.get("id").getAsString());
                } else if (obj.has("model") && !obj.get("model").isJsonNull()) {
                    ids.add(obj.get("model").getAsString());
                }
            }
        }
    }

    public void handleResetSettings(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not authenticated")));
            return;
        }
        jdbcUpdate("DELETE FROM ai_settings WHERE user_id = ?", userId);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    public void handleSaveSettings(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not authenticated")));
            return;
        }

        String jsonBody = new String(req.getReader().readAllBytes());
        var gson = new com.google.gson.Gson();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = gson.fromJson(jsonBody, Map.class);

        String provider = str(body.get("provider")).trim();
        String model = str(body.get("model")).trim();
        String baseUrl = str(body.get("baseUrl")).trim();
        String apiKey = str(body.get("apiKey")).trim();

        if (!isSupportedProvider(provider)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "请选择 API 格式")));
            return;
        }
        if (model.isBlank()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "model required")));
            return;
        }

        String key = apiKey.isBlank() ? "" : apiKey;
        jdbcUpdate(
            "INSERT INTO ai_settings (user_id, provider, model, base_url, api_key) VALUES (?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE provider=VALUES(provider), model=VALUES(model), base_url=VALUES(base_url), api_key=VALUES(api_key)",
            userId, provider, model, baseUrl, key);

        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    // ── JDBC helpers ─────────────────────────────────────────────────────

    private List<Map<String, Object>> jdbcQueryForList(String sql, Object... args) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= colCount; c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    result.add(row);
                }
            }
        }
        return result;
    }

    private int jdbcUpdate(String sql, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }
}
