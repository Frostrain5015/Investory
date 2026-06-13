package com.investory.controller.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.investory.crawler.AiSessionManager;
import com.investory.server.AppContext;
import com.investory.server.ConfigLoader;
import com.investory.server.DatabaseManager;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class AiApiController {

    private static final Logger log = Logger.getLogger(AiApiController.class.getName());
    private static final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AiSessionManager session = AppContext.get(AiSessionManager.class);

    private final String pythonExecutable = ConfigLoader.get("python.executable", "python3");
    private final String defaultKey = ConfigLoader.get("ai.default.key", "");
    private final boolean aiTitleEnabled = "true".equals(ConfigLoader.get("ai.title.enabled", "false"));

    private static final String DEFAULT_PROVIDER = "openai_compat";
    private static final String DEFAULT_MODEL   = "deepseek-v4-flash";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final List<String> STATIC_SUGGESTIONS = List.of(
        "我的组合风险怎么样？", "分析一下我的持仓风格", "帮我写一个均线策略");

    private boolean isSupportedProvider(String provider) {
        return DEFAULT_PROVIDER.equals(provider) || "anthropic".equals(provider);
    }

    private long userIdOf(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object uid = s.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    public void handleAnswer(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long uid = userIdOf(req);
        String jsonBody = new String(req.getReader().readAllBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = gson.fromJson(jsonBody, Map.class);
        String answer = body.get("answer") instanceof String ? (String) body.get("answer") : "";
        if (answer.isBlank()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "answer required")));
            return;
        }
        boolean ok = session.writeAnswer(uid, answer);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("ok", ok)));
    }

    public void handleChat(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HttpSession s = req.getSession(false);
        long userId = 0;
        if (s != null) {
            Object uid = s.getAttribute("userId");
            if (uid instanceof Number) userId = ((Number) uid).longValue();
        }
        if (userId <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not authenticated")));
            return;
        }

        String jsonBody = new String(req.getReader().readAllBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = gson.fromJson(jsonBody, Map.class);

        String aiKey = defaultKey;
        String aiProvider = DEFAULT_PROVIDER;
        String aiModel = DEFAULT_MODEL;
        String aiBaseUrl = DEFAULT_BASE_URL;

        if (userId > 0) {
            List<Map<String, Object>> rows = jdbcQueryForList(
                "SELECT provider, model, base_url, api_key FROM ai_settings WHERE user_id = ?", userId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                String savedKey = (String) row.get("api_key");
                if (savedKey != null && !savedKey.isBlank()) {
                    String savedProvider = row.getOrDefault("provider", DEFAULT_PROVIDER).toString();
                    if (!isSupportedProvider(savedProvider)) {
                        resp.setContentType("application/json;charset=UTF-8");
                        resp.getWriter().write(JsonUtil.toJson(Map.of("error", "请先在设置页重新选择 API 格式")));
                        return;
                    }
                    aiKey = savedKey;
                    aiProvider = savedProvider;
                    aiModel = row.getOrDefault("model", DEFAULT_MODEL).toString();
                    aiBaseUrl = row.getOrDefault("base_url", DEFAULT_BASE_URL).toString();
                }
            }
        }
        if (aiKey == null || aiKey.isBlank()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "AI API key not configured")));
            return;
        }

        final String provider = "anthropic".equals(aiProvider) ? "anthropic" : "openai_compat";
        final String key = aiKey;
        final String baseUrl = aiBaseUrl;
        final boolean deepThink = Boolean.TRUE.equals(body.get("deepThink"));

        String resolvedModel = aiModel;
        boolean isDeepseek = baseUrl != null && baseUrl.contains("deepseek");
        if (isDeepseek) {
            if ("deepseek-chat".equals(resolvedModel)) resolvedModel = "deepseek-v4-flash";
            else if ("deepseek-reasoner".equals(resolvedModel)) resolvedModel = "deepseek-v4-flash";
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
        if (rawMessages == null || rawMessages.isEmpty()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "messages required")));
            return;
        }

        final boolean webSearch = Boolean.TRUE.equals(body.get("webSearch"));

        long conversationId = 0;
        Object cidRaw = body.get("conversationId");
        if (cidRaw instanceof Number) conversationId = ((Number) cidRaw).longValue();
        if (conversationId <= 0 && userId > 0) {
            try {
                jdbcUpdate("INSERT INTO ai_conversations (user_id, title) VALUES (?, '无标题')", userId);
                conversationId = jdbcGetLastInsertId();
            } catch (Exception ignored) {}
        }
        final long convId = conversationId;

        final int MAX_HISTORY = 24;
        List<Map<String, Object>> windowed = rawMessages.size() > MAX_HISTORY
            ? new ArrayList<>(rawMessages.subList(rawMessages.size() - MAX_HISTORY, rawMessages.size()))
            : rawMessages;

        List<Map<String, Object>> systemBlocks = new ArrayList<>();
        if (portfolioId > 0) {
            String ctx = buildPortfolioHint(portfolioId);
            if (!ctx.isEmpty()) systemBlocks.add(Map.of("role", "system", "content", ctx));
        }
        if (userId > 0) {
            try {
                List<Map<String, Object>> td = jdbcQueryForList(
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

        if (userId > 0 && !rawMessages.isEmpty()) {
            Map<String, Object> lastMsg = rawMessages.get(rawMessages.size() - 1);
            if ("user".equals(lastMsg.get("role"))) {
                String content = String.valueOf(lastMsg.getOrDefault("content", ""));
                try {
                    jdbcUpdate("INSERT INTO ai_chat_history (user_id, conversation_id, role, content) VALUES (?, ?, 'user', ?)",
                        userId, convId, content.length() > 4000 ? content.substring(0, 4000) : content);
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
                if (!script.exists()) script = new File("../script/ai_agent.py").getCanonicalFile();
                if (!script.exists()) {
                    session.emitError(uid, "AI 引擎脚本未找到");
                    session.finishSession(uid);
                    return;
                }
                File scriptDir = script.getParentFile();
                Path tmpInput = Files.createTempFile("ai_input_", ".json");
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("messages", messages);
                Files.writeString(tmpInput, gson.toJson(input));

                List<String> cmd = new ArrayList<>();
                cmd.add(pythonExecutable); cmd.add("-u"); cmd.add(script.getAbsolutePath());
                cmd.add("--provider"); cmd.add(provider);
                cmd.add("--model"); cmd.add(model);
                cmd.add("--portfolio-id"); cmd.add(String.valueOf(pid));
                cmd.add("--user-id"); cmd.add(String.valueOf(uid));
                cmd.add("--input"); cmd.add(tmpInput.toString());
                if (deepThink) cmd.add("--deep-think");
                if (webSearch) cmd.add("--web-search");
                if (!baseUrl.isBlank()) { cmd.add("--api-base"); cmd.add(baseUrl); }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(scriptDir);
                pb.redirectErrorStream(true);
                pb.environment().put("PYTHONUNBUFFERED", "1");
                pb.environment().put("AI_API_KEY", key);

                Process p = pb.start();
                session.bindProcess(uid, p);
                StringBuilder accumContent = new StringBuilder();
                List<Map<String, Object>> timeline = new ArrayList<>();
                final String[] toolContext = { null };
                final List<Map<String, Object>> pendingArtifacts = new ArrayList<>();
                final Map<String, Object>[] openThinking = new Map[]{null};
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
                        step.put("_ts", System.currentTimeMillis());
                        timeline.add(step);
                        openThinking[0] = step;
                    }
                    openThinking[0].put("text", openThinking[0].get("text").toString() + chunk);
                };
                final Map<String, Object>[] openText = new Map[]{null};
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
                            try { String jsonStr = line.substring(5).trim(); @SuppressWarnings("unchecked") Map<String, Object> askData = gson.fromJson(jsonStr, Map.class); session.emitAsk(uid, askData); } catch (Exception ignored) {}
                        } else if (line.startsWith("[STRATEGY]")) {
                            try { String jsonStr = line.substring(10).trim(); @SuppressWarnings("unchecked") Map<String, Object> sdata = gson.fromJson(jsonStr, Map.class); session.emitStrategy(uid, sdata); } catch (Exception ignored) {}
                        } else if (line.startsWith("[TOOL_END]")) {
                            String[] parts = line.substring(10).trim().split("\t", 3);
                            String callId = parts.length > 0 ? parts[0] : "";
                            String name = parts.length > 1 ? parts[1] : "";
                            String summary = parts.length > 2 ? parts[2].trim() : "";
                            markToolDone(timeline, callId, name, true, summary, null);
                            session.emitToolEnd(uid, callId, name, summary);
                        } else if (line.startsWith("[TOOL_FAIL]")) {
                            String[] parts = line.substring(11).trim().split("\t", 3);
                            String callId = parts.length > 0 ? parts[0] : "";
                            String name = parts.length > 1 ? parts[1] : "";
                            String errMsg = parts.length > 2 ? parts[2] : "";
                            markToolDone(timeline, callId, name, true, null, errMsg);
                            session.emitToolFail(uid, callId, name, errMsg);
                        } else if (line.startsWith("[KB]")) {
                            String topic = line.substring(4).trim();
                            closeThinking.run(); closeText.run();
                            Map<String, Object> kbStep = new LinkedHashMap<>();
                            kbStep.put("kind", "kb"); kbStep.put("topic", topic);
                            timeline.add(kbStep);
                            session.emitKb(uid, topic);
                        } else if (line.startsWith("[MEMORY]")) {
                            String cnt = line.substring(8).trim();
                            closeThinking.run(); closeText.run();
                            Map<String, Object> memStep = new LinkedHashMap<>();
                            memStep.put("kind", "memory"); memStep.put("count", cnt);
                            timeline.add(memStep);
                            session.emitMemory(uid, cnt);
                        } else if (line.startsWith("[TOOL]")) {
                            String[] parts = line.substring(6).trim().split("\t", 3);
                            String name = parts.length > 0 ? parts[0] : "";
                            String category = parts.length > 1 ? parts[1].trim() : "query";
                            String callId = parts.length > 2 ? parts[2].trim() : "";
                            closeThinking.run(); closeText.run();
                            Map<String, Object> step = new LinkedHashMap<>();
                            step.put("kind", "tool"); step.put("name", name);
                            step.put("category", category); step.put("done", false);
                            if (!callId.isEmpty()) step.put("callId", callId);
                            timeline.add(step);
                            session.emitTool(uid, name, category, callId);
                        } else if (line.startsWith("[ARTIFACT]")) {
                            try { String jsonStr = line.substring(10).trim(); @SuppressWarnings("unchecked") Map<String, Object> artifact = gson.fromJson(jsonStr, Map.class); long artifactId = persistArtifact(uid, convId, artifact); if (artifactId > 0) artifact.put("id", artifactId); pendingArtifacts.add(artifact); session.emitArtifact(uid, artifact); } catch (Exception ignored) {}
                        } else if (line.startsWith("[CONTEXT]")) {
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
                        } else if (!isTracebackLine(line)) {
                            String tok = line.isEmpty() ? "\n" : line;
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
                closeThinking.run();
                if (uid > 0 && accumContent.length() > 0) {
                    try {
                        String content = accumContent.toString();
                        if (content.length() > 8000) content = content.substring(0, 8000);
                        jdbcUpdate("INSERT INTO ai_chat_history (user_id, conversation_id, role, content) VALUES (?, ?, 'assistant', ?)",
                            uid, convId, content);
                        if (!timeline.isEmpty()) {
                            String tlJson = gson.toJson(timeline);
                            if (tlJson.length() <= 16000) {
                                jdbcUpdate("INSERT INTO ai_chat_history (user_id, conversation_id, role, content) VALUES (?, ?, 'thinking', ?)",
                                    uid, convId, tlJson);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (uid > 0 && toolContext[0] != null && !toolContext[0].isEmpty()) {
                    try {
                        jdbcUpdate("DELETE FROM ai_chat_history WHERE user_id = ? AND role = 'tooldata'", uid);
                        String tc = toolContext[0];
                        if (tc.length() > 4000) tc = tc.substring(0, 4000);
                        jdbcUpdate("INSERT INTO ai_chat_history (user_id, conversation_id, role, content) VALUES (?, ?, 'tooldata', ?)",
                            uid, convId, tc);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                session.emitError(uid, e.getMessage());
            } finally {
                session.finishSession(uid);
            }
        });

        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "started", "conversationId", convId)));
    }

    public void handleSuggestions(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (STATIC_SUGGESTIONS != null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("suggestions", STATIC_SUGGESTIONS)));
            return;
        }
        HttpSession s = req.getSession(false);
        long userId = s != null && s.getAttribute("userId") instanceof Number
            ? ((Number) s.getAttribute("userId")).longValue() : 0;

        String aiKey = defaultKey, aiProvider = DEFAULT_PROVIDER, aiModel = DEFAULT_MODEL, aiBaseUrl = DEFAULT_BASE_URL;
        if (userId > 0) {
            List<Map<String, Object>> rows = jdbcQueryForList(
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
        // Fall back to static suggestions
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("suggestions", STATIC_SUGGESTIONS)));
    }

    public void handleStream(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setHeader("X-Accel-Buffering", "no");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        long uid = userIdOf(req);

        if (!session.isActive(uid) && !session.hasReplayEvents(uid)) {
            var writer = resp.getWriter();
            writer.write("event: error\ndata: {\"msg\":\"无活跃对话\"}\n\n");
            writer.flush();
            return;
        }

        // Subscribe using SseClient
        var sseClient = new com.investory.server.SseClient(resp);
        sseClient.init();
        session.subscribe(uid, sseClient);
    }

    public void handleStatus(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> result = session.getStatus(userIdOf(req));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleCreateConversation(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long uid = userIdOf(req);
        if (uid <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "未登录")));
            return;
        }
        session.clearSession(uid);
        try { jdbcUpdate("DELETE FROM ai_chat_history WHERE user_id = ? AND role = 'tooldata'", uid); } catch (Exception ignored) {}
        jdbcUpdate("INSERT INTO ai_conversations (user_id, title) VALUES (?, '无标题')", uid);
        long convId = jdbcGetLastInsertId();
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("id", convId, "title", "无标题")));
    }

    public void handleListConversations(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long uid = userIdOf(req);
        if (uid <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        List<Map<String, Object>> result = jdbcQueryForList("""
            SELECT c.id, c.title, c.created_at AS createdAt,
                   (SELECT COUNT(*) FROM ai_chat_history h WHERE h.conversation_id = c.id AND h.role IN ('user','assistant')) AS messageCount
            FROM ai_conversations c WHERE c.user_id = ?
            ORDER BY c.updated_at DESC LIMIT 30
            """, uid);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleGetConversation(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long uid = userIdOf(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        if (uid <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("messages", List.of())));
            return;
        }
        Integer count = jdbcQueryForObject("SELECT COUNT(*) FROM ai_conversations WHERE id = ? AND user_id = ?", Integer.class, id, uid);
        if (count == null || count == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("messages", List.of())));
            return;
        }
        List<Map<String, Object>> rows = jdbcQueryForList(
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
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("messages", out)));
    }

    public void handleDeleteConversation(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long uid = userIdOf(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        if (uid <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "未登录")));
            return;
        }
        jdbcUpdate("DELETE FROM ai_artifacts WHERE user_id = ? AND conversation_id = ?", uid, id);
        jdbcUpdate("DELETE FROM ai_chat_history WHERE user_id = ? AND conversation_id = ?", uid, id);
        jdbcUpdate("DELETE FROM ai_conversations WHERE id = ? AND user_id = ?", id, uid);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "deleted")));
    }

    public void handleClear(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long uid = userIdOf(req);
        if (uid <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("status", "not_logged_in")));
            return;
        }
        Long convId = null;
        try {
            convId = jdbcQueryForObject(
                "SELECT conversation_id FROM ai_chat_history WHERE user_id = ? " +
                "AND role IN ('user','assistant','thinking') ORDER BY id DESC LIMIT 1", Long.class, uid);
        } catch (Exception ignored) {}
        session.clearSession(uid);
        if (convId != null && convId > 0) {
            try { jdbcUpdate("DELETE FROM ai_artifacts WHERE user_id = ? AND conversation_id = ?", uid, convId); } catch (Exception ignored) {}
            try { jdbcUpdate("DELETE FROM ai_chat_history WHERE user_id = ? AND conversation_id = ?", uid, convId); } catch (Exception ignored) {}
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "cleared", "conversationId", convId)));
    }

    public void handleCancel(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long uid = userIdOf(req);
        boolean ok = session.cancel(uid);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("cancelled", ok)));
    }

    public void handleHistory(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long uid = userIdOf(req);
        if (uid <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("messages", List.of())));
            return;
        }
        try {
            Long convId = null;
            try {
                convId = jdbcQueryForObject(
                    "SELECT conversation_id FROM ai_chat_history WHERE user_id = ? " +
                    "AND role IN ('user','assistant','thinking') ORDER BY id DESC LIMIT 1", Long.class, uid);
            } catch (Exception ignored) {}
            List<Map<String, Object>> rows;
            if (convId != null && convId > 0) {
                rows = jdbcQueryForList(
                    "SELECT role, content FROM ai_chat_history WHERE user_id = ? " +
                    "AND conversation_id = ? AND role IN ('user','assistant','thinking') ORDER BY id ASC LIMIT 200", uid, convId);
            } else {
                rows = jdbcQueryForList(
                    "SELECT role, content FROM ai_chat_history WHERE user_id = ? " +
                    "AND role IN ('user','assistant','thinking') ORDER BY id ASC LIMIT 200", uid);
            }
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
            if (convId != null && convId > 0) attachArtifactsToMessages(uid, convId, out);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("messages", out);
            if (convId != null && convId > 0) result.put("conversationId", convId);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(result));
        } catch (Exception e) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("messages", List.of())));
        }
    }

    public void handleMorningGreeting(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long uid = userIdOf(req);
        if (uid <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("show", false)));
            return;
        }
        long portfolioId = 0;
        HttpSession s = req.getSession(false);
        if (s != null) {
            Object pid = s.getAttribute("portfolioId");
            if (pid instanceof Number) portfolioId = ((Number) pid).longValue();
        }
        if (portfolioId <= 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("show", false)));
            return;
        }
        try {
            List<Map<String, Object>> movers = jdbcQueryForList(
                "SELECT s.name, s.market, h.total_shares * sp.close AS mv, " +
                "  (sp.close - sp_prev.close) / sp_prev.close * 100 AS chg_pct " +
                "FROM holdings h JOIN stocks s ON s.id = h.stock_id " +
                "JOIN stock_prices sp ON sp.stock_id = h.stock_id " +
                "JOIN stock_prices sp_prev ON sp_prev.stock_id = h.stock_id " +
                "WHERE h.portfolio_id = ? AND h.total_shares > 0 " +
                "  AND sp.trade_date = (SELECT MAX(trade_date) FROM stock_prices WHERE stock_id = h.stock_id) " +
                "  AND sp_prev.trade_date = (SELECT MAX(trade_date) FROM stock_prices WHERE stock_id = h.stock_id AND trade_date < sp.trade_date) " +
                "ORDER BY ABS((sp.close - sp_prev.close) / sp_prev.close) DESC LIMIT 1", portfolioId);
            Integer count = jdbcQueryForObject(
                "SELECT COUNT(*) FROM holdings WHERE portfolio_id = ? AND total_shares > 0", Integer.class, portfolioId);
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
            if (n > 0) msg.append("你目前持有 ").append(n).append(" 只标的");
            else msg.append("你还没有持仓数据");
            if (n > 0 && !movers.isEmpty()) {
                Map<String, Object> top = movers.get(0);
                Number chg = (Number) top.get("chg_pct");
                String name = String.valueOf(top.get("name"));
                if (chg != null) {
                    double c = chg.doubleValue();
                    String dir = c >= 0 ? "+" : "";
                    msg.append("。昨日最大波动是 ").append(name).append(" ").append(dir).append(String.format("%.1f%%", c));
                }
            }
            msg.append("。可以先看世界市场、检查组合风险，或挑一只持仓做深度分析。");
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("show", true, "title", "观澜 · " + greeting, "message", msg.toString())));
        } catch (Exception e) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("show", false)));
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

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

    private void summarizeTitleAsync(long convId, String userMessage, long userId) {
        if (!aiTitleEnabled || convId <= 0 || userId <= 0) return;
        executor.submit(() -> {
            try {
                File script = new File("script/ai_agent.py");
                if (!script.exists()) script = new File("../script/ai_agent.py").getCanonicalFile();
                if (!script.exists()) return;
                Path tmpInput = Files.createTempFile("ai_title_", ".json");
                Files.writeString(tmpInput, gson.toJson(Map.of("message", userMessage)));
                String key = defaultKey, model = DEFAULT_MODEL, base = DEFAULT_BASE_URL, provider = DEFAULT_PROVIDER;
                try {
                    List<Map<String, Object>> rows = jdbcQueryForList(
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
                if (key == null || key.isBlank()) { Files.deleteIfExists(tmpInput); return; }
                try {
                    String currentTitle = jdbcQueryForObject(
                        "SELECT title FROM ai_conversations WHERE id = ? AND user_id = ?", String.class, convId, userId);
                    if (currentTitle != null && !currentTitle.isBlank() && !"无标题".equals(currentTitle)) {
                        Files.deleteIfExists(tmpInput); return;
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
                if (!title.isEmpty() && !"无标题".equals(title)) {
                    jdbcUpdate("UPDATE ai_conversations SET title = ? WHERE id = ?", title, convId);
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
            String contentJsonStr = contentJson == null ? gson.toJson(artifact) : gson.toJson(contentJson);
            String contentMarkdown = String.valueOf(artifact.getOrDefault("content_markdown", ""));
            Object cid = conversationId > 0 ? conversationId : null;
            jdbcUpdate("""
                INSERT INTO ai_artifacts (user_id, conversation_id, type, title, summary, content_json, content_markdown)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, cid, type, title, summary, contentJsonStr, contentMarkdown);
            return jdbcGetLastInsertId();
        } catch (Exception ignored) { return 0; }
    }

    private void attachArtifactsToMessages(long userId, long conversationId, List<Map<String, Object>> messages) {
        if (userId <= 0 || conversationId <= 0 || messages == null || messages.isEmpty()) return;
        try {
            List<Map<String, Object>> artifacts = jdbcQueryForList("""
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
                if ("assistant".equals(msg.get("role"))) { target = msg; break; }
            }
            if (target != null) target.put("artifacts", artifacts);
        } catch (Exception ignored) {}
    }

    private String buildPortfolioHint(long portfolioId) {
        try {
            List<Map<String, Object>> rows = jdbcQueryForList(
                "SELECT s.name, s.market, h.total_shares, h.avg_cost, " +
                "  (SELECT sp.close FROM stock_prices sp WHERE sp.stock_id = h.stock_id ORDER BY sp.trade_date DESC LIMIT 1) AS price " +
                "FROM holdings h JOIN stocks s ON s.id = h.stock_id " +
                "WHERE h.portfolio_id = ? AND h.total_shares > 0 ORDER BY (h.total_shares * h.avg_cost) DESC", portfolioId);
            if (rows.isEmpty()) return "";
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
            double hhi = 0, maxWeight = 0;
            if (totalMv > 0) {
                for (double mv : mvs) {
                    double w = mv / totalMv; hhi += w * w; if (w > maxWeight) maxWeight = w;
                }
            }
            String concentration = holdingCount <= 3 || hhi > 0.4 ? "高度集中" : hhi > 0.2 ? "中等集中" : "分散";
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
            Integer recentTxCount = jdbcQueryForObject(
                "SELECT COUNT(*) FROM transactions WHERE portfolio_id = ? AND type IN ('BUY','SELL') AND trade_date >= CURRENT_DATE - INTERVAL 90 DAY",
                Integer.class, portfolioId);
            int tx90 = recentTxCount != null ? recentTxCount : 0;
            String activity = tx90 >= 20 ? "高频交易" : tx90 >= 6 ? "中等频率" : tx90 >= 1 ? "低频/长线" : "近期无交易";
            StringBuilder sb = new StringBuilder();
            sb.append("【用户持仓画像（客观信号，不要照本宣科念给用户，作为分析背景使用）】\n");
            sb.append("持仓数=").append(holdingCount).append("，集中度=").append(concentration)
              .append(String.format("(HHI=%.2f, 最大单股权重=%.0f%%)", hhi, maxWeight * 100))
              .append("，市场分布=").append(marketMix.toString().trim()).append("，近90天交易活跃度=").append(activity).append("(共").append(tx90).append("笔)。\n");
            sb.append("前5大持仓：");
            int shown = 0;
            for (Map<String, Object> r : rows) {
                if (shown >= 5) break;
                Number price = (Number) r.get("price");
                Number avgCost = (Number) r.get("avg_cost");
                Number shares = (Number) r.get("total_shares");
                if (price == null || avgCost == null || shares == null) continue;
                double pnlPct = avgCost.doubleValue() > 0 ? (price.doubleValue() - avgCost.doubleValue()) / avgCost.doubleValue() * 100 : 0;
                sb.append(r.get("name")).append("(").append(r.get("market")).append(")")
                  .append(pnlPct >= 0 ? " +" : " ").append(String.format("%.1f%%", pnlPct)).append("；");
                shown++;
            }
            sb.append("\n如需完整持仓数据或量化分析，请调用 get_portfolio / get_portfolio_report 等工具。");
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static boolean isTracebackLine(String line) {
        if (line == null) return false;
        String t = line.strip();
        if (t.isEmpty()) return false;
        if (t.startsWith("Traceback (most recent call last):")) return true;
        if (t.startsWith("File \"") && t.contains("line ")) return true;
        if (t.matches("^[\\w.]+(Error|Exception|Timeout|Warning)(:.*)?$")) return true;
        if (t.startsWith("The above exception was the direct cause")) return true;
        if (t.matches("^\\^+~*$") && t.length() >= 2) return true;
        if (t.matches("^\\s+File \".*\", line \\d+.*")) return true;
        return false;
    }

    // ── JDBC helpers ─────────────────────────────────────────────────────

    private List<Map<String, Object>> jdbcQueryForList(String sql, Object... args) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= colCount; c++) row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    result.add(row);
                }
            }
        }
        return result;
    }

    private <T> T jdbcQueryForObject(String sql, Class<T> clazz, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return (T) rs.getObject(1);
            }
        }
        return null;
    }

    private int jdbcUpdate(String sql, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }

    private long jdbcGetLastInsertId() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT LAST_INSERT_ID()");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getLong(1);
        }
        return 0;
    }
}
