package com.investory.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.service.AiModelConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/ai")
public class AiSettingsController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AiModelConfiguration configuration;
    public AiSettingsController(AiModelConfiguration configuration) {
        this.configuration = configuration;
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> authorizationError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", "unauthorized"));
    }
    private long userId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") instanceof Number id && id.longValue() > 0)
            return id.longValue();
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    @GetMapping("/settings")
    public AiModelConfiguration.Settings getSettings(HttpServletRequest request) {
        return configuration.settings(userId(request));
    }
    @PostMapping("/settings")
    public AiModelConfiguration.Settings saveSettings(@RequestBody AiModelConfiguration.SaveRequest body, HttpServletRequest request) {
        return configuration.save(userId(request), body);
    }
    @DeleteMapping("/settings/{id}")
    public AiModelConfiguration.Settings removeSettings(@PathVariable int id, HttpServletRequest request) {
        return configuration.remove(userId(request), id);
    }
    public record Selection(Integer id) {}
    @PostMapping("/settings/active")
    public AiModelConfiguration.Settings selectModel(@RequestBody Selection body, HttpServletRequest request) {
        return configuration.select(userId(request), body.id());
    }
    @PostMapping("/models")
    public Map<String, Object> listModels(@RequestBody AiModelConfiguration.ModelsRequest body, HttpServletRequest request) {
        long id = userId(request);
        String provider = AiModelConfiguration.validateProvider(body.provider());
        String base = AiModelConfiguration.normalizeBase(body.baseUrl());
        String key = configuration.resolveKey(id, body.id(), provider, base, body.apiKey());
        String path = "anthropic".equals(provider) && !base.endsWith("/v1") ? "/v1/models" : "/models";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET();
        if ("anthropic".equals(provider)) {
            builder.header("anthropic-version", "2023-06-01");
            if (!key.isBlank()) builder.header("x-api-key", key);
        } else if (!key.isBlank()) builder.header("Authorization", "Bearer " + key);
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                return Map.of("error", "模型列表请求失败（HTTP " + response.statusCode() + "），也可手动填写 Model ID");
            JsonNode root = JSON.readTree(response.body());
            SortedSet<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            collect(root.path("data"), ids);
            collect(root.path("models"), ids);
            collect(root, ids);
            if (ids.isEmpty()) return Map.of("error", "服务未返回模型列表，请手动填写 Model ID");
            return Map.of("models", ids, "count", ids.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("error", "模型列表请求已取消");
        } catch (Exception e) {
            // Upstream errors may contain credentials; never forward raw messages or bodies.
            return Map.of("error", "无法获取模型列表，请检查接口地址、密钥和服务状态，或手动填写 Model ID");
        }
    }
    private static void collect(JsonNode nodes, Set<String> ids) {
        if (!nodes.isArray()) return;
        for (JsonNode item : nodes) {
            if (ids.size() >= 1000) break;
            String id = item.isTextual() ? item.asText() : item.path("id").asText(item.path("model").asText(""));
            if (!id.isBlank() && id.length() <= 255) ids.add(id);
        }
    }
}
