package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.crawler.BacktestSessionManager;
import com.investory.dao.BacktestDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/backtest")
public class BacktestApiController {

    private static final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    private final JdbcTemplate jdbc;
    private final BacktestDao backtestDao;
    private final BacktestSessionManager session;

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    @Autowired
    public BacktestApiController(JdbcTemplate jdbc, BacktestDao backtestDao, BacktestSessionManager session) {
        this.jdbc = jdbc;
        this.backtestDao = backtestDao;
        this.session = session;
    }

    // ── Session helpers ──────────────────────────────────────────────────

    private long getUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object uid = s.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    private Long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return null;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : null;
    }

    // ── Start backtest (JSON response, then stream via GET /stream) ──────

    @PostMapping("/start")
    public Map<String, Object> startBacktest(@RequestBody Map<String, Object> body,
                                              HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "未登录");

        String name = (String) body.getOrDefault("name", "未命名策略");
        String strategyType = (String) body.get("strategyType");
        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) body.get("strategy");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        Long portfolioId = getPortfolioId(req);

        String strategyJson, configJson;
        try {
            strategyJson = json.writeValueAsString(strategy);
            configJson = json.writeValueAsString(config);
        } catch (Exception e) {
            return Map.of("error", "JSON序列化失败: " + e.getMessage());
        }

        String startDate = (String) config.get("startDate");
        String endDate = (String) config.get("endDate");

        long resultId = backtestDao.insert(userId, portfolioId, name, strategyType,
            strategyJson, configJson, startDate, endDate, "[]", "{}", "[]");

        session.startSession();

        executor.submit(() -> {
            try {
                session.emitStatus("启动回测: " + name);

                File script = new File("script/backtest_engine.py");
                if (!script.exists()) {
                    script = new File("../script/backtest_engine.py").getCanonicalFile();
                }
                if (!script.exists()) {
                    session.emitError("回测引擎脚本未找到");
                    session.clearSession();
                    return;
                }
                File scriptDir = script.getParentFile();

                // Write input JSON to temp file
                Path tmpInput = Files.createTempFile("backtest_input_", ".json");
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("strategy_type", strategyType);
                input.put("strategy", strategy);
                input.put("config", config);
                input.put("result_id", resultId);
                Files.writeString(tmpInput, json.writeValueAsString(input));

                ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable, "-u", script.getAbsolutePath(),
                    "--input", tmpInput.toString()
                );
                pb.directory(scriptDir);
                pb.redirectErrorStream(true);
                pb.environment().put("PYTHONUNBUFFERED", "1");

                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = PROGRESS_RE.matcher(line);
                        if (m.find()) {
                            Map<String, Object> prog = new LinkedHashMap<>();
                            prog.put("current", Integer.parseInt(m.group(1)));
                            prog.put("total",   Integer.parseInt(m.group(2)));
                            prog.put("pct",     Double.parseDouble(m.group(3)));
                            prog.put("name",    m.group(4).trim());
                            session.updateProgress(prog);
                        } else if (line.contains("===")) {
                            session.emitInfo(line.replaceFirst("^.*?INFO\\s*", "").trim());
                        } else {
                            session.addLog(line.trim());
                        }
                    }
                }

                int exitCode = p.waitFor();
                Files.deleteIfExists(tmpInput);

                // Read output JSON
                Path tmpOutput = Path.of("backtest_output_" + resultId + ".json");
                if (exitCode == 0 && Files.exists(tmpOutput)) {
                    String outputJson = Files.readString(tmpOutput);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> output = json.readValue(outputJson, Map.class);
                    String equityCurve = json.writeValueAsString(output.get("equityCurve"));
                    String metrics = json.writeValueAsString(output.get("metrics"));
                    String tradeLog = json.writeValueAsString(output.get("tradeLog"));
                    backtestDao.updateResult(resultId, equityCurve, metrics, tradeLog);
                    session.emitDone("回测完成", resultId);
                    Files.deleteIfExists(tmpOutput);
                } else if (exitCode != 0) {
                    session.emitError("回测引擎退出码: " + exitCode);
                }
            } catch (Exception e) {
                session.emitError(e.getMessage());
            } finally {
                session.clearSession();
            }
        });

        return Map.of("status", "started", "resultId", resultId);
    }

    // ── Reconnect to active session ──────────────────────────────────────

    @GetMapping("/status")
    public Map<String, Object> getStatus(HttpServletRequest req) {
        return session.getStatus();
    }

    @GetMapping("/stream")
    public SseEmitter stream(HttpServletRequest req, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        if (!session.isActive()) {
            SseEmitter err = new SseEmitter();
            emit(err, "error", Map.of("msg", "无活跃回测"));
            err.complete();
            return err;
        }
        return session.subscribe();
    }

    // ── CRUD for saved results ───────────────────────────────────────────

    @GetMapping("/history")
    public List<Map<String, Object>> history(HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return List.of();
        return backtestDao.findByUser(userId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getResult(@PathVariable long id, HttpServletRequest req) {
        long userId = getUserId(req);
        Map<String, Object> row = backtestDao.findById(id);
        if (row == null) return Map.of("error", "not found");
        return row;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteResult(@PathVariable long id, HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "unauthorized");
        int deleted = backtestDao.delete(id, userId);
        return Map.of("status", deleted > 0 ? "ok" : "not_found");
    }

    private void emit(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(data)));
        } catch (Exception ignored) {}
    }
}
