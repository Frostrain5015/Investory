package com.investory.controller.api;

import com.google.gson.Gson;
import com.investory.dao.QuantCacheDao;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 量化分析 REST 控制器，路径前缀 /api/quant。
 */
public class QuantApiController {

    private static final Gson gson = new Gson();
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final QuantCacheDao quantDao = AppContext.get(QuantCacheDao.class);
    private final String pythonExecutable = System.getProperty("python.executable", "python3");

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return 0;
        Object pid = session.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    public void handleGetHoldingsMetrics(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("metrics", Map.of())));
            return;
        }
        List<Map<String, Object>> holdings = jdbcQueryForList(
            "SELECT stock_id FROM holdings WHERE portfolio_id = ? AND total_shares > 0", portfolioId);
        List<Long> stockIds = holdings.stream()
            .map(h -> ((Number) h.get("stock_id")).longValue())
            .collect(Collectors.toList());
        Map<Long, Map<String, Object>> metrics = quantDao.findMetricsByStockIds(stockIds);
        Map<String, Object> metricsStr = new LinkedHashMap<>();
        metrics.forEach((k, v) -> metricsStr.put(String.valueOf(k), v));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(Map.of("metrics", metricsStr)));
    }

    public void handleGetPortfolioScenario(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("scenarios", List.of(), "risk", Map.of())));
            return;
        }
        List<Map<String, Object>> scenarios = quantDao.findScenariosByPortfolio(portfolioId);
        Map<String, Object> risk = quantDao.findRiskSummaryByPortfolio(portfolioId);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(Map.of("scenarios", scenarios, "risk", risk != null ? risk : Map.of())));
    }

    public void handleGetPortfolioStyle(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "no portfolio")));
            return;
        }
        try {
            File script = new File("script/portfolio_style_analyzer.py");
            if (!script.exists()) script = new File("../script/portfolio_style_analyzer.py").getCanonicalFile();
            if (!script.exists()) {
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(gson.toJson(Map.of("error", "分析引擎未找到")));
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, script.getAbsolutePath(), "--portfolio-id", String.valueOf(portfolioId), "--mode", "quick");
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), "UTF-8");
            boolean finished = p.waitFor(5, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly();
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(gson.toJson(Map.of("error", "分析超时")));
                return;
            }
            int exitCode = p.exitValue();
            if (exitCode == 0 && !output.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = gson.fromJson(output, Map.class);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(gson.toJson(result));
                return;
            }
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "分析失败, exit=" + exitCode)));
        } catch (Exception e) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", e.getMessage())));
        }
    }

    public void handleStartRefresh(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        var writer = resp.getWriter();

        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) {
            writer.write("event: error\ndata: {\"msg\":\"未登录或无活跃组合\"}\n\n");
            writer.flush();
            return;
        }

        final long pid = portfolioId;
        jakarta.servlet.AsyncContext ac = req.startAsync();
        ac.setTimeout(0);

        executor.submit(() -> {
            try {
                writer.write("event: status\ndata: {\"msg\":\"启动量化分析...\"}\n\n");
                writer.flush();

                File script = new File("script/analyze_quant.py");
                if (!script.exists()) script = new File("../script/analyze_quant.py").getCanonicalFile();
                if (!script.exists()) {
                    writer.write("event: error\ndata: {\"msg\":\"脚本未找到\"}\n\n");
                    writer.flush();
                    ac.complete();
                    return;
                }
                ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u", script.getAbsolutePath(), "--mode", "all", "--portfolio-id", String.valueOf(pid));
                pb.directory(script.getParentFile());
                pb.redirectErrorStream(true);
                pb.environment().put("PYTHONUNBUFFERED", "1");
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = PROGRESS_RE.matcher(line);
                        if (m.find()) {
                            Map<String, Object> prog = new LinkedHashMap<>();
                            prog.put("current", Integer.parseInt(m.group(1)));
                            prog.put("total",   Integer.parseInt(m.group(2)));
                            prog.put("pct",     Double.parseDouble(m.group(3)));
                            prog.put("name",    m.group(4).trim());
                            writer.write("event: progress\ndata: " + gson.toJson(prog) + "\n\n");
                            writer.flush();
                        } else if (line.contains("===")) {
                            writer.write("event: info\ndata: {\"msg\":\"" + line.trim() + "\"}\n\n");
                            writer.flush();
                        } else {
                            writer.write("event: log\ndata: {\"msg\":\"" + line.trim() + "\"}\n\n");
                            writer.flush();
                        }
                    }
                }
                boolean finished = p.waitFor(15, TimeUnit.MINUTES);
                if (!finished) { p.destroyForcibly();
                    writer.write("event: error\ndata: {\"msg\":\"量化分析超时（15分钟），已终止\"}\n\n");
                    writer.flush();
                } else if (p.exitValue() == 0) {
                    writer.write("event: done\ndata: {\"msg\":\"量化分析完成\"}\n\n");
                    writer.flush();
                } else {
                    writer.write("event: error\ndata: {\"msg\":\"脚本退出码: " + p.exitValue() + "\"}\n\n");
                    writer.flush();
                }
            } catch (Exception e) {
                try { writer.write("event: error\ndata: {\"msg\":\"" + e.getMessage() + "\"}\n\n"); writer.flush(); } catch (Exception ignored) {}
            } finally {
                ac.complete();
            }
        });
    }

    public void handleOptimize(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "未选择组合")));
            return;
        }
        String mode = req.getParameter("mode") != null ? req.getParameter("mode") : "sharpe";
        double maxWeight = req.getParameter("maxWeight") != null ? Double.parseDouble(req.getParameter("maxWeight")) : 0.30;
        try {
            File script = new File("script/optimizer.py");
            if (!script.exists()) script = new File("../script/optimizer.py").getCanonicalFile();
            if (!script.exists()) {
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(gson.toJson(Map.of("error", "优化器脚本未找到")));
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u", script.getAbsolutePath(), "--portfolio-id", String.valueOf(portfolioId), "--mode", mode, "--max-weight", String.valueOf(maxWeight));
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
            if (!finished) { p.destroyForcibly();
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(gson.toJson(Map.of("error", "优化超时")));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = gson.fromJson(out.toString(), Map.class);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(result));
        } catch (Exception e) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", e.getMessage())));
        }
    }

    public void handleContextSummary(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(Map.of("error", "no portfolio")));
            return;
        }
        Map<String, Double> toCny = new HashMap<>(); toCny.put("CNY", 1.0);
        try { jdbcQueryForList("SELECT currency, rate FROM exchange_rates").forEach(r -> {
            String c = (String) r.get("currency"); Number rate = (Number) r.get("rate");
            if (rate != null && rate.doubleValue() > 0) toCny.put(c, 1.0 / rate.doubleValue());
        }); } catch (Exception ignored) {}
        List<Map<String, Object>> rows = jdbcQueryForList(
            "SELECT s.symbol, s.name, s.market, s.currency, h.total_shares, h.avg_cost, " +
            "  (SELECT sp.close FROM stock_prices sp WHERE sp.stock_id = h.stock_id ORDER BY sp.trade_date DESC LIMIT 1) AS latest_price " +
            "FROM holdings h JOIN stocks s ON s.id = h.stock_id " +
            "WHERE h.portfolio_id = ? AND h.total_shares > 0", portfolioId);
        double totalValue = 0;
        double[][] mv = new double[rows.size()][2];
        for (int i = 0; i < rows.size(); i++) {
            Number shares = (Number) rows.get(i).get("total_shares");
            Number price = (Number) rows.get(i).get("latest_price");
            if (shares == null || price == null) continue;
            double rate = toCny.getOrDefault((String) rows.get(i).get("currency"), 1.0);
            mv[i][0] = shares.doubleValue() * price.doubleValue() * rate;
            Number avgCost = (Number) rows.get(i).get("avg_cost");
            mv[i][1] = (avgCost != null && avgCost.doubleValue() > 0) ? (price.doubleValue() - avgCost.doubleValue()) / avgCost.doubleValue() * 100 : 0;
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
            Map<String, Object> risk = jdbcQueryForMap("SELECT weighted_beta FROM portfolio_risk_cache WHERE portfolio_id = ?", portfolioId);
            if (risk.get("weighted_beta") != null) weightedBeta = ((Number) risk.get("weighted_beta")).doubleValue();
        } catch (Exception ignored) {}
        String dominantStyle = "";
        try {
            List<Map<String, Object>> styleRows = jdbcQueryForList(
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
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleHoldingsCorrelation(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        String symbol = req.getParameter("symbol");
        List<Map<String, Object>> targetRows = jdbcQueryForList("SELECT id FROM stocks WHERE symbol = ? LIMIT 1", symbol);
        if (targetRows.isEmpty()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        long targetId = ((Number) targetRows.get(0).get("id")).longValue();
        List<Map<String, Object>> holdingRows = jdbcQueryForList(
            "SELECT h.stock_id, s.symbol, s.name FROM holdings h JOIN stocks s ON s.id = h.stock_id " +
            "WHERE h.portfolio_id = ? AND h.total_shares > 0 AND h.stock_id != ?", portfolioId, targetId);
        if (holdingRows.isEmpty()) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        Map<String, Double> targetPrices = fetchPriceSeries(targetId, 32);
        if (targetPrices.size() < 11) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
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
        result.sort((a, b) -> Double.compare(Math.abs((Double) b.get("correlation_30d")), Math.abs((Double) a.get("correlation_30d"))));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(result));
    }

    private Map<String, Double> fetchPriceSeries(long stockId, int limit) throws Exception {
        List<Map<String, Object>> rows = jdbcQueryForList(
            "SELECT trade_date, close FROM stock_prices WHERE stock_id = ? ORDER BY trade_date DESC LIMIT ?", stockId, limit);
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

    private Map<String, Object> jdbcQueryForMap(String sql, Object... args) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    int colCount = rs.getMetaData().getColumnCount();
                    for (int c = 1; c <= colCount; c++) row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    return row;
                }
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
}
