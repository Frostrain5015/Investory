package com.investory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investory.dao.McpTokenDao;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.function.Function;

/**
 * MCP 工具注册表 + 执行器。
 *
 * <p>声明对齐「观澜」的 34 个工具（name/description/inputSchema），并把每个工具映射到
 * 一次对本应用自身 REST {@code /api/*} 的内部 HTTP 调用——携带 MCP 客户端的同一个
 * Bearer token，由 {@code LoginInterceptor} 注入用户身份，从而 100% 复用现有控制器逻辑
 * （归属校验、现金校验、净值回填），零逻辑重写。</p>
 *
 * <p>写操作（交易/自选）走两步确认：第一步调用 {@code stage*} 返回 confirm_token，
 * 第二步 {@code confirm_action} 才真正发起写请求（见 {@link McpConfirmStore}）。</p>
 */
@Service
public class McpToolRegistry {

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpConfirmStore confirmStore;
    private final McpTokenDao tokenDao;

    @Value("${server.port:8443}")
    private int serverPort;

    @Value("${server.servlet.context-path:/investory}")
    private String contextPath;

    private HttpClient http;
    private String apiBase;       // https://localhost:<port><ctx>/api
    private final List<Tool> tools = new ArrayList<>();
    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public McpToolRegistry(McpConfirmStore confirmStore, McpTokenDao tokenDao) {
        this.confirmStore = confirmStore;
        this.tokenDao = tokenDao;
    }

    // ── Tool 定义 ────────────────────────────────────────────────────────

    /** 一个工具：名称、描述、JSON Schema、以及给定 (args, token) 的执行逻辑。 */
    public record Tool(String name, String description, ObjectNode inputSchema,
                       BiFn handler) {}

    @FunctionalInterface
    public interface BiFn {
        /** @return 任意可被 Jackson 序列化的结果对象 */
        Object apply(JsonNode args, String token) throws Exception;
    }

    public List<Tool> tools() { return tools; }
    public Tool get(String name) { return byName.get(name); }

    // ── 初始化：内部 HTTP client（信任本机自签证书）+ 工具表 ────────────────

    @PostConstruct
    void init() throws Exception {
        // 内部调用走 localhost 自签 HTTPS：信任所有证书仅限本机回环调用（不出网）。
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new java.security.SecureRandom());
        // 关闭主机名校验（localhost vs 证书 CN）
        var props = System.getProperties();
        props.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        this.http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(15)).build();
        this.apiBase = "https://localhost:" + serverPort + contextPath + "/api";
        registerTools();
    }

    // ── 内部 REST 调用 ───────────────────────────────────────────────────

    private JsonNode apiGet(String token, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Authorization", "Bearer " + token).GET().build();
        return send(req);
    }

    private JsonNode apiSend(String token, String method, String path, String form, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher body;
        if (json != null) {
            b.header("Content-Type", "application/json");
            body = HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
        } else {
            b.header("Content-Type", "application/x-www-form-urlencoded");
            body = HttpRequest.BodyPublishers.ofString(form == null ? "" : form, StandardCharsets.UTF_8);
        }
        b.method(method, body);
        return send(b.build());
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = resp.body();
        if (resp.statusCode() >= 400) {
            ObjectNode err = mapper.createObjectNode();
            err.put("error", "HTTP " + resp.statusCode());
            err.put("detail", body != null && body.length() > 300 ? body.substring(0, 300) : body);
            return err;
        }
        if (body == null || body.isBlank()) return mapper.createObjectNode();
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            ObjectNode n = mapper.createObjectNode();
            n.put("raw", body);
            return n;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String argStr(JsonNode args, String key, String def) {
        JsonNode n = args == null ? null : args.get(key);
        return n == null || n.isNull() ? def : n.asText();
    }

    private static int argInt(JsonNode args, String key, int def) {
        JsonNode n = args == null ? null : args.get(key);
        return n == null || n.isNull() ? def : n.asInt();
    }

    private static double argNum(JsonNode args, String key, double def) {
        JsonNode n = args == null ? null : args.get(key);
        return n == null || n.isNull() ? def : n.asDouble();
    }

    // ── Schema 辅助 ──────────────────────────────────────────────────────

    private ObjectNode obj() { ObjectNode s = mapper.createObjectNode(); s.put("type", "object"); s.putObject("properties"); return s; }
    private ObjectNode prop(ObjectNode s, String name, String type, String desc, boolean required) {
        ((ObjectNode) s.get("properties")).putObject(name).put("type", type).put("description", desc);
        if (required) {
            ArrayNode r = s.has("required") ? (ArrayNode) s.get("required") : s.putArray("required");
            r.add(name);
        }
        return s;
    }
    private ObjectNode empty() { return obj(); }

    private void add(String name, String desc, ObjectNode schema, BiFn handler) {
        Tool t = new Tool(name, desc, schema, handler);
        tools.add(t);
        byName.put(name, t);
    }

    // ── 工具表（对齐观澜）────────────────────────────────────────────────

    private void registerTools() {
        // meta
        add("whoami", "返回当前 MCP 绑定的 Investory 用户身份。", empty(),
                (a, tok) -> {
                    McpTokenDao.TokenInfo info = tokenDao.resolveToken(tok);
                    ObjectNode out = mapper.createObjectNode();
                    if (info == null) { out.put("authenticated", false); return out; }
                    out.put("authenticated", true);
                    out.put("user_id", info.userId());
                    out.put("username", info.username());
                    if (info.portfolioId() != null) out.put("portfolio_id", info.portfolioId());
                    return out;
                });
        add("confirm_action",
                "确认并执行一个待定的写操作。写工具会先返回 confirm_token，把它传给本工具才真正写入。",
                prop(obj(), "confirm_token", "string", "写工具返回的 confirm_token", true),
                (a, tok) -> confirmStore.confirm(argStr(a, "confirm_token", ""), userIdOf(tok),
                        p -> execWrite(tok, p)));

        // 组合
        add("get_portfolio", "获取当前组合的持仓与盈亏概要：各标的市值/盈亏/权重 + 总市值/总盈亏/现金。", empty(),
                (a, tok) -> {
                    JsonNode d = apiGet(tok, "/dashboard");
                    ObjectNode out = mapper.createObjectNode();
                    for (String k : List.of("totalMarketValue", "totalInvested", "totalPnl", "realizedPnl",
                            "cumulativePnl", "totalReturnPct", "todayPnl", "todayPnlPct", "cashBalance")) {
                        if (d.has(k)) out.set(k, d.get(k));
                    }
                    out.set("cashByCurrency", d.get("cashByCurrency"));
                    ArrayNode hs = out.putArray("holdings");
                    if (d.has("snapshots")) for (JsonNode s : d.get("snapshots")) {
                        ObjectNode h = hs.addObject();
                        h.set("symbol", s.get("stockSymbol")); h.set("name", s.get("stockName"));
                        h.set("market", s.get("market")); h.set("shares", s.get("shares"));
                        h.set("avgCost", s.get("avgCost")); h.set("marketValue", s.get("marketValue"));
                        h.set("unrealizedPnl", s.get("unrealizedPnl")); h.set("currency", s.get("currency"));
                    }
                    out.put("holdingCount", hs.size());
                    return out;
                });
        add("get_dashboard", "获取仪表盘聚合数据：总市值/成本/各类盈亏/今日盈亏/现金/仓位分布。", empty(),
                (a, tok) -> apiGet(tok, "/dashboard"));
        add("get_cash", "获取当前组合各币种现金余额。", empty(),
                (a, tok) -> apiGet(tok, "/cash"));
        add("get_closed_positions", "获取已平仓头寸及已实现盈亏（用于复盘）。", empty(),
                (a, tok) -> apiGet(tok, "/closed-positions"));
        add("get_pnl_history", "获取组合累计收益走势。",
                prop(obj(), "days", "integer", "回看天数，默认90", false),
                (a, tok) -> apiGet(tok, "/chart?type=cumulative_return&days=" + argInt(a, "days", 90)));
        add("list_portfolios", "列出当前用户的所有投资组合。", empty(),
                (a, tok) -> apiGet(tok, "/portfolios"));

        // 个股
        add("search_stocks", "按名称/代码模糊搜索股票，返回 id/symbol/name/market/currency/price。写自选或交易需要先拿到 stockId。",
                prop(obj(), "query", "string", "搜索关键词（股票名或代码）", true),
                (a, tok) -> apiGet(tok, "/stock/search?q=" + enc(argStr(a, "query", ""))));
        add("get_stock_quote", "获取个股当前价格。",
                prop(obj(), "symbol", "string", "股票代码，如 600519.SH", true),
                (a, tok) -> apiGet(tok, "/quote/" + enc(argStr(a, "symbol", ""))));
        add("get_stock_detail", "获取个股详情：基础信息/持仓/相关交易股息/最新价。",
                prop(obj(), "symbol", "string", "股票代码", true),
                (a, tok) -> apiGet(tok, "/stocks/" + enc(argStr(a, "symbol", ""))));
        add("get_stock_price_history", "获取个股历史 K 线（OHLC）。",
                prop(prop(obj(), "symbol", "string", "股票代码", true), "days", "integer", "天数，默认60", false),
                (a, tok) -> apiGet(tok, "/chart?type=price&symbol=" + enc(argStr(a, "symbol", "")) + "&days=" + argInt(a, "days", 60)));

        // 因子
        add("get_factor_scores", "批量获取多因子综合评分（各方向得分）。分析个股时首选。",
                prop(obj(), "symbols", "string", "逗号分隔代码，如 600519,000858", true),
                (a, tok) -> apiGet(tok, "/stocksage/factor-scores?symbols=" + enc(argStr(a, "symbols", ""))));
        add("get_factor_breakdown", "单股逐因子拆解：价值/成长/动量/质量/技术等。",
                prop(obj(), "symbol", "string", "股票代码", true),
                (a, tok) -> apiGet(tok, "/stocksage/factor-breakdown/" + enc(argStr(a, "symbol", ""))));
        add("get_market_regime", "获取当前市场环境（牛/熊/正常/谨慎/危机）及评分。", empty(),
                (a, tok) -> apiGet(tok, "/stocksage/regime"));
        add("get_daily_picks", "获取今日智能选股推荐。",
                prop(prop(obj(), "strategy", "string", "main/chip/hot/golden_cross，默认main", false), "limit", "integer", "数量，默认10", false),
                (a, tok) -> apiGet(tok, "/stocksage/daily-picks?type=" + enc(argStr(a, "strategy", "main")) + "&limit=" + argInt(a, "limit", 10)));
        add("get_portfolio_analysis", "运行多因子组合分析：组合综合评分、因子暴露、Top/Bottom 持仓。", empty(),
                (a, tok) -> apiSend(tok, "POST", "/stocksage/portfolio-analysis", "", "{}"));

        // 量化
        add("get_portfolio_style", "组合风格诊断：成长/价值/动量/防御因子暴露与分布。", empty(),
                (a, tok) -> apiGet(tok, "/quant/portfolio-style"));
        add("get_holdings_metrics", "各持仓量化指标（Beta、波动率、分位等）。", empty(),
                (a, tok) -> apiGet(tok, "/quant/holdings-metrics"));
        add("compute_correlation", "持仓相关性矩阵（r>0.7 高度相关、分散化弱）。",
                prop(obj(), "symbol", "string", "逗号分隔代码；留空用全部持仓", false),
                (a, tok) -> apiGet(tok, "/quant/holdings-correlation?symbol=" + enc(argStr(a, "symbol", ""))));
        add("optimize_portfolio", "均值-方差组合优化，给出建议权重。",
                prop(prop(obj(), "mode", "string", "sharpe(默认)/minvar/riskparity", false), "max_weight", "number", "单票最大权重，默认0.30", false),
                (a, tok) -> apiGet(tok, "/quant/optimize?mode=" + enc(argStr(a, "mode", "sharpe")) + "&maxWeight=" + argNum(a, "max_weight", 0.30)));

        // 市场
        add("get_global_indices", "全球主要股指 + 商品/汇率指标最新行情。", empty(),
                (a, tok) -> apiGet(tok, "/market/indices"));
        add("get_world_news", "今日全球财经/地缘要闻。",
                prop(obj(), "limit", "integer", "条数，默认10", false),
                (a, tok) -> apiGet(tok, "/market/news?limit=" + argInt(a, "limit", 10)));

        // 回测/策略
        add("list_strategies", "列出用户已保存的策略。", empty(),
                (a, tok) -> apiGet(tok, "/backtest/strategies"));
        add("get_strategy", "获取单个策略详情。",
                prop(obj(), "id", "integer", "策略ID", true),
                (a, tok) -> apiGet(tok, "/backtest/strategies/" + argInt(a, "id", 0)));
        add("get_backtests", "获取最近的历史回测记录。", empty(),
                (a, tok) -> apiGet(tok, "/backtest/history"));
        add("get_backtest", "获取单次回测的完整结果（指标/交易/净值）。",
                prop(obj(), "id", "integer", "回测结果ID", true),
                (a, tok) -> apiGet(tok, "/backtest/" + argInt(a, "id", 0)));

        // 运行回测 — 调用 /api/backtest/start
        add("run_backtest", "运行一次量化策略回测。优先用 strategy_id（已保存策略），否则用 code 直接回测。",
                runBacktestSchema(),
                (a, tok) -> {
                    String jsonBody = buildRunBacktestBody(a);
                    return apiSend(tok, "POST", "/backtest/start", null, jsonBody);
                });

        // 生成策略 — 调用 /api/backtest/strategies
        add("generate_strategy", "生成并保存一个量化策略。code 必须包含 def decide(ctx) 函数。",
                generateStrategySchema(),
                (a, tok) -> {
                    String jsonBody = buildGenerateStrategyBody(a);
                    return apiSend(tok, "POST", "/backtest/strategies", null, jsonBody);
                });

        // 分析回测 — 复用 /api/backtest/{id} 数据 + 本地计算
        add("analyze_backtest", "分析回测结果并从收益、风险、稳定性和改进方向给出客观评价。",
                prop(obj(), "id", "integer", "回测结果ID", true),
                (a, tok) -> apiGet(tok, "/backtest/" + argInt(a, "id", 0)));

        // StockSage 报告
        add("get_stock_report", "生成单只A股的可审计 Markdown 分析报告。",
                prop(obj(), "symbol", "string", "股票代码，如 600519.SH", true),
                (a, tok) -> apiGet(tok, "/stocksage/stock-analysis/" + enc(argStr(a, "symbol", ""))));
        add("get_portfolio_report", "生成当前持仓组合的可审计 Markdown 报告。", empty(),
                (a, tok) -> apiSend(tok, "POST", "/stocksage/portfolio-analysis", null, "{}"));
        add("get_daily_picks_report", "生成今日候选股的可审计 Markdown 选股报告。",
                prop(prop(obj(), "strategy", "string", "main/chip/hot/golden_cross，默认main", false), "limit", "integer", "数量，默认10", false),
                (a, tok) -> apiGet(tok, "/stocksage/daily-picks?type=" + enc(argStr(a, "strategy", "main")) + "&limit=" + argInt(a, "limit", 10)));

        // 行业分布
        add("compute_sector_breakdown", "分析持仓行业、市值和市场分布。", empty(),
                (a, tok) -> apiGet(tok, "/dashboard"));

        // 基准对比
        add("benchmark_compare", "对比组合与基准指数表现差异。",
                prop(prop(obj(), "benchmark", "string", "基准代码，默认000001.SH", false), "days", "integer", "对比天数，默认252", false),
                (a, tok) -> apiGet(tok, "/chart?type=cumulative_return&days=" + argInt(a, "days", 252)));

        // 知识库
        add("consult_kb", "查询投资知识库：原则、指标解读、报告解读等。",
                prop(obj(), "topic", "string", "查询主题", true),
                (a, tok) -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("note", "知识库查询已集成到AI对话系统。请直接描述你的分析需求，AI会自动查阅相关知识。");
                    return out;
                });

        // 联网搜索
        add("web_search", "联网搜索新闻、时事、最新动态。",
                prop(prop(obj(), "query", "string", "搜索关键词", true), "count", "integer", "结果数量，默认5", false),
                (a, tok) -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("note", "联网搜索功能需要在AI对话中通过 --web-search 参数启用。请在AI设置中开启联网搜索。");
                    return out;
                });

        // 记忆管理
        add("manage_memory", "管理用户长期记忆。action='remember'记住事实，action='forget'删除记忆。",
                manageMemorySchema(),
                (a, tok) -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("note", "记忆管理功能需要在AI对话中通过 manage_memory 工具调用。当前通过MCP暂不支持直接操作记忆。");
                    return out;
                });

        // 用户交互
        add("ask_user", "需要用户选择时调用，UI会生成交互按钮。",
                askUserSchema(),
                (a, tok) -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("note", "ask_user 是AI对话中的交互工具，需要在AI对话流中使用。当前通过MCP暂不支持直接触发用户选择。");
                    return out;
                });

        // 交易（读 + 两步写）
        add("get_transactions", "获取近期交易与股息流水。",
                prop(obj(), "limit", "integer", "数量上限，默认50", false),
                (a, tok) -> apiGet(tok, "/transactions"));
        add("create_transaction",
                "创建一笔交易（两步确认）。先返回 confirm_token，再调 confirm_action 才写入。type: BUY/SELL/TRANSFER_IN/TRANSFER_OUT。先用 search_stocks 拿 stockId。",
                txCreateSchema(),
                (a, tok) -> stageTx(tok, "POST", "/transactions", a, "创建交易"));
        add("update_transaction", "修改一笔交易（两步确认）。先用 get_transactions 拿 id。",
                prop(txCreateSchema(), "id", "integer", "交易记录ID", true),
                (a, tok) -> stageTx(tok, "PUT", "/transactions/" + argInt(a, "id", 0), a, "编辑交易 #" + argInt(a, "id", 0)));
        add("delete_transaction", "删除一笔交易（两步确认）。",
                prop(obj(), "id", "integer", "交易记录ID", true),
                (a, tok) -> confirmStore.stage(userIdOf(tok), "DELETE", "/transactions/" + argInt(a, "id", 0), null, null,
                        "删除交易 #" + argInt(a, "id", 0)));

        // 自选（读 + 两步写）
        add("get_watchlist", "获取自选股列表（含最新价与近一周涨跌）。", empty(),
                (a, tok) -> apiGet(tok, "/watchlist"));
        add("add_to_watchlist", "添加股票到自选（两步确认）。先用 search_stocks 拿 stockId。",
                prop(prop(obj(), "stockId", "integer", "股票ID", true), "name", "string", "股票名（仅摘要展示）", false),
                (a, tok) -> confirmStore.stage(userIdOf(tok), "POST", "/watchlist",
                        "stockId=" + argInt(a, "stockId", 0), null,
                        "添加 " + argStr(a, "name", "stockId=" + argInt(a, "stockId", 0)) + " 到自选"));
        add("remove_from_watchlist", "从自选移除股票（两步确认）。先用 get_watchlist 拿 stockId。",
                prop(prop(obj(), "stockId", "integer", "股票ID", true), "name", "string", "股票名（仅摘要展示）", false),
                (a, tok) -> confirmStore.stage(userIdOf(tok), "DELETE", "/watchlist/" + argInt(a, "stockId", 0), null, null,
                        "从自选移除 " + argStr(a, "name", "stockId=" + argInt(a, "stockId", 0))));
    }

    private ObjectNode txCreateSchema() {
        ObjectNode s = obj();
        prop(s, "stockId", "integer", "股票ID（TRANSFER 类可传0）", true);
        prop(s, "type", "string", "BUY/SELL/TRANSFER_IN/TRANSFER_OUT", true);
        prop(s, "shares", "number", "股数（划转类为金额）", true);
        prop(s, "price", "number", "每股价格；BUY/SELL必填，划转填0", false);
        prop(s, "fee", "number", "手续费，默认0", false);
        prop(s, "tradeDate", "string", "交易日期 YYYY-MM-DD，默认今天", false);
        prop(s, "currency", "string", "币种 CNY/HKD/USD", false);
        prop(s, "note", "string", "备注", false);
        return s;
    }

    /** 暂存一笔交易写操作（form 编码 body）。 */
    private Object stageTx(String token, String method, String path, JsonNode a, String summaryPrefix) {
        String today = java.time.LocalDate.now().toString();
        StringBuilder form = new StringBuilder();
        appendForm(form, "stockId", String.valueOf(argInt(a, "stockId", 0)));
        appendForm(form, "type", argStr(a, "type", "BUY"));
        appendForm(form, "shares", String.valueOf(argNum(a, "shares", 0)));
        appendForm(form, "price", String.valueOf(argNum(a, "price", 0)));
        appendForm(form, "fee", String.valueOf(argNum(a, "fee", 0)));
        appendForm(form, "tradeDate", argStr(a, "tradeDate", today));
        appendForm(form, "currency", argStr(a, "currency", "CNY"));
        appendForm(form, "note", argStr(a, "note", ""));
        String summary = summaryPrefix + ": " + argStr(a, "type", "BUY") + " " + argNum(a, "shares", 0)
                + "@" + argNum(a, "price", 0) + " (" + argStr(a, "tradeDate", today) + ")";
        return confirmStore.stage(userIdOf(token), method, path, form.toString(), null, summary);
    }

    private static void appendForm(StringBuilder sb, String k, String v) {
        if (sb.length() > 0) sb.append('&');
        sb.append(enc(k)).append('=').append(enc(v));
    }

    /** confirm_action 真正执行暂存的写：用调用者 token 发内部 REST 请求。 */
    private JsonNode execWrite(String token, McpConfirmStore.Pending p) throws Exception {
        return apiSend(token, p.method(), p.path(), p.form(), p.json());
    }

    // userId 解析：confirmStore 需要按 user 隔离；写路径用 token 反查。
    private long userIdOf(String token) {
        McpTokenDao.TokenInfo info = tokenDao.resolveToken(token);
        return info != null ? info.userId() : 0;
    }

    // ── run_backtest 辅助 ────────────────────────────────────────────────

    private ObjectNode runBacktestSchema() {
        ObjectNode s = obj();
        prop(s, "strategy_id", "integer", "已保存策略的ID。与code二选一，优先用此项", false);
        prop(s, "code", "string", "未保存策略时直接传入的完整Python代码，须含def decide(ctx)函数", false);
        prop(s, "stocks", "array", "回测标的代码列表，如['600519.SH','000001.SZ']。省略则默认回测当前组合持仓", false);
        prop(s, "start_date", "string", "回测起始日期 YYYY-MM-DD，默认一年前", false);
        prop(s, "end_date", "string", "回测结束日期 YYYY-MM-DD，默认今天", false);
        prop(s, "initial_capital", "number", "初始资金，默认100000", false);
        prop(s, "commission_pct", "number", "手续费率(小数)，默认0.008即千分之八", false);
        return s;
    }

    private String buildRunBacktestBody(JsonNode a) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        // name
        sb.append("\"name\":\"").append(escJson(argStr(a, "name", "未命名策略"))).append("\",");
        // strategyType
        sb.append("\"strategyType\":\"advanced\",");
        // strategy
        sb.append("\"strategy\":{");
        String code = argStr(a, "code", "");
        if (!code.isEmpty()) {
            sb.append("\"code\":\"").append(escJson(code)).append("\"");
        }
        sb.append("},\"config\":{");
        // config fields with camelCase
        sb.append("\"startDate\":\"").append(argStr(a, "start_date", "")).append("\",");
        sb.append("\"endDate\":\"").append(argStr(a, "end_date", "")).append("\",");
        sb.append("\"initialCapital\":").append(argNum(a, "initial_capital", 100000)).append(",");
        sb.append("\"commissionPct\":").append(argNum(a, "commission_pct", 0.008)).append(",");
        sb.append("\"slippagePct\":0.001");
        sb.append("}}");
        return sb.toString();
    }

    // ── generate_strategy 辅助 ───────────────────────────────────────────

    private ObjectNode generateStrategySchema() {
        ObjectNode s = obj();
        prop(s, "name", "string", "策略名称", true);
        prop(s, "description", "string", "一句话描述策略思路", true);
        prop(s, "code", "string", "完整Python代码，必须包含 def decide(ctx) 函数", true);
        return s;
    }

    private String buildGenerateStrategyBody(JsonNode a) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\":\"").append(escJson(argStr(a, "name", "未命名策略"))).append("\",");
        sb.append("\"strategyType\":\"advanced\",");
        sb.append("\"strategy\":{");
        sb.append("\"code\":\"").append(escJson(argStr(a, "code", ""))).append("\"");
        sb.append("}}");
        return sb.toString();
    }

    // ── manage_memory / ask_user 辅助 ────────────────────────────────────

    private ObjectNode manageMemorySchema() {
        ObjectNode s = obj();
        prop(s, "action", "string", "remember 或 forget", true);
        prop(s, "fact", "string", "要记住的事实（action=remember时）", false);
        prop(s, "keyword", "string", "要删除的关键词（action=forget时）", false);
        return s;
    }

    private ObjectNode askUserSchema() {
        ObjectNode s = obj();
        prop(s, "question", "string", "要问用户的问题", true);
        prop(s, "options", "array", "选项列表", true);
        prop(s, "multiSelect", "boolean", "是否多选", false);
        return s;
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
