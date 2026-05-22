package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.dao.QuantCacheDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/quant")
public class QuantApiController {

    private static final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 与 AdminController 完全相同的进度行正则
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    private final JdbcTemplate jdbc;
    private final QuantCacheDao quantDao;

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    @Autowired
    public QuantApiController(JdbcTemplate jdbc, QuantCacheDao quantDao) {
        this.jdbc = jdbc;
        this.quantDao = quantDao;
    }

    // ── 获取持仓股票的量化指标（持仓页可选列数据源）──────────────────────────

    @GetMapping("/holdings-metrics")
    public Map<String, Object> getHoldingsMetrics(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("metrics", Map.of());

        List<Map<String, Object>> holdings = jdbc.queryForList(
            "SELECT stock_id FROM holdings WHERE portfolio_id = ? AND total_shares > 0",
            portfolioId);

        List<Long> stockIds = holdings.stream()
            .map(h -> ((Number) h.get("stock_id")).longValue())
            .collect(Collectors.toList());

        Map<Long, Map<String, Object>> metrics = quantDao.findMetricsByStockIds(stockIds);

        // 将 key 转为 String 方便前端按 stockId 字符串索引
        Map<String, Object> metricsStr = new LinkedHashMap<>();
        metrics.forEach((k, v) -> metricsStr.put(String.valueOf(k), v));

        return Map.of("metrics", metricsStr);
    }

    // ── 获取组合压测 + 风险汇总（量化页数据源）───────────────────────────────

    @GetMapping("/portfolio-scenario")
    public Map<String, Object> getPortfolioScenario(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("scenarios", List.of(), "risk", Map.of());

        List<Map<String, Object>> scenarios = quantDao.findScenariosByPortfolio(portfolioId);
        Map<String, Object> risk = quantDao.findRiskSummaryByPortfolio(portfolioId);

        return Map.of(
            "scenarios", scenarios,
            "risk", risk != null ? risk : Map.of()
        );
    }

    // ── 组合风格诊断 ─────────────────────────────────────────────────────

    @GetMapping("/portfolio-style")
    public Map<String, Object> getPortfolioStyle(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("error", "no portfolio");

        try {
            File script = new File("script/portfolio_style_analyzer.py");
            if (!script.exists()) {
                script = new File("../script/portfolio_style_analyzer.py").getCanonicalFile();
            }
            if (!script.exists()) {
                return Map.of("error", "分析引擎未找到");
            }
            File scriptDir = script.getParentFile();

            ProcessBuilder pb = new ProcessBuilder(
                pythonExecutable, script.getAbsolutePath(),
                "--portfolio-id", String.valueOf(portfolioId),
                "--mode", "quick"
            );
            pb.directory(scriptDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), "UTF-8");
            int exitCode = p.waitFor();
            if (exitCode == 0 && !output.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = json.readValue(output, Map.class);
                return result;
            }
            return Map.of("error", "分析失败, exit=" + exitCode);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // ── SSE 刷新：触发 analyze_quant.py，实时推送进度 ──────────────────────────

    @GetMapping("/refresh")
    public SseEmitter startRefresh(HttpServletRequest req, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(0L);

        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) {
            emit(emitter, "error", Map.of("msg", "未登录或无活跃组合"));
            emitter.complete();
            return emitter;
        }

        final long pid = portfolioId;
        executor.submit(() -> {
            try {
                emit(emitter, "status", Map.of("msg", "启动量化分析..."));

                // 支持两种路径：云端 script/ 和本地开发 ../script/
                File script = new File("script/analyze_quant.py");
                if (!script.exists()) {
                    script = new File("../script/analyze_quant.py").getCanonicalFile();
                }
                if (!script.exists()) {
                    emit(emitter, "error", Map.of("msg", "脚本未找到: " + script.getAbsolutePath()));
                    emitter.complete();
                    return;
                }
                File scriptDir = script.getParentFile();

                ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable, "-u", script.getAbsolutePath(),
                    "--mode", "all",
                    "--portfolio-id", String.valueOf(pid)
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
                            emit(emitter, "progress", prog);
                        } else if (line.contains("===")) {
                            emit(emitter, "info", Map.of("msg", line.trim()));
                        } else {
                            emit(emitter, "log", Map.of("msg", line.trim()));
                        }
                    }
                }
                int exitCode = p.waitFor();
                if (exitCode == 0) {
                    emit(emitter, "done", Map.of("msg", "量化分析完成"));
                } else {
                    emit(emitter, "error", Map.of("msg", "脚本退出码: " + exitCode));
                }
            } catch (Exception e) {
                emit(emitter, "error", Map.of("msg", e.getMessage()));
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    // 组合优化
    @GetMapping("/optimize")
    public Map<String, Object> optimize(@RequestParam(defaultValue = "sharpe") String mode,
                                        @RequestParam(defaultValue = "0.30") double maxWeight,
                                        HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("error", "未选择组合");

        try {
            File script = new File("script/optimizer.py");
            if (!script.exists()) {
                script = new File("../script/optimizer.py").getCanonicalFile();
            }
            if (!script.exists()) return Map.of("error", "优化器脚本未找到");

            ProcessBuilder pb = new ProcessBuilder(
                pythonExecutable, "-u", script.getAbsolutePath(),
                "--portfolio-id", String.valueOf(portfolioId),
                "--mode", mode,
                "--max-weight", String.valueOf(maxWeight));
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONUNBUFFERED", "1");

            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line);
            }
            p.waitFor();
            return json.readValue(out.toString(), Map.class);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return 0;
        Object pid = session.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    private void emit(SseEmitter emitter, String event, Object data) {
        try {
            String jsonStr = json.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event).data(jsonStr));
        } catch (Exception e) {
            System.err.println("[QuantSSE] emit failed event=" + event + " error=" + e.getMessage());
        }
    }
}
