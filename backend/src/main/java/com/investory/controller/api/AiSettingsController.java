package com.investory.controller.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/ai")
public class AiSettingsController {

    private final JdbcTemplate jdbc;

    @Autowired
    public AiSettingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        if (rows.isEmpty()) return Map.of("provider", "", "model", "", "baseUrl", "", "hasKey", false);

        Map<String, Object> row = rows.get(0);
        return Map.of(
            "provider", row.getOrDefault("provider", ""),
            "model", row.getOrDefault("model", ""),
            "baseUrl", row.getOrDefault("base_url", ""),
            "hasKey", row.getOrDefault("has_key", false)
        );
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "not authenticated");

        String provider = (String) body.getOrDefault("provider", "");
        String model = (String) body.getOrDefault("model", "");
        String baseUrl = (String) body.getOrDefault("baseUrl", "");
        String apiKey = (String) body.getOrDefault("apiKey", "");

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
