package com.investory.controller.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.investory.server.AppContext;
import com.investory.server.ConfigLoader;
import com.investory.server.DatabaseManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.util.*;

public class AiSettingsController {

    private static final Gson gson = new Gson();
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
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) {
            resp.getWriter().write("{\"error\":\"not authenticated\"}");
            return;
        }

        List<Map<String, Object>> rows = queryForList(
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
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleListModels(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) {
            resp.getWriter().write("{\"error\":\"not authenticated\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (var reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        JsonObject body = gson.fromJson(sb.toString(), JsonObject.class);

        String provider = str(getJsonString(body, "provider")).trim();
        String baseUrl = str(getJsonString(body, "baseUrl")).trim();
        String apiKey = str(getJsonString(body, "apiKey")).trim();

        if (!isSupportedProvider(provider)) {
            resp.getWriter().write("{\"error\":\"请选择 API 格式\"}");
            return;
        }

        if (apiKey.isBlank()) {
            List<Map<String, Object>> rows = queryForList(
                "SELECT api_key FROM ai_settings WHERE user_id = ?", userId);
            if (!rows.isEmpty()) apiKey = str(rows.get(0).get("api_key")).trim();
        }
        if (apiKey.isBlank()) apiKey = defaultKey == null ? "" : defaultKey.trim();
        if (apiKey.isBlank()) {
            resp.getWriter().write("{\"error\":\"请先填写 API Key\"}");
            return;
        }

        try {
            List<String> models = "anthropic".equals(provider)
                ? fetchAnthropicModels(baseUrl, apiKey)
                : fetchOpenAiCompatibleModels(baseUrl, apiKey);
            if (models.isEmpty()) {
                resp.getWriter().write("{\"error\":\"未获取到可用模型\"}");
                return;
            }
            Map<String, Object> r = Map.of("status", "ok", "models", models, "count", models.size());
            resp.getWriter().write(gson.toJson(r));
        } catch (Exception e) {
            resp.getWriter().write("{\"error\":\"" + (e.getMessage() == null ? "模型列表获取失败" : e.getMessage()) + "\"}");
        }
    }

    public void handleResetSettings(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) {
            resp.getWriter().write("{\"error\":\"not authenticated\"}");
            return;
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM ai_settings WHERE user_id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        }
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    public void handleSaveSettings(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) {
            resp.getWriter().write("{\"error\":\"not authenticated\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (var reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        JsonObject body = gson.fromJson(sb.toString(), JsonObject.class);

        String provider = str(getJsonString(body, "provider")).trim();
        String model = str(getJsonString(body, "model")).trim();
        String baseUrl = str(getJsonString(body, "baseUrl")).trim();
        String apiKey = str(getJsonString(body, "apiKey")).trim();

        if (!isSupportedProvider(provider)) {
            resp.getWriter().write("{\"error\":\"请选择 API 格式\"}");
            return;
        }
        if (model.isBlank()) {
            resp.getWriter().write("{\"error\":\"model required\"}");
            return;
        }

        String key = apiKey.isBlank() ? "" : apiKey;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ai_settings (user_id, provider, model, base_url, api_key) VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE provider=VALUES(provider), model=VALUES(model), base_url=VALUES(base_url), api_key=VALUES(api_key)")) {
            ps.setObject(1, userId);
            ps.setString(2, provider);
            ps.setString(3, model);
            ps.setString(4, baseUrl);
            ps.setString(5, key);
            ps.executeUpdate();
        }
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    // ── Private helpers ──────────────────────────────────────────────

    private String getJsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
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
            switch (resp.statusCode()) {
                case 401:
                case 403:
                    throw new IllegalStateException("API Key 无效或没有模型列表权限");
                default:
                    throw new IllegalStateException("模型列表请求失败: HTTP " + resp.statusCode());
            }
        }

        JsonObject root = gson.fromJson(resp.body(), JsonObject.class);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (root.has("data")) collectModelIds(root.get("data"), ids);
        if (root.has("models")) collectModelIds(root.get("models"), ids);

        List<String> models = new ArrayList<>(ids);
        models.sort(String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    private void collectModelIds(JsonElement node, Set<String> ids) {
        if (node == null || !node.isJsonArray()) return;
        for (JsonElement item : node.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                ids.add(item.getAsString());
            } else if (item.isJsonObject()) {
                JsonObject obj = item.getAsJsonObject();
                if (obj.has("id") && !obj.get("id").isJsonNull()) ids.add(obj.get("id").getAsString());
                else if (obj.has("model") && !obj.get("model").isJsonNull()) ids.add(obj.get("model").getAsString());
            }
        }
    }

    private List<Map<String, Object>> queryForList(String sql, Object... params) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int colCount = rsmd.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(rsmd.getColumnLabel(i), rs.getObject(i));
                    }
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
        return results;
    }
}
