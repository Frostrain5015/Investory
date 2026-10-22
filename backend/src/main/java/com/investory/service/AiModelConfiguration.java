package com.investory.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** User-owned model settings. No shared model, endpoint or credential fallback. */
@Service
public class AiModelConfiguration {
    public static final int MIN_CONTEXT = 1024;
    public static final int MAX_CONTEXT = 1_048_576;
    private final JdbcTemplate jdbc;

    public AiModelConfiguration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Model(int id, String provider, String model, String baseUrl, String apiKey, Integer contextWindow) {
        public boolean configured() {
            return List.of("openai_compat", "anthropic").contains(provider)
                && !model.isBlank() && !baseUrl.isBlank() && contextWindow != null
                && contextWindow >= MIN_CONTEXT && contextWindow <= MAX_CONTEXT;
        }
        @Override public String toString() { return "Model[provider=" + provider + ", credentials=redacted]"; }
    }
    public record View(int id, String provider, String model, String baseUrl, boolean hasKey,
                       Integer contextWindow, boolean configured) {}
    public record SaveRequest(Integer id, String provider, String model, String baseUrl, String apiKey,
                              @JsonDeserialize(using = ContextInteger.class) Integer contextWindow) {
        @Override public String toString() { return "SaveRequest[credentials=redacted]"; }
    }
    public record ModelsRequest(Integer id, String provider, String baseUrl, String apiKey) {
        @Override public String toString() { return "ModelsRequest[credentials=redacted]"; }
    }
    public static class ContextInteger extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws java.io.IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                throw JsonMappingException.from(parser, "上下文窗口须为整数");
            return parser.getIntValue();
        }
    }

    public record Settings(List<View> models, Integer activeModelId, int maxModels) {}
    private static final String SELECT = "SELECT slot_id, provider, model, base_url, api_key, context_window FROM ai_model_profiles WHERE user_id = ?";
    private static Model fromRow(Map<String, Object> row) {
        Integer context = row.get("context_window") instanceof Number n ? n.intValue() : null;
        return new Model(((Number) row.get("slot_id")).intValue(), text(row.get("provider")), text(row.get("model")),
            text(row.get("base_url")), text(row.get("api_key")), context);
    }
    private static View view(Model model) {
        return new View(model.id(), model.provider(), model.model(), model.baseUrl(), !model.apiKey().isBlank(),
            model.contextWindow(), model.configured());
    }
    public Model load(long userId, int id) {
        validateId(id);
        var rows = jdbc.queryForList(SELECT + " AND slot_id = ?", userId, id);
        return rows.isEmpty() ? new Model(id, "", "", "", "", null) : fromRow(rows.get(0));
    }
    public Integer selectedId(long userId) {
        var rows = jdbc.queryForList("SELECT slot_id FROM ai_model_selection WHERE user_id = ?", userId);
        return rows.isEmpty() || !(rows.get(0).get("slot_id") instanceof Number id) ? null : id.intValue();
    }
    public Settings settings(long userId) {
        List<View> models = jdbc.queryForList(SELECT + " ORDER BY slot_id", userId).stream()
            .map(AiModelConfiguration::fromRow).map(AiModelConfiguration::view).toList();
        return new Settings(models, selectedId(userId), 3);
    }
    public Model require(long userId) { return require(userId, null); }
    public Model require(long userId, Integer requestedId) {
        Integer id = requestedId == null ? selectedId(userId) : requestedId;
        if (id == null) throw new IllegalArgumentException("请先在设置中添加并选择观澜模型");
        Model model = load(userId, id);
        if (!model.configured()) throw new IllegalArgumentException("请先完善所选模型的配置，并确认上下文窗口");
        normalizeBase(model.baseUrl());
        return model;
    }
    @Transactional
    public Settings save(long userId, SaveRequest request) {
        int id = validateId(request.id());
        String provider = validateProvider(request.provider());
        String base = normalizeBase(request.baseUrl());
        String model = text(request.model()).trim();
        if (model.isEmpty() || model.length() > 255 || model.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请填写有效的 Model ID（最多 255 字符）");
        Integer context = request.contextWindow();
        if (context == null || context < MIN_CONTEXT || context > MAX_CONTEXT)
            throw new IllegalArgumentException("上下文窗口须为 1024 至 1048576 的整数");
        String key = resolveKey(userId, id, provider, base, request.apiKey());
        jdbc.update("INSERT INTO ai_model_profiles (user_id, slot_id, provider, model, base_url, api_key, context_window) VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE provider=VALUES(provider), model=VALUES(model), base_url=VALUES(base_url), "
            + "api_key=VALUES(api_key), context_window=VALUES(context_window)", userId, id, provider, model, base, key, context);
        jdbc.update("INSERT INTO ai_model_selection (user_id, slot_id) VALUES (?, ?) "
            + "ON DUPLICATE KEY UPDATE slot_id=COALESCE(slot_id, VALUES(slot_id))", userId, id);
        // The legacy row was migrated by V8; do not leave a second copy of credentials.
        jdbc.update("DELETE FROM ai_settings WHERE user_id = ?", userId);
        return settings(userId);
    }
    @Transactional
    public Settings select(long userId, Integer id) {
        require(userId, validateId(id));
        jdbc.update("INSERT INTO ai_model_selection (user_id, slot_id) VALUES (?, ?) ON DUPLICATE KEY UPDATE slot_id=VALUES(slot_id)", userId, id);
        return settings(userId);
    }
    @Transactional
    public Settings remove(long userId, int id) {
        validateId(id);
        jdbc.update("DELETE FROM ai_model_profiles WHERE user_id = ? AND slot_id = ?", userId, id);
        jdbc.update("UPDATE ai_model_selection SET slot_id = NULL WHERE user_id = ? AND slot_id = ?", userId, id);
        jdbc.update("DELETE FROM ai_settings WHERE user_id = ?", userId);
        return settings(userId);
    }
    /** Missing key retains credentials only within the same slot/protocol/endpoint. Empty clears. */
    public String resolveKey(long userId, Integer id, String provider, String base, String suppliedKey) {
        validateId(id);
        if (suppliedKey != null) {
            String key = suppliedKey.trim();
            if (key.length() > 4096 || key.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("API Key 格式不正确");
            return key;
        }
        Model saved = load(userId, id);
        if (!saved.provider().equals(provider) || saved.baseUrl().isBlank()) return "";
        return normalizeBase(saved.baseUrl()).equals(base) ? saved.apiKey() : "";
    }
    public static int validateId(Integer id) {
        if (id == null || id < 1 || id > 3) throw new IllegalArgumentException("最多可配置 3 个模型，请选择有效的模型槽位");
        return id;
    }
    public static String validateProvider(String value) {
        String provider = text(value).trim();
        if (!List.of("openai_compat", "anthropic").contains(provider))
            throw new IllegalArgumentException("请选择支持的 API 协议");
        return provider;
    }
    public static String normalizeBase(String value) {
        String base = text(value).trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(base);
            if (base.length() > 1024 || !List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("请填写 HTTP 或 HTTPS 接口地址，不含密钥、查询参数或片段");
        }
        return base;
    }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
