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

/**
 * 量化分析 REST 控制器，路径前缀 /api/quant。
 *
 * 核心职责：
 * 1. 返回持仓股票的量化指标（Beta、波动率、估值分位数等），数据来自 stock_metric_cache 表。
 * 2. 返回组合压测场景结果与风险汇总（来自 portfolio_scenario_cache / portfolio_risk_cache）。
 * 3. 调用 Python 脚本 portfolio_style_analyzer.py 执行组合风格诊断（成长/价值/动量等）。
 * 4. 通过 SSE 实时推送 analyze_quant.py 的刷新进度。
 * 5. 调用 optimizer.py 执行均值-方差组合优化（Sharpe/MinVar 模式）。
 * 6. 提供 AI 对话系统提示词注入所需的持仓上下文摘要。
 * 7. 计算目标标的与当前持仓各股的 30 日 Pearson 相关系数。
 */
@RestController
@RequestMapping("/api/quant")
public class QuantApiController {

    /** Jackson JSON 序列化器，用于 SSE 数据帧和脚本输出解析 */
    private static final ObjectMapper json = new ObjectMapper();

    /** 固定线程池，用于异步运行 Python 量化脚本 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /**
     * 进度行正则，与 AdminController 完全相同，匹配格式：
     *   "[current/total pct%] description"
     */
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    private final JdbcTemplate jdbc;

    /** 量化缓存数据访问对象，读取 stock_metric_cache / portfolio_* 缓存表 */
    private final QuantCacheDao quantDao;

    /** Python 可执行文件路径，默认 python3，可在 application.properties 覆盖 */
    @Value("${python.executable:python3}")
    private String pythonExecutable;

    @Autowired
    public QuantApiController(JdbcTemplate jdbc, QuantCacheDao quantDao) {
        this.jdbc = jdbc;
        this.quantDao = quantDao;
    }

    // ── 获取持仓股票的量化指标（持仓页可选列数据源）──────────────────────────

    /**
     * 获取当前用户持仓中所有股票的量化指标（Beta、波动率、估值分位数等）。
     * 数据来自 stock_metric_cache 表（由 analyze_quant.py 定期计算写入）。
     *
     * @param req HTTP 请求（从 Session 获取组合 id）
     * @return {metrics: {stockId → {beta, volatility, pe_percentile, ...}}}
     *         key 为字符串型 stockId，方便前端按字符串索引
     */
    @GetMapping("/holdings-metrics")
    public Map<String, Object> getHoldingsMetrics(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("metrics", Map.of());

        // 查询当前持仓中所有股票 id（份额 > 0）
        List<Map<String, Object>> holdings = jdbc.queryForList(
            "SELECT stock_id FROM holdings WHERE portfolio_id = ? AND total_shares > 0",
            portfolioId);

        List<Long> stockIds = holdings.stream()
            .map(h -> ((Number) h.get("stock_id")).longValue())
            .collect(Collectors.toList());

        Map<Long, Map<String, Object>> metrics = quantDao.findMetricsByStockIds(stockIds);

        // 将 Long 类型 key 转为 String，方便前端按 stockId 字符串索引
        Map<String, Object> metricsStr = new LinkedHashMap<>();
        metrics.forEach((k, v) -> metricsStr.put(String.valueOf(k), v));

        return Map.of("metrics", metricsStr);
    }

    // ── 获取组合压测 + 风险汇总（量化页数据源）───────────────────────────────

    /**
     * 获取当前组合的压测场景分析结果和风险指标汇总。
     * 压测场景（如市场下跌 10%、20%）来自 portfolio_scenario_cache 表；
     * 风险汇总（加权 Beta、最大回撤等）来自 portfolio_risk_cache 表。
     *
     * @param req HTTP 请求（从 Session 获取组合 id）
     * @return {scenarios: [...], risk: {...}}
     */
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

    /**
     * 调用 portfolio_style_analyzer.py 对当前组合进行风格诊断
     * （成长型/价值型/动量型/防御型等），返回各因子得分及主导风格。
     *
     * <p>脚本以 --mode quick 运行，最多等待 5 分钟。
     * 脚本以 JSON 形式输出到 stdout，直接反序列化后返回。
     *
     * @param req HTTP 请求（从 Session 获取组合 id）
     * @return 风格诊断结果 Map（结构由 Python 脚本定义），失败时返回 {error: "..."}
     */
    @GetMapping("/portfolio-style")
    public Map<String, Object> getPortfolioStyle(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("error", "no portfolio");

        try {
            // 优先在工作目录 script/ 找脚本，找不到则回退到 ../script/
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

    /**
     * 触发量化数据全量刷新（调用 analyze_quant.py --mode all），通过 SSE 实时推送进度。
     * 刷新内容包括：个股 Beta / 波动率 / 估值分位数、组合压测、风险汇总等缓存表。
     *
     * <p>SSE 事件说明：
     * <ul>
     *   <li>{@code status}   — 启动提示，data: {msg}</li>
     *   <li>{@code progress} — 分析进度，data: {current, total, pct, name}</li>
     *   <li>{@code info}     — 阶段信息（含"==="的行），data: {msg}</li>
     *   <li>{@code log}      — 普通日志行，data: {msg}</li>
     *   <li>{@code done}     — 刷新完成，data: {msg}</li>
     *   <li>{@code error}    — 错误，data: {msg}</li>
     * </ul>
     *
     * @param req      HTTP 请求（从 Session 获取组合 id）
     * @param response HTTP 响应（设置 SSE 响应头）
     * @return SseEmitter 流对象
     */
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

                // -u 参数：Python 无缓冲模式，确保输出实时传递给 BufferedReader
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
                        // 尝试解析进度行，匹配 "[n/N pct%] name"
                        Matcher m = PROGRESS_RE.matcher(line);
                        if (m.find()) {
                            Map<String, Object> prog = new LinkedHashMap<>();
                            prog.put("current", Integer.parseInt(m.group(1)));
                            prog.put("total",   Integer.parseInt(m.group(2)));
                            prog.put("pct",     Double.parseDouble(m.group(3)));
                            prog.put("name",    m.group(4).trim());
                            emit(emitter, "progress", prog);
                        } else if (line.contains("===")) {
                            // 阶段分隔线，作为 info 事件推送
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

    // ── 组合优化 ──────────────────────────────────────────────────────────────

    /**
     * 调用 optimizer.py 执行均值-方差组合优化，返回各股最优权重分配建议。
     *
     * @param mode      优化模式：sharpe（最大夏普比）/ minvar（最小方差）等，默认 sharpe
     * @param maxWeight 单只股票最大权重上限（0.0-1.0），默认 0.30（即 30%）
     * @param req       HTTP 请求（从 Session 获取组合 id）
     * @return 优化结果 Map（结构由 Python 脚本定义），包含各股建议权重、预期收益、风险等
     */
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

    // ── 持仓上下文摘要（AI 对话系统提示词注入用）────────────────────────────────

    /**
     * 生成持仓上下文摘要，供 AI 对话的系统提示词注入使用。
     * 包含组合总市值（CNY）、前5大持仓（权重、盈亏）、加权 Beta、
     * 市场分配（A股/港股/美股/其他）、主导投资风格。
     *
     * @param req HTTP 请求（从 Session 获取组合 id）
     * @return 上下文摘要 Map，包含 totalValue、top5Holdings、weightedBeta、
     *         marketAllocation、dominantStyle（可选）
     */
    @GetMapping("/context-summary")
    public Map<String, Object> contextSummary(HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return Map.of("error", "no portfolio");

        // 加载外汇换算系数（外币→CNY），exchange_rates 存储 1 CNY 等于多少外币
        Map<String, Double> toCny = new HashMap<>(); toCny.put("CNY", 1.0);
        try { jdbc.queryForList("SELECT currency, rate FROM exchange_rates").forEach(r -> {
            String c = (String) r.get("currency"); Number rate = (Number) r.get("rate");
            if (rate != null && rate.doubleValue() > 0) toCny.put(c, 1.0 / rate.doubleValue());
        }); } catch (Exception ignored) {}

        // 查询持仓基本信息及最新收盘价（子查询取最新一条 stock_prices）
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.symbol, s.name, s.market, s.currency, h.total_shares, h.avg_cost, " +
            "  (SELECT sp.close FROM stock_prices sp WHERE sp.stock_id = h.stock_id ORDER BY sp.trade_date DESC LIMIT 1) AS latest_price " +
            "FROM holdings h JOIN stocks s ON s.id = h.stock_id " +
            "WHERE h.portfolio_id = ? AND h.total_shares > 0", portfolioId);

        // 计算各股市值（CNY）和盈亏率
        double totalValue = 0;
        double[][] mv = new double[rows.size()][2]; // [0]=市值, [1]=盈亏率%
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

        // 按市值降序取前5大持仓
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

        // 按大市场分组（A股/港股/美股/其他）计算市值占比
        Map<String, Double> marketMv = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String market = (String) rows.get(i).get("market"); if (market == null) continue;
            String group = (market.equals("SH") || market.equals("SZ")) ? "A股"
                : market.equals("HK") ? "港股" : market.equals("US") ? "美股" : "其他";
            marketMv.merge(group, mv[i][0], Double::sum);
        }
        Map<String, Object> marketAlloc = new LinkedHashMap<>();
        marketMv.forEach((k, v) -> marketAlloc.put(k, tv > 0 ? Math.round(v / tv * 1000.0) / 10.0 : 0.0));

        // 从 portfolio_risk_cache 读取加权 Beta（默认 1.0）
        double weightedBeta = 1.0;
        try {
            Map<String, Object> risk = jdbc.queryForMap(
                "SELECT weighted_beta FROM portfolio_risk_cache WHERE portfolio_id = ?", portfolioId);
            if (risk.get("weighted_beta") != null) weightedBeta = ((Number) risk.get("weighted_beta")).doubleValue();
        } catch (Exception ignored) {}

        // 从 stock_metric_cache 统计持仓中出现最多的投资风格
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

    // ── 持仓与目标标的 30 日 Pearson 相关系数 ───────────────────────────────────

    /**
     * 计算目标股票与当前持仓各股的近 30 个交易日日收益率 Pearson 相关系数，
     * 用于衡量新标的与现有持仓的重叠风险。
     *
     * <p>计算基于对齐的共同交易日序列（至少需要 11 个共同交易日），
     * 结果按相关系数绝对值降序排列。
     *
     * @param symbol 目标股票代码（如 "1.600519"）
     * @param req    HTTP 请求（从 Session 获取组合 id）
     * @return 相关系数列表，每项包含 symbol、name、correlation_30d（-1.0 ~ 1.0）
     */
    @GetMapping("/holdings-correlation")
    public List<Map<String, Object>> holdingsCorrelation(
            @RequestParam String symbol, HttpServletRequest req) {
        long portfolioId = getPortfolioId(req);
        if (portfolioId == 0) return List.of();

        // 查找目标股票 id
        List<Map<String, Object>> targetRows = jdbc.queryForList(
            "SELECT id FROM stocks WHERE symbol = ? LIMIT 1", symbol);
        if (targetRows.isEmpty()) return List.of();
        long targetId = ((Number) targetRows.get(0).get("id")).longValue();

        // 查询持仓中除目标股票外的所有股票
        List<Map<String, Object>> holdingRows = jdbc.queryForList(
            "SELECT h.stock_id, s.symbol, s.name FROM holdings h JOIN stocks s ON s.id = h.stock_id " +
            "WHERE h.portfolio_id = ? AND h.total_shares > 0 AND h.stock_id != ?",
            portfolioId, targetId);
        if (holdingRows.isEmpty()) return List.of();

        // 获取目标股票最近 32 个交易日的收盘价序列
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
        // 按相关系数绝对值降序排列
        result.sort((a, b) -> Double.compare(
            Math.abs((Double) b.get("correlation_30d")),
            Math.abs((Double) a.get("correlation_30d"))));
        return result;
    }

    /**
     * 查询指定股票最近 N 个交易日的收盘价序列。
     *
     * @param stockId 股票 id
     * @param limit   最多取几条（按日期降序）
     * @return 日期字符串（yyyy-MM-dd）→ 收盘价 的 Map（按数据库返回顺序）
     */
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

    /**
     * 在两只股票的对齐共同交易日上计算日收益率的 Pearson 相关系数。
     * 需要至少 11 个共同交易日才能计算，否则返回 NaN。
     *
     * @param a 股票 A 的日期→收盘价 Map
     * @param b 股票 B 的日期→收盘价 Map
     * @return Pearson 相关系数（-1.0 ~ 1.0），数据不足时返回 Double.NaN
     */
    private double pearsonOnAligned(Map<String, Double> a, Map<String, Double> b) {
        // 取两只股票共同交易日，并按日期升序排列
        List<String> dates = new ArrayList<>(a.keySet());
        dates.retainAll(b.keySet());
        Collections.sort(dates);
        if (dates.size() < 11) return Double.NaN;
        int n = dates.size() - 1;
        // 计算相邻日的日收益率序列
        double[] ra = new double[n], rb = new double[n];
        for (int i = 1; i < dates.size(); i++) {
            double pa = a.get(dates.get(i - 1)), ca = a.get(dates.get(i));
            double pb = b.get(dates.get(i - 1)), cb = b.get(dates.get(i));
            ra[i - 1] = pa > 0 ? (ca - pa) / pa : 0;
            rb[i - 1] = pb > 0 ? (cb - pb) / pb : 0;
        }
        // Pearson 公式：Σ(ex·ey) / sqrt(Σex² · Σey²)，ex/ey 为去均值后的偏差
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

    /**
     * 从 Session 获取当前用户的活跃组合 id。
     *
     * @param req HTTP 请求
     * @return 组合 id，未登录或无组合时返回 0
     */
    private long getPortfolioId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return 0;
        Object pid = session.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    /**
     * 向指定 SseEmitter 发送一个 SSE 事件帧。
     *
     * @param emitter 目标 SSE 流
     * @param event   事件名称
     * @param data    事件数据对象，会被 Jackson 序列化为 JSON
     */
    private void emit(SseEmitter emitter, String event, Object data) {
        try {
            String jsonStr = json.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event).data(jsonStr));
        } catch (Exception e) {
            System.err.println("[QuantSSE] emit failed event=" + event + " error=" + e.getMessage());
        }
    }
}
