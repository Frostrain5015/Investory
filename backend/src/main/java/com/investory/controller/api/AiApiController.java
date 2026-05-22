package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.crawler.AiSessionManager;
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

@RestController
@RequestMapping("/api/ai")
public class AiApiController {

    private static final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AiSessionManager session;

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    @Autowired
    public AiApiController(AiSessionManager session) {
        this.session = session;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        String aiKey = req.getHeader("X-AI-Key");
        String aiProvider = req.getHeader("X-AI-Provider");
        String aiModel = req.getHeader("X-AI-Model");
        String aiBaseUrl = req.getHeader("X-AI-Base-URL");

        if (aiKey == null || aiKey.isBlank()) return Map.of("error", "API key required");
        // Map custom providers to "openai_compat" for the Python agent
        final String provider;
        if ("anthropic".equals(aiProvider)) {
            provider = "anthropic";
        } else if (aiProvider != null && !"openai".equals(aiProvider)) {
            provider = "openai_compat";  // DeepSeek, Moonshot, custom, etc.
        } else {
            provider = "openai";
        }
        final String model = (aiModel != null && !aiModel.isBlank()) ? aiModel : "gpt-4o-mini";
        final String key = aiKey;
        final String baseUrl = (aiBaseUrl != null) ? aiBaseUrl : "";

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
                cmd.add("--api-key"); cmd.add(key);
                cmd.add("--input"); cmd.add(tmpInput.toString());
                if (!baseUrl.isBlank()) { cmd.add("--api-base"); cmd.add(baseUrl); }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(scriptDir);
                pb.redirectErrorStream(true);
                pb.environment().put("PYTHONUNBUFFERED", "1");

                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("[DONE]".equals(line.trim())) {
                            session.emitDone();
                        } else if (line.startsWith("[ERROR]")) {
                            session.emitError(line.substring(7).trim());
                        } else {
                            session.emitToken(line);
                        }
                    }
                }
                p.waitFor();
                Files.deleteIfExists(tmpInput);
            } catch (Exception e) {
                session.emitError(e.getMessage());
            } finally {
                session.clearSession();
            }
        });

        return Map.of("status", "started");
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
}
