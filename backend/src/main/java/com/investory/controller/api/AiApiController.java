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
    private static final String DEFAULT_MODEL   = "qwen-plus-latest";
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

        // Inject fresh portfolio profile on every turn — holdings/profile may have changed
        // since last message. DashScope's ephemeral cache dedupes identical system blocks
        // so re-injection costs nothing when nothing has changed.
        final List<Map<String, Object>> messages;
        if (portfolioId > 0) {
            String ctx = buildPortfolioHint(portfolioId);
            if (!ctx.isEmpty()) {
                List<Map<String, Object>> withCtx = new ArrayList<>(rawMessages.size() + 1);
                Map<String, Object> sysMsg = new LinkedHashMap<>();
                sysMsg.put("role", "system"); sysMsg.put("content", ctx);
                withCtx.add(sysMsg); withCtx.addAll(rawMessages);
                messages = withCtx;
            } else { messages = rawMessages; }
        } else { messages = rawMessages; }

        // Persist the user message (last in the list) before we kick off generation
        if (userId > 0 && !rawMessages.isEmpty()) {
            Map<String, Object> lastMsg = rawMessages.get(rawMessages.size() - 1);
            if ("user".equals(lastMsg.get("role"))) {
                String content = String.valueOf(lastMsg.getOrDefault("content", ""));
                try {
                    jdbc.update("INSERT INTO ai_chat_history (user_id, role, content) VALUES (?, 'user', ?)",
                        userId, content.length() > 4000 ? content.substring(0, 4000) : content);
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
                    session.clearSession(uid);
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
                // Pointer to the current open thinking step (so we can keep appending to it).
                final Map<String, Object>[] openThinking = new Map[]{ null };
                java.util.function.Consumer<String> appendThinking = (chunk) -> {
                    if (openThinking[0] == null) {
                        Map<String, Object> step = new LinkedHashMap<>();
                        step.put("kind", "thinking"); step.put("text", "");
                        timeline.add(step);
                        openThinking[0] = step;
                    }
                    openThinking[0].put("text", openThinking[0].get("text").toString() + chunk);
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
                            String name = line.substring(10).trim();
                            // Mark the most recent matching tool step as done
                            for (int i = timeline.size() - 1; i >= 0; i--) {
                                Map<String, Object> step = timeline.get(i);
                                if ("tool".equals(step.get("kind")) && name.equals(step.get("name"))
                                        && !Boolean.TRUE.equals(step.get("done"))) {
                                    step.put("done", true);
                                    break;
                                }
                            }
                            session.emitToolEnd(uid, name);
                        } else if (line.startsWith("[TOOL_FAIL]")) {
                            String payload = line.substring(11).trim();
                            int tab = payload.indexOf('\t');
                            String name = tab >= 0 ? payload.substring(0, tab) : payload;
                            String errMsg = tab >= 0 ? payload.substring(tab + 1) : "";
                            // Mark the most recent matching tool step as failed
                            for (int i = timeline.size() - 1; i >= 0; i--) {
                                Map<String, Object> step = timeline.get(i);
                                if ("tool".equals(step.get("kind")) && name.equals(step.get("name"))
                                        && !Boolean.TRUE.equals(step.get("done"))) {
                                    step.put("done", true);
                                    if (!errMsg.isEmpty()) step.put("error", errMsg);
                                    break;
                                }
                            }
                            session.emitToolFail(uid, name, errMsg);
                        } else if (line.startsWith("[TOOL]")) {
                            String name = line.substring(6).trim();
                            // A new tool call closes the current thinking segment
                            openThinking[0] = null;
                            Map<String, Object> step = new LinkedHashMap<>();
                            step.put("kind", "tool"); step.put("name", name); step.put("done", false);
                            timeline.add(step);
                            session.emitTool(uid, name);
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
                            appendThinking.accept(decoded);
                            session.emitReasoning(uid, decoded);
                        } else {
                            String tok = line.isEmpty() ? "\n" : line;
                            accumContent.append(tok);
                            session.emitToken(uid, tok);
                        }
                    }
                }
                boolean finished = p.waitFor(10, TimeUnit.MINUTES);
                if (!finished) { p.destroyForcibly(); session.emitError(uid, "AI 对话超时"); }
                Files.deleteIfExists(tmpInput);

                // Persist assistant turn (content + structured timeline) once generation completes
                if (uid > 0 && accumContent.length() > 0) {
                    try {
                        String content = accumContent.toString();
                        if (content.length() > 8000) content = content.substring(0, 8000);
                        jdbc.update("INSERT INTO ai_chat_history (user_id, role, content) VALUES (?, 'assistant', ?)",
                            uid, content);
                        // Persist the entire timeline (thinking + tool steps) as JSON in the 'thinking'
                        // role row, so we can faithfully replay the reasoning trace on history reload.
                        if (!timeline.isEmpty()) {
                            String tlJson = json.writeValueAsString(timeline);
                            if (tlJson.length() > 8000) tlJson = tlJson.substring(0, 8000);
                            jdbc.update("INSERT INTO ai_chat_history (user_id, role, content) VALUES (?, 'thinking', ?)",
                                uid, tlJson);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                session.emitError(uid, e.getMessage());
            } finally {
                session.clearSession(uid);
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
        if (!session.isActive(uid)) {
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

    @PostMapping("/clear")
    public Map<String, Object> clear(HttpServletRequest req) {
        long uid = userIdOf(req);
        session.clearSession(uid);
        if (uid > 0) {
            try { jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ? AND role IN ('user','assistant','thinking')", uid); } catch (Exception ignored) {}
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
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content FROM ai_chat_history WHERE user_id = ? " +
                "AND role IN ('user','assistant','thinking') ORDER BY id ASC LIMIT 200", uid);
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
            return Map.of("messages", out);
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
            if (n == 0) return Map.of("show", false);

            int hour = java.time.LocalTime.now().getHour();
            String greeting = hour < 6 ? "夜深了" : hour < 12 ? "早安" : hour < 18 ? "午安" : "晚上好";

            StringBuilder msg = new StringBuilder();
            msg.append(greeting).append("。你目前持有 ").append(n).append(" 只标的");
            if (!movers.isEmpty()) {
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
            msg.append("。需要我现在帮你看看什么吗？");

            return Map.of(
                "show", true,
                "title", "观澜 · " + greeting,
                "message", msg.toString()
            );
        } catch (Exception e) {
            return Map.of("show", false);
        }
    }

    private long userIdOf(HttpServletRequest req) {
        jakarta.servlet.http.HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object uid = s.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
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
            sb.append("\n如需完整持仓数据或量化分析，请调用 get_portfolio / get_portfolio_analysis 等工具。");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
