package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.crawler.AiSessionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/ai")
public class AiApiController {

    private static final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final AiSessionManager session;
    private final JdbcTemplate jdbc;

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    @Value("${ai.default.key:}")
    private String defaultKey;

    private static final String DEFAULT_PROVIDER = "bailian";
    private static final String DEFAULT_MODEL   = "qwen3.7-max";
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    @Autowired
    public AiApiController(AiSessionManager session, JdbcTemplate jdbc) {
        this.session = session;
        this.jdbc = jdbc;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        jakarta.servlet.http.HttpSession s = req.getSession(false);
        long userId = 0;
        if (s != null) {
            Object uid = s.getAttribute("userId");
            if (uid instanceof Number) userId = ((Number) uid).longValue();
        }

        // Resolve AI config: user's saved settings > defaults
        String aiKey = defaultKey;
        String aiProvider = DEFAULT_PROVIDER;
        String aiModel = DEFAULT_MODEL;
        String aiBaseUrl = DEFAULT_BASE_URL;

        if (userId > 0) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT provider, model, base_url, api_key FROM ai_settings WHERE user_id = ?", userId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                String savedKey = (String) row.get("api_key");
                if (savedKey != null && !savedKey.isBlank()) {
                    aiKey = savedKey;
                    aiProvider = row.getOrDefault("provider", DEFAULT_PROVIDER).toString();
                    aiModel = row.getOrDefault("model", DEFAULT_MODEL).toString();
                    aiBaseUrl = row.getOrDefault("base_url", DEFAULT_BASE_URL).toString();
                }
            }
        }

        final String provider = "anthropic".equals(aiProvider) ? "anthropic" : "openai".equals(aiProvider) ? "openai" : "openai_compat";
        final String model = aiModel;
        final String key = aiKey;
        final String baseUrl = aiBaseUrl;
        final boolean deepThink = Boolean.TRUE.equals(body.get("deepThink"));

        long portfolioId = 0;
        if (s != null) {
            Object pid = s.getAttribute("portfolioId");
            if (pid instanceof Number) portfolioId = ((Number) pid).longValue();
        }
        final long pid = portfolioId;
        final long uid = userId;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        if (messages == null || messages.isEmpty()) return Map.of("error", "messages required");

        session.startSession();

        executor.submit(() -> {
            try {
                File script = new File("script/ai_agent.py");
                if (!script.exists()) {
                    script = new File("../script/ai_agent.py").getCanonicalFile();
                }
                if (!script.exists()) {
                    session.emitError("AI 引擎脚本未找到");
                    session.clearSession();
                    return;
                }
                File scriptDir = script.getParentFile();

                // Write messages to temp file
                Path tmpInput = Files.createTempFile("ai_input_", ".json");
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("messages", messages);
                Files.writeString(tmpInput, json.writeValueAsString(input));

                java.util.List<String> cmd = new java.util.ArrayList<>();
                cmd.add(pythonExecutable); cmd.add("-u"); cmd.add(script.getAbsolutePath());
                cmd.add("--provider"); cmd.add(provider);
                cmd.add("--model"); cmd.add(model);
                cmd.add("--portfolio-id"); cmd.add(String.valueOf(pid));
                cmd.add("--user-id"); cmd.add(String.valueOf(uid));
                cmd.add("--input"); cmd.add(tmpInput.toString());
                if (deepThink) { cmd.add("--deep-think"); }
                if (!baseUrl.isBlank()) { cmd.add("--api-base"); cmd.add(baseUrl); }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(scriptDir);
                pb.redirectErrorStream(true);
                pb.environment().put("PYTHONUNBUFFERED", "1");
                pb.environment().put("AI_API_KEY", key);  // env var, not CLI arg (ps aux invisible)

                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("[DONE]".equals(line.trim())) {
                            session.emitDone();
                        } else if (line.startsWith("[ASK]")) {
                            try {
                                String jsonStr = line.substring(5).trim();
                                Map<String, Object> askData = json.readValue(jsonStr, Map.class);
                                session.emitAsk(askData);
                            } catch (Exception ignored) {}
                        } else if (line.startsWith("[STRATEGY]")) {
                            try {
                                String jsonStr = line.substring(10).trim();
                                Map<String, Object> sdata = json.readValue(jsonStr, Map.class);
                                session.emitStrategy(sdata);
                            } catch (Exception ignored) {}
                        } else if (line.startsWith("[TOOL]")) {
                            session.emitTool(line.substring(6).trim());
                        } else if (line.startsWith("[CONFIRM]")) {
                            session.emitConfirm(line.substring(9).trim());
                        } else if (line.startsWith("[ERROR]")) {
                            session.emitError(line.substring(7).trim());
                        } else {
                            // Empty lines represent newlines in the model's output
                            // (Python appends \n as protocol delimiter, readLine strips it,
                            //  leaving "" for every \n the model actually produced)
                            session.emitToken(line.isEmpty() ? "\n" : line);
                        }
                    }
                }
                boolean finished = p.waitFor(10, TimeUnit.MINUTES);
                if (!finished) { p.destroyForcibly(); session.emitError("AI 对话超时"); }
                Files.deleteIfExists(tmpInput);
            } catch (Exception e) {
                session.emitError(e.getMessage());
            } finally {
                session.clearSession();
            }
        });

        return Map.of("status", "started");
    }

    @GetMapping("/suggestions")
    public Map<String, Object> suggestions(HttpServletRequest req) {
        jakarta.servlet.http.HttpSession s = req.getSession(false);
        long userId = s != null && s.getAttribute("userId") instanceof Number
            ? ((Number) s.getAttribute("userId")).longValue() : 0;

        String aiKey = defaultKey, aiProvider = DEFAULT_PROVIDER, aiModel = DEFAULT_MODEL, aiBaseUrl = DEFAULT_BASE_URL;
        if (userId > 0) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT provider, model, base_url, api_key FROM ai_settings WHERE user_id = ?", userId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                String savedKey = (String) row.get("api_key");
                if (savedKey != null && !savedKey.isBlank()) {
                    aiKey = savedKey;
                    aiProvider = row.getOrDefault("provider", DEFAULT_PROVIDER).toString();
                    aiModel = row.getOrDefault("model", DEFAULT_MODEL).toString();
                    aiBaseUrl = row.getOrDefault("base_url", DEFAULT_BASE_URL).toString();
                }
            }
        }

        // Use fast model on DashScope for suggestions
        final String provider = "anthropic".equals(aiProvider) ? "anthropic" : "openai_compat";
        final String model = aiModel;
        final String key = aiKey;
        final String baseUrl = aiBaseUrl;

        try {
            File script = new File("script/ai_agent.py");
            if (!script.exists()) script = new File("../script/ai_agent.py").getCanonicalFile();
            if (!script.exists()) return Map.of("suggestions", List.of("我的组合风险怎么样？", "分析一下我的持仓风格", "帮我写一个均线策略"));

            List<String> cmd = new java.util.ArrayList<>(List.of(
                pythonExecutable, "-u", script.getAbsolutePath(),
                "--mode", "suggestions",
                "--provider", provider,
                "--model", model
            ));
            if (!baseUrl.isBlank()) { cmd.add("--api-base"); cmd.add(baseUrl); }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(false);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.environment().put("AI_API_KEY", key);

            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), "UTF-8").trim();
            boolean finished = p.waitFor(5, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly(); }

            if (out.startsWith("[")) {
                @SuppressWarnings("unchecked")
                List<String> suggestions = json.readValue(out, List.class);
                return Map.of("suggestions", suggestions);
            }
        } catch (Exception ignored) {}

        return Map.of("suggestions", List.of("我的组合风险怎么样？", "分析一下我的持仓风格", "帮我写一个均线策略"));
    }

    @GetMapping("/stream")
    public SseEmitter stream(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        if (!session.isActive()) {
            SseEmitter err = new SseEmitter();
            try { err.send(SseEmitter.event().name("error").data(Map.of("msg", "无活跃对话"))); } catch (IOException ignored) {}
            err.complete();
            return err;
        }
        return session.subscribe();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return session.getStatus();
    }

    @PostMapping("/clear")
    public Map<String, Object> clear() {
        session.clearSession();
        return Map.of("status", "cleared");
    }
}
