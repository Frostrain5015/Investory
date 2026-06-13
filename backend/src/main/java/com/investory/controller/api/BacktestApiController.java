package com.investory.controller.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.investory.dao.BacktestDao;
import com.investory.server.AppContext;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BacktestApiController {

    private static final Gson gson = new Gson();
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final BacktestDao backtestDao = AppContext.get(BacktestDao.class);
    private final String pythonExecutable = System.getProperty("python.executable", "python3");

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
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "未登录")));
            return;
        }

        String jsonBody = new String(req.getReader().readAllBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = gson.fromJson(jsonBody, Map.class);

        String name = (String) body.getOrDefault("name", "未命名策略");
        String strategyType = (String) body.get("strategyType");
        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) body.get("strategy");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        Long portfolioId = getPortfolioId(req);

        String strategyJson, configJson;
        try {
            strategyJson = gson.toJson(strategy);
            configJson = gson.toJson(config);
        } catch (Exception e) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "JSON序列化失败: " + e.getMessage())));
            return;
        }

        String startDate = (String) config.get("startDate");
        String endDate = (String) config.get("endDate");

        long resultId = backtestDao.insert(userId, portfolioId, name, strategyType,
            strategyJson, configJson, startDate, endDate, "[]", "{}", "[]");

        executor.submit(() -> {
            try {
                File script = new File("script/backtest_engine.py");
                if (!script.exists()) script = new File("../script/backtest_engine.py").getCanonicalFile();
                if (!script.exists()) return;
                File scriptDir = script.getParentFile();
                Path tmpInput = Files.createTempFile("backtest_input_", ".json");
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("strategy_type", strategyType);
                input.put("strategy", strategy);
                input.put("config", config);
                input.put("result_id", resultId);
                Files.writeString(tmpInput, gson.toJson(input));
                ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u", script.getAbsolutePath(), "--input", tmpInput.toString());
                pb.directory(scriptDir);
                pb.redirectErrorStream(true);
                pb.environment().put("PYTHONUNBUFFERED", "1");
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) { /* consume output */ }
                }
                boolean finished = p.waitFor(15, TimeUnit.MINUTES);
                if (!finished) { p.destroyForcibly(); }
                int exitCode = finished ? p.exitValue() : -1;
                Files.deleteIfExists(tmpInput);
                Path tmpOutput = scriptDir.toPath().resolve("backtest_output_" + resultId + ".json");
                Path tmpError  = scriptDir.toPath().resolve("backtest_error_" + resultId + ".json");
                if (exitCode == 0 && Files.exists(tmpOutput)) {
                    String outputJson = Files.readString(tmpOutput);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> output = gson.fromJson(outputJson, Map.class);
                    String equityCurve = gson.toJson(output.get("equityCurve"));
                    String metrics = gson.toJson(output.get("metrics"));
                    String tradeLog = gson.toJson(output.get("tradeLog"));
                    backtestDao.updateResult(resultId, equityCurve, metrics, tradeLog);
                    Files.deleteIfExists(tmpOutput);
                } else {
                    String errDetail = "";
                    if (Files.exists(tmpError)) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> errData = gson.fromJson(Files.readString(tmpError), Map.class);
                            errDetail = ": " + errData.getOrDefault("error", "").toString();
                        } catch (Exception ignored) {}
                        Files.deleteIfExists(tmpError);
                    }
                    String errMsg = exitCode != 0 ? "回测引擎异常退出 (code=" + exitCode + ")" + errDetail : "回测引擎未产生输出文件" + errDetail;
                    backtestDao.updateResult(resultId, "[]",
                        "{\"error\":\"" + errMsg.replace("\"", "'") + "\"}", "[]");
                }
            } catch (Exception ignored) {}
        });

        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "started", "resultId", resultId)));
    }

    public void handleGetStatus(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "idle")));
    }

    public void handleStream(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        var writer = resp.getWriter();
        writer.write("event: error\ndata: {\"msg\":\"无活跃回测\"}\n\n");
        writer.flush();
    }

    public void handleHistory(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(backtestDao.findByUser(userId)));
    }

    public void handleGetResult(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "unauthorized")));
            return;
        }
        Map<String, Object> row = backtestDao.findById(id);
        if (row == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not found")));
            return;
        }
        Long ownerId = row.get("user_id") instanceof Number ? ((Number) row.get("user_id")).longValue() : null;
        if (ownerId == null || ownerId != userId) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not found")));
            return;
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(row));
    }

    public void handleDeleteResult(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "unauthorized")));
            return;
        }
        int deleted = backtestDao.delete(id, userId);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", deleted > 0 ? "ok" : "not_found")));
    }

    public void handleCompare(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        String ids = req.getParameter("ids");
        if (ids == null || ids.isBlank()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        List<Long> idList = Arrays.stream(ids.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .map(Long::parseLong).limit(10).toList();
        if (idList.size() < 2) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        List<Map<String, Object>> rows = backtestDao.findByIds(userId, idList);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("name", row.get("name"));
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
                    java.lang.reflect.Type listType = new TypeToken<List<Map<String, Object>>>(){}.getType();
                    List<Map<String, Object>> curve = gson.fromJson(ej, listType);
                    if (!curve.isEmpty()) {
                        Object baseObj = curve.get(0).get("equity");
                        double base = baseObj instanceof Number ? ((Number) baseObj).doubleValue() : 1.0;
                        if (base == 0) base = 1;
                        final double b = base;
                        List<Map<String, Object>> norm = curve.stream().map(pt -> {
                            Object eqObj = pt.get("equity");
                            double eq = eqObj instanceof Number ? ((Number) eqObj).doubleValue() : b;
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("date", pt.get("date"));
                            p.put("value", Math.round(eq / b * 10000.0) / 100.0);
                            return p;
                        }).toList();
                        item.put("equityCurveNormalized", norm);
                    } else { item.put("equityCurveNormalized", List.of()); }
                } else { item.put("equityCurveNormalized", List.of()); }
            } catch (Exception e) { item.put("equityCurveNormalized", List.of()); }
            result.add(item);
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }
}
