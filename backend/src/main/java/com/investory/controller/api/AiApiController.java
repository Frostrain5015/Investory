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
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;

@RestController
@RequestMapping("/api/ai")
public class AiApiController {

    private static final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor;
    private final AiSessionManager session;
    private final JdbcTemplate jdbc;

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    @Value("${ai.default.key:}")
    private String defaultKey;

    @Value("${ai.title.enabled:false}")
    private boolean aiTitleEnabled;

    private static final String DEFAULT_PROVIDER = "openai_compat";
    private static final String DEFAULT_MODEL   = "deepseek-v4-pro";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final List<String> STATIC_SUGGESTIONS = List.of(
        "\u6211\u7684\u7ec4\u5408\u98ce\u9669\u600e\u4e48\u6837\uff1f",
        "\u5206\u6790\u4e00\u4e0b\u6211\u7684\u6301\u4ed3\u98ce\u683c",
        "\u5e2e\u6211\u5199\u4e00\u4e2a\u5747\u7ebf\u7b56\u7565"
    );

    private boolean isSupportedProvider(String provider) {
        return DEFAULT_PROVIDER.equals(provider) || "anthropic".equals(provider);
    }

    @Autowired
    public AiApiController(AiSessionManager session, JdbcTemplate jdbc,
                           @Qualifier("aiExecutor") ExecutorService executor) {
        this.session = session;
        this.jdbc = jdbc;
        this.executor = executor;
    }

    /**
     * Feed the user's answer back to the running Python process.
     * Called when the user clicks an ask_user option card in the frontend.
     * The Python process is blocking on stdin waiting for this answer.
     */
    @PostMapping("/answer")
    public Map<String, Object> answer(@RequestBody Map<String, Object> body,
                                       HttpServletRequest req) {
        long uid = userIdOf(req);
        String answer = body.get("answer") instanceof String ? (String) body.get("answer") : "";
        if (answer.isBlank()) return Map.of("error", "answer required");
        boolean ok = session.writeAnswer(uid, answer);
        return Map.of("ok", ok);
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
        if (userId <= 0) return Map.of("error", "not authenticated");

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
                    String savedProvider = row.getOrDefault("provider", DEFAULT_PROVIDER).toString();
                    if (!isSupportedProvider(savedProvider)) {
                        return Map.of("error", "请先在设置页重新选择 API 格式");
                    }
                    aiKey = savedKey;
                    aiProvider = savedProvider;
                    aiModel = row.getOrDefault("model", DEFAULT_MODEL).toString();
                    aiBaseUrl = row.getOrDefault("base_url", DEFAULT_BASE_URL).toString();
                }
            }
        }
        if (aiKey == null || aiKey.isBlank()) {
            return Map.of("error", "AI API key not configured");
        }

        final String provider = "anthropic".equals(aiProvider) ? "anthropic" : "openai_compat";
        final String key = aiKey;
        final String baseUrl = aiBaseUrl;
        final boolean deepThink = Boolean.TRUE.equals(body.get("deepThink"));

        // DeepSeek: deep-think implies the reasoning-tier model (v4-pro); fast mode uses v4-flash.
        // Also rewrites legacy names (deepseek-chat / deepseek-reasoner) that may still be cached in the client.
        String resolvedModel = aiModel;
        boolean isDeepseek = baseUrl != null && baseUrl.contains("deepseek");
        if (isDeepseek) {
            if ("deepseek-chat".equals(resolvedModel)) resolvedModel = "deepseek-v4-flash";
            else if ("deepseek-reasoner".equals(resolvedModel)) resolvedModel = "deepseek-v4-pro";
            if (deepThink && !"deepseek-v4-pro".equals(resolvedModel)) resolvedModel = "deepseek-v4-pro";
        }
        final String model = resolvedModel;

        long portfolioId = 0;
        if (s != null) {
            Object pid = s.getAttribute("portfolioId");
            if (pid instanceof Number) portfolioId = ((Number) pid).longValue();
        }
        final long pid = portfolioId;
        final long uid = userId;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawMessages = (List<Map<String, Object>>) body.get("messages");
        if (rawMessages == null || rawMessages.isEmpty()) return Map.of("error", "messages required");

        final boolean webSearch = Boolean.TRUE.equals(body.get("webSearch"));

        // Multi-conversation: accept optional conversationId, auto-create if absent.
        long conversationId = 0;
        Object cidRaw = body.get("conversationId");
        if (cidRaw instanceof Number) conversationId = ((Number) cidRaw).longValue();
        if (conversationId <= 0 && userId > 0) {
            try {
                jdbc.update("INSERT INTO ai_conversations (user_id, title) VALUES (?, '无标题')", userId);
                conversationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            } catch (Exception ignored) {}
        }
        final long convId = conversationId;

        // #2 Context window guard: the frontend re-sends the whole UI history, which
        // grows unbounded over a long chat and will eventually blow the model context.
        // Keep only the most recent turns (plus the freshly injected system blocks).
        final int MAX_HISTORY = 24;
        List<Map<String, Object>> windowed = rawMessages.size() > MAX_HISTORY
            ? new ArrayList<>(rawMessages.subList(rawMessages.size() - MAX_HISTORY, rawMessages.size()))
            : rawMessages;

        // Build the leading system blocks: fresh portfolio profile + last turn's tool
        // results (#1). DashScope's ephemeral cache dedupes identical system blocks,
        // so re-injecting costs nothing when nothing changed.
        List<Map<String, Object>> systemBlocks = new ArrayList<>();
        if (portfolioId > 0) {
            String ctx = buildPortfolioHint(portfolioId);
            if (!ctx.isEmpty()) systemBlocks.add(Map.of("role", "system", "content", ctx));
        }
        if (userId > 0) {
            try {
                List<Map<String, Object>> td = jdbc.queryForList(
                    "SELECT content FROM ai_chat_history WHERE user_id = ? AND role = 'tooldata' ORDER BY id DESC LIMIT 1", userId);
                if (!td.isEmpty()) {
                    String tc = String.valueOf(td.get(0).get("content"));
                    if (tc != null && !tc.isBlank()) {
                        systemBlocks.add(Map.of("role", "system", "content",
                            "【上一轮工具已获取的数据（供延续对话参考，已是最新拉取，不必重复查询；如需更新再调用工具）】\n" + tc));
                    }
                }
            } catch (Exception ignored) {}
        }

        final List<Map<String, Object>> messages;
        if (!systemBlocks.isEmpty()) {
            List<Map<String, Object>> withCtx = new ArrayList<>(systemBlocks.size() + windowed.size());
            withCtx.addAll(systemBlocks); withCtx.addAll(windowed);
            messages = withCtx;
        } else { messages = windowed; }

        // Persist the user message (last in the list) before we kick off generation
        if (userId > 0 && !rawMessages.isEmpty()) {
            Map<String, Object> lastMsg = rawMessages.get(rawMessages.size() - 1);
            if ("user".equals(lastMsg.get("role"))) {
                String content = String.valueOf(lastMsg.getOrDefault("content", ""));
                try {
                    jdbc.update("INSERT INTO ai_chat_history (user_id, conversation_id, role, content) VALUES (?, ?, 'user', ?)",
                        userId, convId, content.length() > 4000 ? content.substring(0, 4000) : content);
                    // Summarise conversation title via LLM after the first turn (only if still the default).
                    if (convId > 0) {
                        summarizeTitleAsync(convId, content, userId);
                    }
                } catch (Exception ignored) {}
            }
        }

        session.startSession(uid);

        executor.submit(() -> {
            try {
                File script = new File("script/ai_agent.py");
                if (!script.exists()) {
                    script = new File("../script/ai_agent.py").getCanonicalFile();
                }
                if (!script.exists()) {
                    session.emitError(uid, "AI 引擎脚本未找到");
                    session.finishSession(uid);
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
                if (webSearch) { cmd.add("--web-search"); }
                if (!baseUrl.isBlank()) { cmd.add("--api-base"); cmd.add(baseUrl); }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(scriptDir);
                pb.redirectErrorStream(true);
                pb.environment().put("PYTHONUNBUFFERED", "1");
                pb.environment().put("AI_API_KEY", key);  // env var, not CLI arg (ps aux invisible)

                Process p = pb.start();
                session.bindProcess(uid, p);
                // Accumulate assistant text + the timeline (interleaved thinking and tool steps).
                // Timeline stays in chronological order: each new tool call ends the current
                // thinking segment; the next reasoning chunk starts a fresh one.
                StringBuilder accumContent = new StringBuilder();
                List<Map<String, Object>> timeline = new ArrayList<>();
                // Compact digest of this turn's tool results (#1), emitted by the
                // agent as a [CONTEXT] line; persisted and replayed next turn.
                final String[] toolContext = { null };
                final List<Map<String, Object>> pendingArtifacts = new ArrayList<>();
                // Pointer to the current open thinking step (so we can keep appending to it).
                final Map<String, Object>[] openThinking = new Map[]{ null };
                // Stamp _elapsed on the current open thinking step and close it.
                // Called before a tool/KB line or at process end so persisted
                // timeline steps carry actual timing (not 0s after history reload).
                final Runnable closeThinking = () -> {
                    if (openThinking[0] != null) {
                        long started = (long) openThinking[0].getOrDefault("_ts", 0L);
                        if (started > 0) openThinking[0].put("_elapsed", System.currentTimeMillis() - started);
                        openThinking[0] = null;
                    }
                };
                java.util.function.Consumer<String> appendThinking = (chunk) -> {
                    if (openThinking[0] == null) {
                        Map<String, Object> step = new LinkedHashMap<>();
                        step.put("kind", "thinking"); step.put("text", "");
                        step.put("_ts", System.currentTimeMillis());  // for _elapsed on reload
                        timeline.add(step);
                        openThinking[0] = step;
                    }
                    openThinking[0].put("text", openThinking[0].get("text").toString() + chunk);
                };
                // The answer text is its own timeline step, interleaved with thinking
                // and tool steps in true emission order — so a chunk of answer written
                // before a later tool call is persisted (and replayed) ABOVE that tool,
                // never dumped below the whole timeline. A new tool/KB/thinking event
                // closes the open text step so subsequent answer text starts a new one.
                final Map<String, Object>[] openText = new Map[]{ null };
                final Runnable closeText = () -> { openText[0] = null; };
                java.util.function.Consumer<String> appendText = (chunk) -> {
                    if (openText[0] == null) {
                        Map<String, Object> step = new LinkedHashMap<>();
                        step.put("kind", "text"); step.put("text", "");
                        timeline.add(step);
                        openText[0] = step;
                    }
                    openText[0].put("text", openText[0].get("text").toString() + chunk);
                };
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("[DONE]".equals(line.trim())) {
                            session.emitDone(uid);
                        } else if (line.startsWith("[ASK]")) {
                            try {
                                String jsonStr = line.substring(5).trim();
                                Map<String, Object> askData = json.readValue(jsonStr, Map.class);
                                session.emitAsk(uid, askData);
                            } catch (Exception ignored) {}
                        } else if (line.startsWith("[STRATEGY]")) {
                            try {
                                String jsonStr = line.substring(10).trim();
                                Map<String, Object> sdata = json.readValue(jsonStr, Map.class);
                                session.emitStrategy(uid, sdata);
                            } catch (Exception ignored) {}
                        } else if (line.startsWith("[TOOL_END]")) {
                            // Payload: "<callId>\t<name>\t<summary>" (summary optional).
                            // callId pairs with the [TOOL] that started this call (#3).
                            String[] parts = line.substring(10).trim().split("\t", 3);
                            String callId = parts.length > 0 ? parts[0] : "";
                            String name = parts.length > 1 ? parts[1] : "";
                            String summary = parts.length > 2 ? parts[2].trim() : "";
                            markToolDone(timeline, callId, name, true, summary, null);
                            session.emitToolEnd(uid, callId, name, summary);
                        } else if (line.startsWith("[TOOL_FAIL]")) {
                            // Payload: "<callId>\t<name>\t<message>"
                            String[] parts = line.substring(11).trim().split("\t", 3);
                            String callId = parts.length > 0 ? parts[0] : "";
                            String name = parts.length > 1 ? parts[1] : "";
                            String errMsg = parts.length > 2 ? parts[2] : "";
                            markToolDone(timeline, callId, name, true, null, errMsg);
                            session.emitToolFail(uid, callId, name, errMsg);
                        } else if (line.startsWith("[KB]")) {
                            // Payload: "<topic>" — agent consulted the knowledge base.
                            String topic = line.substring(4).trim();
                            closeThinking.run();
                            closeText.run();
                            Map<String, Object> kbStep = new LinkedHashMap<>();
                            kbStep.put("kind", "kb"); kbStep.put("topic", topic);
                            timeline.add(kbStep);
                            session.emitKb(uid, topic);
                        } else if (line.startsWith("[MEMORY]")) {
                            // Payload: "<count>" — relevant user memories were recalled.
                            String cnt = line.substring(8).trim();
                            closeThinking.run();
                            closeText.run();
                            Map<String, Object> memStep = new LinkedHashMap<>();
                            memStep.put("kind", "memory"); memStep.put("count", cnt);
                            timeline.add(memStep);
                            session.emitMemory(uid, cnt);
                        } else if (line.startsWith("[TOOL]")) {
                            // Payload: "<name>\t<category>\t<callId>" (latter two optional)
                            String[] parts = line.substring(6).trim().split("\t", 3);
                            String name = parts.length > 0 ? parts[0] : "";
                            String category = parts.length > 1 ? parts[1].trim() : "query";
                            String callId = parts.length > 2 ? parts[2].trim() : "";
                            // A new tool call closes the current thinking + text segments
                            closeThinking.run();
                            closeText.run();
                            Map<String, Object> step = new LinkedHashMap<>();
                            step.put("kind", "tool"); step.put("name", name);
                            step.put("category", category); step.put("done", false);
                            if (!callId.isEmpty()) step.put("callId", callId);
                            timeline.add(step);
                            session.emitTool(uid, name, category, callId);
                        } else if (line.startsWith("[ARTIFACT]")) {
                            try {
                                String jsonStr = line.substring(10).trim();
                                Map<String, Object> artifact = json.readValue(jsonStr, Map.class);
                                long artifactId = persistArtifact(uid, convId, artifact);
                                if (artifactId > 0) artifact.put("id", artifactId);
                                pendingArtifacts.add(artifact);
                                session.emitArtifact(uid, artifact);
                            } catch (Exception ignored) {}
                        } else if (line.startsWith("[CONTEXT]")) {
                            // Compact tool-result digest for cross-turn continuity (#1)
                            toolContext[0] = line.substring(9).trim();
                        } else if (line.startsWith("[CONFIRM]")) {
                            session.emitConfirm(uid, line.substring(9).trim());
                        } else if (line.startsWith("[ERROR]")) {
                            session.emitError(uid, line.substring(7).trim());
                        } else if (line.startsWith("[REASONING]")) {
                            String payload = line.substring(11);
                            StringBuilder sb = new StringBuilder(payload.length());
                            for (int i = 0; i < payload.length(); i++) {
                                char c = payload.charAt(i);
                                if (c == '\\' && i + 1 < payload.length()) {
                                    char n = payload.charAt(i + 1);
                                    if (n == 'n') { sb.append('\n'); i++; continue; }
                                    if (n == '\\') { sb.append('\\'); i++; continue; }
                                }
                                sb.append(c);
                            }
                            String decoded = sb.toString();
                            closeText.run();
                            appendThinking.accept(decoded);
                            session.emitReasoning(uid, decoded);
                        } else if (isTracebackLine(line)) {
                            // Python traceback leaked to stdout — silently drop it.
                            // Normal errors are emitted via [ERROR] protocol lines.
                        } else {
                            String tok = line.isEmpty() ? "\n" : line;
                            // Answer text closes any open thinking segment, then folds
                            // into the timeline at its true position.
                            closeThinking.run();
                            accumContent.append(tok);
                            appendText.accept(tok);
                            session.emitToken(uid, tok);
                        }
                    }
                }
                boolean finished = p.waitFor(10, TimeUnit.MINUTES);
                if (!finished) { p.destroyForcibly(); session.emitError(uid, "AI 对话超时"); }
                Files.deleteIfExists(tmpInput);

                // Stamp _elapsed on the final thinking segment (if any) so
                // reloaded history doesn't show 0s.
                closeThinking.run();

                // Persist assistant turn (content + structured timeline) once generation completes
                if (uid > 0 && accumContent.length() > 0) {
                    try {
                        String content = accumContent.toString();
                        if (content.length() > 8000) content = content.substring(0, 8000);
                        jdbc.update("INSERT INTO ai_chat_history (user_id, conversation_id, role, content) VALUES (?, ?, 'assistant', ?)",
                            uid, convId, content);
                        // Persist the entire timeline (answer text + thinking + tool steps)
                        // as JSON in the 'thinking' role row, so we can faithfully replay the
                        // interleaved trace on history reload. NEVER substring the JSON — a
                        // truncated blob is invalid JSON and would be dropped on reload. If it
                        // exceeds the safe cap, skip it: the frontend then falls back to
                        // rendering the (separately persisted) answer text.
                        if (!timeline.isEmpty()) {
                            String tlJson = json.writeValueAsString(timeline);
                            if (tlJson.length() <= 16000) {
                                jdbc.update("INSERT INTO ai_chat_history (user_id, conversation_id, role, content) VALUES (?, ?, 'thinking', ?)",
                                    uid, convId, tlJson);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                // Persist this turn's tool-result digest (#1) — keep only the most
                // recent one so the model gets a single, fresh continuity block.
                if (uid > 0 && toolContext[0] != null && !toolContext[0].isEmpty()) {
                    try {
                        jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ? AND role = 'tooldata'", uid);
                        String tc = toolContext[0];
                        if (tc.length() > 4000) tc = tc.substring(0, 4000);
                        jdbc.update("INSERT INTO ai_chat_history (user_id, conversation_id, role, content) VALUES (?, ?, 'tooldata', ?)",
                            uid, convId, tc);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                session.emitError(uid, e.getMessage());
            } finally {
                session.finishSession(uid);
            }
        });

        // Return the (possibly auto-created) conversationId so the client can keep
        // convIdRef in sync — needed so deleting the active conversation clears the
        // chat window even when it was freshly created this turn.
        return Map.of("status", "started", "conversationId", convId);
    }

    @GetMapping("/suggestions")
    public Map<String, Object> suggestions(HttpServletRequest req) {
        if (STATIC_SUGGESTIONS != null) return Map.of("suggestions", STATIC_SUGGESTIONS);
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
                    String savedProvider = row.getOrDefault("provider", DEFAULT_PROVIDER).toString();
                    if (isSupportedProvider(savedProvider)) {
                        aiKey = savedKey;
                        aiProvider = savedProvider;
                        aiModel = row.getOrDefault("model", DEFAULT_MODEL).toString();
                        aiBaseUrl = row.getOrDefault("base_url", DEFAULT_BASE_URL).toString();
                    }
                }
            }
        }

        // Use fast model on DashScope for suggestions
        final String provider = "anthropic".equals(aiProvider) ? "anthropic" : "openai_compat";
        // Rewrite legacy DeepSeek model names (deepseek-chat / deepseek-reasoner are being deprecated)
        String resolvedModel = aiModel;
        if (aiBaseUrl != null && aiBaseUrl.contains("deepseek")) {
            if ("deepseek-chat".equals(resolvedModel)) resolvedModel = "deepseek-v4-flash";
            else if ("deepseek-reasoner".equals(resolvedModel)) resolvedModel = "deepseek-v4-pro";
        }
        final String model = resolvedModel;
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
    public SseEmitter stream(HttpServletRequest req, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        long uid = userIdOf(req);
        if (!session.isActive(uid) && !session.hasReplayEvents(uid)) {
            SseEmitter err = new SseEmitter();
            try { err.send(SseEmitter.event().name("error").data(Map.of("msg", "无活跃对话"))); } catch (IOException ignored) {}
            err.complete();
            return err;
        }
        return session.subscribe(uid);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return session.getStatus(userIdOf(req));
    }

    // ── Multi-conversation management ──────────────────────────────────

    @PostMapping("/conversations")
    public Map<String, Object> createConversation(HttpServletRequest req) {
        long uid = userIdOf(req);
        if (uid <= 0) return Map.of("error", "未登录");
        // Archive current conversation — don't delete, just clear session
        session.clearSession(uid);
        // Clear stale tooldata so it doesn't leak into the new conversation
        try { jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ? AND role = 'tooldata'", uid); } catch (Exception ignored) {}
        // Insert new conversation row; title is summarised async by the LLM after the first turn.
        jdbc.update("INSERT INTO ai_conversations (user_id, title) VALUES (?, '无标题')", uid);
        long convId = 0;
        try { convId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class); } catch (Exception ignored) {}
        return Map.of("id", convId, "title", "无标题");
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> listConversations(HttpServletRequest req) {
        long uid = userIdOf(req);
        if (uid <= 0) return List.of();
        return jdbc.queryForList("""
            SELECT c.id, c.title, c.created_at AS createdAt,
                   (SELECT COUNT(*) FROM ai_chat_history h WHERE h.conversation_id = c.id AND h.role IN ('user','assistant')) AS messageCount
            FROM ai_conversations c WHERE c.user_id = ?
            ORDER BY c.updated_at DESC LIMIT 30
            """, uid);
    }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> getConversation(@PathVariable long id, HttpServletRequest req) {
        long uid = userIdOf(req);
        if (uid <= 0) return Map.of("messages", List.of());
        // Verify ownership
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ai_conversations WHERE id = ? AND user_id = ?", Integer.class, id, uid);
        if (count == null || count == 0) return Map.of("messages", List.of());
        // Load messages for this conversation (reuse history stitching logic)
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT role, content FROM ai_chat_history WHERE user_id = ? AND conversation_id = ? " +
            "AND role IN ('user','assistant','thinking') ORDER BY id ASC LIMIT 300", uid, id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String role = String.valueOf(r.get("role"));
            String content = String.valueOf(r.getOrDefault("content", ""));
            if ("thinking".equals(role) && !out.isEmpty()) {
                Map<String, Object> last = out.get(out.size() - 1);
                if ("assistant".equals(last.get("role"))) {
                    last.put("thinking", content);
                    continue;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", role); m.put("content", content);
            out.add(m);
        }
        attachArtifactsToMessages(uid, id, out);
        return Map.of("messages", out);
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> deleteConversation(@PathVariable long id, HttpServletRequest req) {
        long uid = userIdOf(req);
        if (uid <= 0) return Map.of("error", "未登录");
        jdbc.update("DELETE FROM ai_artifacts WHERE user_id = ? AND conversation_id = ?", uid, id);
        jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ? AND conversation_id = ?", uid, id);
        jdbc.update("DELETE FROM ai_conversations WHERE id = ? AND user_id = ?", id, uid);
        return Map.of("status", "deleted");
    }

    @PostMapping("/clear")
    public Map<String, Object> clear(HttpServletRequest req) {
        long uid = userIdOf(req);
        session.clearSession(uid);
        if (uid > 0) {
            try { jdbc.update("DELETE FROM ai_artifacts WHERE user_id = ?", uid); } catch (Exception ignored) {}
            try { jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ? AND role IN ('user','assistant','thinking','tooldata')", uid); } catch (Exception ignored) {}
        }
        return Map.of("status", "cleared");
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(HttpServletRequest req) {
        long uid = userIdOf(req);
        boolean ok = session.cancel(uid);
        return Map.of("cancelled", ok);
    }

    @GetMapping("/history")
    public Map<String, Object> history(HttpServletRequest req) {
        long uid = userIdOf(req);
        if (uid <= 0) return Map.of("messages", List.of());
        try {
            // Find the most recent conversation for this user
            Long convId = null;
            try {
                convId = jdbc.queryForObject(
                    "SELECT conversation_id FROM ai_chat_history WHERE user_id = ? " +
                    "AND role IN ('user','assistant','thinking') " +
                    "ORDER BY id DESC LIMIT 1", Long.class, uid);
            } catch (Exception ignored) {}

            List<Map<String, Object>> rows;
            if (convId != null && convId > 0) {
                // Load only the latest conversation's messages
                rows = jdbc.queryForList(
                    "SELECT role, content FROM ai_chat_history WHERE user_id = ? " +
                    "AND conversation_id = ? " +
                    "AND role IN ('user','assistant','thinking') ORDER BY id ASC LIMIT 200", uid, convId);
            } else {
                rows = jdbc.queryForList(
                    "SELECT role, content FROM ai_chat_history WHERE user_id = ? " +
                    "AND role IN ('user','assistant','thinking') ORDER BY id ASC LIMIT 200", uid);
            }
            // Stitch thinking onto the preceding assistant turn for the client
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                String role = String.valueOf(r.get("role"));
                String content = String.valueOf(r.getOrDefault("content", ""));
                if ("thinking".equals(role) && !out.isEmpty()) {
                    Map<String, Object> last = out.get(out.size() - 1);
                    if ("assistant".equals(last.get("role"))) {
                        last.put("thinking", content);
                        continue;
                    }
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", role); m.put("content", content);
                out.add(m);
            }
            // Attach artifacts so they survive browser restart
            if (convId != null && convId > 0) {
                attachArtifactsToMessages(uid, convId, out);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("messages", out);
            if (convId != null && convId > 0) result.put("conversationId", convId);
            return result;
        } catch (Exception e) {
            return Map.of("messages", List.of());
        }
    }

    @GetMapping("/morning-greeting")
    public Map<String, Object> morningGreeting(HttpServletRequest req) {
        long uid = userIdOf(req);
        if (uid <= 0) return Map.of("show", false);
        long portfolioId = 0;
        jakarta.servlet.http.HttpSession s = req.getSession(false);
        if (s != null) {
            Object pid = s.getAttribute("portfolioId");
            if (pid instanceof Number) portfolioId = ((Number) pid).longValue();
        }
        if (portfolioId <= 0) return Map.of("show", false);

        try {
            // Today's biggest mover among holdings
            List<Map<String, Object>> movers = jdbc.queryForList(
                "SELECT s.name, s.market, h.total_shares * sp.close AS mv, " +
                "  (sp.close - sp_prev.close) / sp_prev.close * 100 AS chg_pct " +
                "FROM holdings h " +
                "JOIN stocks s ON s.id = h.stock_id " +
                "JOIN stock_prices sp ON sp.stock_id = h.stock_id " +
                "JOIN stock_prices sp_prev ON sp_prev.stock_id = h.stock_id " +
                "WHERE h.portfolio_id = ? AND h.total_shares > 0 " +
                "  AND sp.trade_date = (SELECT MAX(trade_date) FROM stock_prices WHERE stock_id = h.stock_id) " +
                "  AND sp_prev.trade_date = (SELECT MAX(trade_date) FROM stock_prices WHERE stock_id = h.stock_id AND trade_date < sp.trade_date) " +
                "ORDER BY ABS((sp.close - sp_prev.close) / sp_prev.close) DESC LIMIT 1",
                portfolioId);

            // Holding count + a market regime hint (placeholder — pulled from a cached table if available)
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM holdings WHERE portfolio_id = ? AND total_shares > 0",
                Integer.class, portfolioId);
            int n = count != null ? count : 0;

            java.time.ZoneId beijing = java.time.ZoneId.of("Asia/Shanghai");
            int hour = java.time.LocalTime.now(beijing).getHour();
            String greeting = hour >= 5 && hour < 9 ? "早上好"
                : hour >= 9 && hour < 11 ? "上午好"
                : hour >= 11 && hour < 14 ? "中午好"
                : hour >= 14 && hour < 18 ? "下午好"
                : hour >= 18 && hour < 23 ? "晚上好"
                : "欢迎回来";

            StringBuilder msg = new StringBuilder();
            msg.append(greeting).append("。");
            if (n > 0) {
                msg.append("你目前持有 ").append(n).append(" 只标的");
            } else {
                msg.append("你还没有持仓数据");
            }
            if (n > 0 && !movers.isEmpty()) {
                Map<String, Object> top = movers.get(0);
                Number chg = (Number) top.get("chg_pct");
                String name = String.valueOf(top.get("name"));
                if (chg != null) {
                    double c = chg.doubleValue();
                    String dir = c >= 0 ? "+" : "";
                    msg.append("。昨日最大波动是 ").append(name)
                       .append(" ").append(dir).append(String.format("%.1f%%", c));
                }
            }
            msg.append("。可以先看世界市场、检查组合风险，或挑一只持仓做深度分析。");

            return Map.of(
                "show", true,
                "title", "观澜 · " + greeting,
                "message", msg.toString()
            );
        } catch (Exception e) {
            return Map.of("show", false);
        }
    }

    /**
     * Mark a tool timeline step done/failed. Matches by callId first (exact, even
     * for parallel same-name calls), falling back to most-recent-by-name so older
     * agents / any id-less line still resolve. (#3)
     */
    private static void markToolDone(List<Map<String, Object>> timeline, String callId,
                                     String name, boolean done, String summary, String error) {
        Map<String, Object> match = null;
        if (callId != null && !callId.isEmpty()) {
            for (int i = timeline.size() - 1; i >= 0; i--) {
                Map<String, Object> step = timeline.get(i);
                if ("tool".equals(step.get("kind")) && callId.equals(step.get("callId"))) { match = step; break; }
            }
        }
        if (match == null) {
            for (int i = timeline.size() - 1; i >= 0; i--) {
                Map<String, Object> step = timeline.get(i);
                if ("tool".equals(step.get("kind")) && name != null && name.equals(step.get("name"))
                        && !Boolean.TRUE.equals(step.get("done"))) { match = step; break; }
            }
        }
        if (match == null) return;
        if (done) match.put("done", true);
        if (summary != null && !summary.isEmpty()) match.put("summary", summary);
        if (error != null && !error.isEmpty()) match.put("error", error);
    }

    private long userIdOf(HttpServletRequest req) {
        jakarta.servlet.http.HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object uid = s.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    /** Summarise a conversation title via LLM (async, fire-and-forget). */
    private void summarizeTitleAsync(long convId, String userMessage, long userId) {
        if (!aiTitleEnabled || convId <= 0 || userId <= 0) return;
        executor.submit(() -> {
            try {
                File script = new File("script/ai_agent.py");
                if (!script.exists()) script = new File("../script/ai_agent.py").getCanonicalFile();
                if (!script.exists()) return;
                // Build temp input with the user message
                Path tmpInput = Files.createTempFile("ai_title_", ".json");
                Files.writeString(tmpInput, json.writeValueAsString(Map.of("message", userMessage)));
                // Load user's AI settings
                String key = defaultKey, model = DEFAULT_MODEL, base = DEFAULT_BASE_URL, provider = DEFAULT_PROVIDER;
                try {
                    List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT provider, model, base_url, api_key FROM ai_settings WHERE user_id = ?", userId);
                    if (!rows.isEmpty()) {
                        var r = rows.get(0);
                        provider = String.valueOf(r.getOrDefault("provider", DEFAULT_PROVIDER));
                        model = String.valueOf(r.getOrDefault("model", DEFAULT_MODEL));
                        key = String.valueOf(r.getOrDefault("api_key", key));
                        String b = String.valueOf(r.getOrDefault("base_url", ""));
                        if (!b.isBlank()) base = b;
                    }
                } catch (Exception ignored) {}
                if (key == null || key.isBlank()) {
                    Files.deleteIfExists(tmpInput);
                    return;
                }
                try {
                    String currentTitle = jdbc.queryForObject(
                        "SELECT title FROM ai_conversations WHERE id = ? AND user_id = ?",
                        String.class, convId, userId);
                    if (currentTitle != null && !currentTitle.isBlank()
                            && !"\u65e0\u6807\u9898".equals(currentTitle)) {
                        Files.deleteIfExists(tmpInput);
                        return;
                    }
                } catch (Exception ignored) {}
                ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u",
                    script.getAbsolutePath(), "--mode", "title", "--api-key", key,
                    "--model", model, "--provider", provider, "--api-base", base,
                    "--input", tmpInput.toString());
                pb.directory(script.getParentFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String title = new String(p.getInputStream().readAllBytes(), "UTF-8").trim();
                p.waitFor(15, TimeUnit.SECONDS);
                Files.deleteIfExists(tmpInput);
                if (!title.isEmpty() && !"\u65e0\u6807\u9898".equals(title)) {
                    jdbc.update("UPDATE ai_conversations SET title = ? WHERE id = ?", title, convId);
                }
            } catch (Exception ignored) {}
        });
    }

    private long persistArtifact(long userId, long conversationId, Map<String, Object> artifact) {
        if (userId <= 0 || artifact == null || artifact.isEmpty()) return 0;
        try {
            String type = String.valueOf(artifact.getOrDefault("type", "stocksage_report"));
            String title = String.valueOf(artifact.getOrDefault("title", "StockSage 分析报告"));
            String summary = String.valueOf(artifact.getOrDefault("summary", ""));
            Object contentJson = artifact.get("content_json");
            String contentJsonStr = contentJson == null ? json.writeValueAsString(artifact) : json.writeValueAsString(contentJson);
            String contentMarkdown = String.valueOf(artifact.getOrDefault("content_markdown", ""));
            Object cid = conversationId > 0 ? conversationId : null;
            jdbc.update("""
                INSERT INTO ai_artifacts
                    (user_id, conversation_id, type, title, summary, content_json, content_markdown)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, cid, type, title, summary, contentJsonStr, contentMarkdown);
            Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            return id == null ? 0 : id;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void attachArtifactsToMessages(long userId, long conversationId, List<Map<String, Object>> messages) {
        if (userId <= 0 || conversationId <= 0 || messages == null || messages.isEmpty()) return;
        try {
            List<Map<String, Object>> artifacts = jdbc.queryForList("""
                SELECT id, type, title, summary,
                       content_json AS contentJson,
                       content_markdown AS contentMarkdown,
                       created_at AS createdAt
                FROM ai_artifacts
                WHERE user_id = ? AND conversation_id = ?
                ORDER BY id ASC
                """, userId, conversationId);
            if (artifacts.isEmpty()) return;
            Map<String, Object> target = null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = messages.get(i);
                if ("assistant".equals(msg.get("role"))) {
                    target = msg;
                    break;
                }
            }
            if (target != null) target.put("artifacts", artifacts);
        } catch (Exception ignored) {}
    }

    private String buildPortfolioHint(long portfolioId) {
        try {
            // Pull all holdings (not just top 5) so we can compute concentration + style signals
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.name, s.market, h.total_shares, h.avg_cost, " +
                "  (SELECT sp.close FROM stock_prices sp WHERE sp.stock_id = h.stock_id ORDER BY sp.trade_date DESC LIMIT 1) AS price " +
                "FROM holdings h JOIN stocks s ON s.id = h.stock_id " +
                "WHERE h.portfolio_id = ? AND h.total_shares > 0 ORDER BY (h.total_shares * h.avg_cost) DESC",
                portfolioId);
            if (rows.isEmpty()) return "";

            // ── Compute objective profile signals ──────────────────────────
            int holdingCount = rows.size();
            double totalMv = 0;
            double[] mvs = new double[holdingCount];
            for (int i = 0; i < holdingCount; i++) {
                Map<String, Object> r = rows.get(i);
                Number price = (Number) r.get("price");
                Number shares = (Number) r.get("total_shares");
                Number avgCost = (Number) r.get("avg_cost");
                double p = price != null ? price.doubleValue() : (avgCost != null ? avgCost.doubleValue() : 0);
                double s = shares != null ? shares.doubleValue() : 0;
                mvs[i] = Math.max(p * s, 0);
                totalMv += mvs[i];
            }

            // Herfindahl-Hirschman Index on weights (0..1, higher = more concentrated)
            double hhi = 0, maxWeight = 0;
            if (totalMv > 0) {
                for (double mv : mvs) {
                    double w = mv / totalMv;
                    hhi += w * w;
                    if (w > maxWeight) maxWeight = w;
                }
            }
            String concentration = holdingCount <= 3 || hhi > 0.4 ? "高度集中"
                : hhi > 0.2 ? "中等集中" : "分散";

            // Market exposure mix (rough style proxy: HK/US count as growth-leaning, A-share = mixed)
            Map<String, Double> marketMv = new LinkedHashMap<>();
            for (int i = 0; i < holdingCount; i++) {
                String mkt = String.valueOf(rows.get(i).get("market"));
                marketMv.merge(mkt, mvs[i], Double::sum);
            }
            StringBuilder marketMix = new StringBuilder();
            for (Map.Entry<String, Double> e : marketMv.entrySet()) {
                if (totalMv > 0 && e.getValue() / totalMv >= 0.05) {
                    marketMix.append(e.getKey()).append(String.format(" %.0f%%", e.getValue() / totalMv * 100)).append(" ");
                }
            }

            // Trading activity: # of buy/sell transactions in the last 90 days
            Integer recentTxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE portfolio_id = ? " +
                "AND type IN ('BUY','SELL') AND trade_date >= CURRENT_DATE - INTERVAL 90 DAY",
                Integer.class, portfolioId);
            int tx90 = recentTxCount != null ? recentTxCount : 0;
            String activity = tx90 >= 20 ? "高频交易"
                : tx90 >= 6 ? "中等频率"
                : tx90 >= 1 ? "低频/长线" : "近期无交易";

            // ── Build context block ────────────────────────────────────────
            StringBuilder sb = new StringBuilder();
            sb.append("【用户持仓画像（客观信号，不要照本宣科念给用户，作为分析背景使用）】\n");
            sb.append("持仓数=").append(holdingCount)
              .append("，集中度=").append(concentration)
              .append(String.format("(HHI=%.2f, 最大单股权重=%.0f%%)", hhi, maxWeight * 100))
              .append("，市场分布=").append(marketMix.toString().trim())
              .append("，近90天交易活跃度=").append(activity).append("(共").append(tx90).append("笔)。\n");

            sb.append("前5大持仓：");
            int shown = 0;
            for (Map<String, Object> r : rows) {
                if (shown >= 5) break;
                Number price = (Number) r.get("price");
                Number avgCost = (Number) r.get("avg_cost");
                Number shares = (Number) r.get("total_shares");
                if (price == null || avgCost == null || shares == null) continue;
                double pnlPct = avgCost.doubleValue() > 0
                    ? (price.doubleValue() - avgCost.doubleValue()) / avgCost.doubleValue() * 100 : 0;
                sb.append(r.get("name")).append("(").append(r.get("market")).append(")")
                  .append(pnlPct >= 0 ? " +" : " ").append(String.format("%.1f%%", pnlPct)).append("；");
                shown++;
            }
            sb.append("\n如需完整持仓数据或量化分析，请调用 get_portfolio / get_portfolio_report 等工具。");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Detect Python traceback lines that would pollute the chat UI.
     *  Matches patterns like "Traceback (most recent call last):",
     *  "  File \"/path/to/file.py\", line 42, in foo", error indicator
     *  arrows, and exception class names. */
    private static boolean isTracebackLine(String line) {
        if (line == null) return false;
        String t = line.strip();
        if (t.isEmpty()) return false;
        // Traceback header
        if (t.startsWith("Traceback (most recent call last):")) return true;
        // File references: "  File \"...\", line N, in ..."
        if (t.startsWith("File \"") && t.contains("line ")) return true;
        // Exception class names (may be chained): module.ExceptionName: message
        if (t.matches("^[\\w.]+(Error|Exception|Timeout|Warning)(:.*)?$")) return true;
        // Chained exception marker
        if (t.startsWith("The above exception was the direct cause")) return true;
        // Error indicator arrows: ^^^^ or ^~~~
        if (t.matches("^\\^+~*$") && t.length() >= 2) return true;
        // httpcore / httpx internal module tracebacks (sometimes printed without "File " prefix)
        if (t.matches("^\\s+File \".*\", line \\d+.*")) return true;
        return false;
    }
}
