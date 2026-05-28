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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/quant")
public class QuantApiController {

    private static final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

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
            boolean finished = p.waitFor(5, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly(); return Map.of("error", "分析超时"); }
            int exitCode = p.exitValue();
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
                boolean finished = p.waitFor(15, TimeUnit.MINUTES);
                if (!finished) {
                    p.destroyForcibly();
                    emit(emitter, "error", Map.of("msg", "量化分析超时（15分钟），已终止"));
                } else if (p.exitValue() == 0) {
                    emit(emitter, "done", Map.of("msg", "量化分析完成"));
                } else {
                    emit(emitter, "error", Map.of("msg", "脚本退出码: " + p.exitValue()));
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
            boolean finished = p.waitFor(5, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly(); return Map.of("error", "优化超时"); }
            return json.readValue(out.toString(), Map.class);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // ── 持仓上下文摘要（AI对话系统提示词注入用）────────────────────────────────

    @GetMapping("/context-summary")
    public Map<String, Object> contextSummary(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("error", "no portfolio");

        Map<String, Double> toCny = new HashMap<>(); toCny.put("CNY", 1.0);
        try { jdbc.queryForList("SELECT currency, rate FROM exchange_rates").forEach(r -> {
            String c = (String) r.get("currency"); Number rate = (Number) r.get("rate");
            if (rate != null && rate.doubleValue() > 0) toCny.put(c, 1.0 / rate.doubleValue());
        }); } catch (Exception ignored) {}

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.symbol, s.name, s.market, s.currency, h.total_shares, h.avg_cost, " +
            "  (SELECT sp.close FROM stock_prices sp WHERE sp.stock_id = h.stock_id ORDER BY sp.trade_date DESC LIMIT 1) AS latest_price " +
            "FROM holdings h JOIN stocks s ON s.id = h.stock_id " +
            "WHERE h.portfolio_id = ? AND h.total_shares > 0", portfolioId);

        double totalValue = 0;
        double[][] mv = new double[rows.size()][2]; // [marketValue, pnlPct]
        for (int i = 0; i < rows.size(); i++) {
            Number shares = (Number) rows.get(i).get("total_shares");
            Number price = (Number) rows.get(i).get("latest_price");
            if (shares == null || price == null) continue;
            double rate = toCny.getOrDefault((String) rows.get(i).get("currency"), 1.0);
            mv[i][0] = shares.doubleValue() * price.doubleValue() * rate;
            Number avgCost = (Number) rows.get(i).get("avg_cost");
            mv[i][1] = (avgCost != null && avgCost.doubleValue() > 0)
                ? (price.doubleValue() - avgCost.doubleValue()) / avgCost.doubleValue() * 100 : 0;
            totalValue += mv[i][0];
        }

        final double tv = totalValue;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) idx.add(i);
        idx.sort((a, b) -> Double.compare(mv[b][0], mv[a][0]));

        List<Map<String, Object>> top5 = new ArrayList<>();
        for (int k = 0; k < Math.min(5, idx.size()); k++) {
            int i = idx.get(k); if (mv[i][0] <= 0) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("symbol", rows.get(i).get("symbol")); entry.put("name", rows.get(i).get("name"));
            entry.put("weightPct", tv > 0 ? Math.round(mv[i][0] / tv * 1000.0) / 10.0 : 0);
            entry.put("pnlPct", Math.round(mv[i][1] * 10.0) / 10.0);
            top5.add(entry);
        }

        Map<String, Double> marketMv = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String market = (String) rows.get(i).get("market"); if (market == null) continue;
            String group = (market.equals("SH") || market.equals("SZ")) ? "A股"
                : market.equals("HK") ? "港股" : market.equals("US") ? "美股" : "其他";
            marketMv.merge(group, mv[i][0], Double::sum);
        }
        Map<String, Object> marketAlloc = new LinkedHashMap<>();
        marketMv.forEach((k, v) -> marketAlloc.put(k, tv > 0 ? Math.round(v / tv * 1000.0) / 10.0 : 0.0));

        double weightedBeta = 1.0;
        try {
            Map<String, Object> risk = jdbc.queryForMap(
                "SELECT weighted_beta FROM portfolio_risk_cache WHERE portfolio_id = ?", portfolioId);
            if (risk.get("weighted_beta") != null) weightedBeta = ((Number) risk.get("weighted_beta")).doubleValue();
        } catch (Exception ignored) {}

        String dominantStyle = "";
        try {
            List<Map<String, Object>> styleRows = jdbc.queryForList(
                "SELECT m.factor_style, COUNT(*) AS cnt FROM stock_metric_cache m " +
                "JOIN holdings h ON h.stock_id = m.stock_id " +
                "WHERE h.portfolio_id = ? AND h.total_shares > 0 AND m.factor_style IS NOT NULL " +
                "GROUP BY m.factor_style ORDER BY cnt DESC LIMIT 1", portfolioId);
            if (!styleRows.isEmpty()) dominantStyle = (String) styleRows.get(0).get("factor_style");
        } catch (Exception ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalValue", Math.round(totalValue));
        result.put("top5Holdings", top5);
        result.put("weightedBeta", Math.round(weightedBeta * 100.0) / 100.0);
        result.put("marketAllocation", marketAlloc);
        if (!dominantStyle.isEmpty()) result.put("dominantStyle", dominantStyle);
        return result;
    }

    // ── 持仓与目标标的30日Pearson相关性 ───────────────────────────────────────

    @GetMapping("/holdings-correlation")
    public List<Map<String, Object>> holdingsCorrelation(
            @RequestParam String symbol, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return List.of();

        List<Map<String, Object>> targetRows = jdbc.queryForList(
            "SELECT id FROM stocks WHERE symbol = ? LIMIT 1", symbol);
        if (targetRows.isEmpty()) return List.of();
        long targetId = ((Number) targetRows.get(0).get("id")).longValue();

        List<Map<String, Object>> holdingRows = jdbc.queryForList(
            "SELECT h.stock_id, s.symbol, s.name FROM holdings h JOIN stocks s ON s.id = h.stock_id " +
            "WHERE h.portfolio_id = ? AND h.total_shares > 0 AND h.stock_id != ?",
            portfolioId, targetId);
        if (holdingRows.isEmpty()) return List.of();

        Map<String, Double> targetPrices = fetchPriceSeries(targetId, 32);
        if (targetPrices.size() < 11) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> h : holdingRows) {
            long sid = ((Number) h.get("stock_id")).longValue();
            Map<String, Double> prices = fetchPriceSeries(sid, 32);
            double corr = pearsonOnAligned(targetPrices, prices);
            if (Double.isNaN(corr)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", h.get("symbol"));
            row.put("name", h.get("name"));
            row.put("correlation_30d", Math.round(corr * 10000.0) / 10000.0);
            result.add(row);
        }
        result.sort((a, b) -> Double.compare(
            Math.abs((Double) b.get("correlation_30d")),
            Math.abs((Double) a.get("correlation_30d"))));
        return result;
    }

    private Map<String, Double> fetchPriceSeries(long stockId, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT trade_date, close FROM stock_prices WHERE stock_id = ? ORDER BY trade_date DESC LIMIT ?",
            stockId, limit);
        Map<String, Double> m = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String date = r.get("trade_date").toString().substring(0, 10);
            Object v = r.get("close");
            if (v != null) m.put(date, ((Number) v).doubleValue());
        }
        return m;
    }

    private double pearsonOnAligned(Map<String, Double> a, Map<String, Double> b) {
        List<String> dates = new ArrayList<>(a.keySet());
        dates.retainAll(b.keySet());
        Collections.sort(dates);
        if (dates.size() < 11) return Double.NaN;
        int n = dates.size() - 1;
        double[] ra = new double[n], rb = new double[n];
        for (int i = 1; i < dates.size(); i++) {
            double pa = a.get(dates.get(i - 1)), ca = a.get(dates.get(i));
            double pb = b.get(dates.get(i - 1)), cb = b.get(dates.get(i));
            ra[i - 1] = pa > 0 ? (ca - pa) / pa : 0;
            rb[i - 1] = pb > 0 ? (cb - pb) / pb : 0;
        }
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += ra[i]; my += rb[i]; }
        mx /= n; my /= n;
        double num = 0, dx2 = 0, dy2 = 0;
        for (int i = 0; i < n; i++) {
            double ex = ra[i] - mx, ey = rb[i] - my;
            num += ex * ey; dx2 += ex * ex; dy2 += ey * ey;
        }
        return (dx2 == 0 || dy2 == 0) ? Double.NaN : num / Math.sqrt(dx2 * dy2);
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
