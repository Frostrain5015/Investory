package com.investory.controller.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.investory.crawler.BacktestSessionManager;
import com.investory.dao.BacktestDao;
import com.investory.server.AppContext;
import com.investory.server.ConfigLoader;
import com.investory.server.DatabaseManager;
import com.investory.server.SseClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BacktestApiController {

    private static final Gson gson = new Gson();
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    private final ExecutorService executor;
    private final BacktestDao backtestDao;
    private final BacktestSessionManager session;
    private final String pythonExecutable = ConfigLoader.get("python.executable", "python3");

    public BacktestApiController() {
        this.executor = AppContext.get(ExecutorService.class);
        this.backtestDao = AppContext.get(BacktestDao.class);
        this.session = AppContext.get(BacktestSessionManager.class);
    }

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

    public void handleStartBacktest(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) { resp.getWriter().write("{\"error\":\"未登录\"}"); return; }

        StringBuilder sb = new StringBuilder();
        try (var reader = req.getReader()) { String l; while ((l = reader.readLine()) != null) sb.append(l); }
        JsonObject body = gson.fromJson(sb.toString(), JsonObject.class);

        String name = body != null && body.has("name") && !body.get("name").isJsonNull() ? body.get("name").getAsString() : "未命名策略";
        String strategyType = body != null && body.has("strategyType") ? body.get("strategyType").getAsString() : null;
        String strategyJson = body != null && body.has("strategy") ? gson.toJson(body.get("strategy")) : "{}";
        String configJson = body != null && body.has("config") ? gson.toJson(body.get("config")) : "{}";
        Long portfolioId = getPortfolioId(req);

        String startDate = "";
        String endDate = "";
        if (body != null && body.has("config")) {
            JsonObject config = body.getAsJsonObject("config");
            if (config.has("startDate")) startDate = config.get("startDate").getAsString();
            if (config.has("endDate")) endDate = config.get("endDate").getAsString();
        }

        long resultId = backtestDao.insert(userId, portfolioId, name, strategyType,
            strategyJson, configJson, startDate, endDate, "[]", "{}", "[]");

        session.startSession();

        executor.submit(() -> {
            try {
                session.emitStatus("启动回测: " + name);

                File script = new File("script/backtest_engine.py");
                if (!script.exists()) script = new File("../script/backtest_engine.py").getCanonicalFile();
                if (!script.exists()) { session.emitError("回测引擎脚本未找到"); session.clearSession(); return; }
                File scriptDir = script.getParentFile();

                Path tmpInput = Files.createTempFile("backtest_input_", ".json");
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("strategy_type", strategyType);
                if (body != null && body.has("strategy")) input.put("strategy", gson.fromJson(strategyJson, Map.class));
                if (body != null && body.has("config")) input.put("config", gson.fromJson(configJson, Map.class));
                input.put("result_id", resultId);
                Files.writeString(tmpInput, gson.toJson(input));

                ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u", script.getAbsolutePath(), "--input", tmpInput.toString());
                pb.directory(scriptDir); pb.redirectErrorStream(true); pb.environment().put("PYTHONUNBUFFERED", "1");

                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = PROGRESS_RE.matcher(line);
                        if (m.find()) {
                            Map<String, Object> prog = new LinkedHashMap<>();
                            prog.put("current", Integer.parseInt(m.group(1)));
                            prog.put("total", Integer.parseInt(m.group(2)));
                            prog.put("pct", Double.parseDouble(m.group(3)));
                            prog.put("name", m.group(4).trim());
                            session.updateProgress(prog);
                        } else if (line.contains("===")) {
                            session.emitInfo(line.replaceFirst("^.*?INFO\\s*", "").trim());
                        } else {
                            session.addLog(line.trim());
                        }
                    }
                }
                boolean finished = p.waitFor(15, TimeUnit.MINUTES);
                if (!finished) { p.destroyForcibly(); session.emitError("回测超时（15分钟）"); }
                int exitCode = finished ? p.exitValue() : -1;
                Files.deleteIfExists(tmpInput);

                Path tmpOutput = scriptDir.toPath().resolve("backtest_output_" + resultId + ".json");
                Path tmpError = scriptDir.toPath().resolve("backtest_error_" + resultId + ".json");

                if (exitCode == 0 && Files.exists(tmpOutput)) {
                    String outputJson = Files.readString(tmpOutput);
                    @SuppressWarnings("unchecked") Map<String, Object> output = gson.fromJson(outputJson, Map.class);
                    String equityCurve = gson.toJson(output.get("equityCurve"));
                    String metrics = gson.toJson(output.get("metrics"));
                    String tradeLog = gson.toJson(output.get("tradeLog"));
                    backtestDao.updateResult(resultId, equityCurve, metrics, tradeLog);
                    session.emitDone("回测完成", resultId);
                    Files.deleteIfExists(tmpOutput);
                } else {
                    String errDetail = "";
                    if (Files.exists(tmpError)) {
                        try {
                            @SuppressWarnings("unchecked") Map<String, Object> errData = gson.fromJson(Files.readString(tmpError), Map.class);
                            errDetail = ": " + String.valueOf(errData.getOrDefault("error", ""));
                            String tb = (String) errData.getOrDefault("traceback", "");
                            if (!tb.isEmpty()) {
                                String[] lines = tb.split("\n");
                                int start = Math.max(0, lines.length - 5);
                                for (int i = start; i < lines.length; i++) session.addLog(lines[i].trim());
                            }
                        } catch (Exception ignored) {}
                        Files.deleteIfExists(tmpError);
                    }
                    String errMsg = exitCode != 0 ? "回测引擎异常退出 (code=" + exitCode + ")" + errDetail : "回测引擎未产生输出文件" + errDetail;
                    session.emitError(errMsg);
                    backtestDao.updateResult(resultId, "[]", "{\"error\":\"" + errMsg.replace("\"", "'") + "\"}", "[]");
                }
            } catch (Exception e) {
                session.emitError(e.getMessage());
                backtestDao.updateResult(resultId, "[]", "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}", "[]");
            } finally {
                session.clearSession();
            }
        });

        resp.getWriter().write("{\"status\":\"started\",\"resultId\":" + resultId + "}");
    }

    public void handleGetStatus(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(session.getStatus()));
    }

    public void handleStream(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (!session.isActive()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"无活跃回测\"}");
            return;
        }
        SseClient client = session.subscribe(resp);
        req.startAsync();
        while (!client.isCompleted()) { try { Thread.sleep(1000); } catch (InterruptedException e) { break; } }
    }

    public void handleHistory(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) { resp.getWriter().write("[]"); return; }
        resp.getWriter().write(gson.toJson(backtestDao.findByUser(userId)));
    }

    public void handleGetResult(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }
        Map<String, Object> row = backtestDao.findById(id);
        if (row == null) { resp.getWriter().write("{\"error\":\"not found\"}"); return; }
        Long ownerId = row.get("user_id") instanceof Number ? ((Number) row.get("user_id")).longValue() : null;
        if (ownerId == null || ownerId != userId) { resp.getWriter().write("{\"error\":\"not found\"}"); return; }
        resp.getWriter().write(gson.toJson(row));
    }

    public void handleDeleteResult(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) { resp.getWriter().write("{\"error\":\"unauthorized\"}"); return; }
        int deleted = backtestDao.delete(id, userId);
        resp.getWriter().write(deleted > 0 ? "{\"status\":\"ok\"}" : "{\"status\":\"not_found\"}");
    }

    public void handleCompare(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        String ids = req.getParameter("ids");
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) { resp.getWriter().write("[]"); return; }
        List<Long> idList = Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .map(Long::parseLong).limit(10).toList();
        if (idList.size() < 2) { resp.getWriter().write("[]"); return; }

        List<Map<String, Object>> rows = backtestDao.findByIds(userId, idList);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id")); item.put("name", row.get("name"));
            item.put("strategyType", row.get("strategy_type"));
            item.put("startDate", row.get("start_date") != null ? row.get("start_date").toString().substring(0, 10) : "");
            item.put("endDate", row.get("end_date") != null ? row.get("end_date").toString().substring(0, 10) : "");
            try {
                String mj = (String) row.get("metrics_json");
                item.put("metrics", mj != null ? gson.fromJson(mj, Map.class) : Map.of());
            } catch (Exception e) { item.put("metrics", Map.of()); }
            try {
                String ej = (String) row.get("equity_curve_json");
                if (ej != null) {
                    List<Map<String, Object>> curve = gson.fromJson(ej, List.class);
                    if (!curve.isEmpty()) {
                        Object baseObj = curve.get(0).get("equity");
                        double base = baseObj instanceof Number ? ((Number) baseObj).doubleValue() : 1.0;
                        if (base == 0) base = 1;
                        final double b = base;
                        List<Map<String, Object>> norm = curve.stream().map(pt -> {
                            Object eqObj = pt.get("equity");
                            double eq = eqObj instanceof Number ? ((Number) eqObj).doubleValue() : b;
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("date", pt.get("date")); p.put("value", Math.round(eq / b * 10000.0) / 100.0);
                            return p;
                        }).toList();
                        item.put("equityCurveNormalized", norm);
                    } else { item.put("equityCurveNormalized", List.of()); }
                } else { item.put("equityCurveNormalized", List.of()); }
            } catch (Exception e) { item.put("equityCurveNormalized", List.of()); }
            result.add(item);
        }
        resp.getWriter().write(gson.toJson(result));
    }
}
