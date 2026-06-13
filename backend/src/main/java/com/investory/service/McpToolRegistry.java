package com.investory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investory.dao.McpTokenDao;
import com.investory.server.AppContext;
import com.investory.server.ConfigLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MCP 工具注册表 + 执行器。
 */
public class McpToolRegistry {

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpConfirmStore confirmStore;
    private final McpTokenDao tokenDao;

    private HttpClient http;
    private String apiBase;
    private final List<Tool> tools = new ArrayList<>();
    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public McpToolRegistry() {
        this.confirmStore = AppContext.get(McpConfirmStore.class);
        this.tokenDao = AppContext.get(McpTokenDao.class);
    }

    public record Tool(String name, String description, ObjectNode inputSchema, BiFn handler) {}

    @FunctionalInterface
    public interface BiFn {
        Object apply(JsonNode args, String token) throws Exception;
    }

    public List<Tool> tools() { return tools; }
    public Tool get(String name) { return byName.get(name); }

    public void init() throws Exception {
        int serverPort = ConfigLoader.getInt("server.port", 8443);
        String contextPath = ConfigLoader.get("server.servlet.context-path", "");
        this.http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
        this.apiBase = "http://localhost:" + serverPort + contextPath + "/api";
        registerTools();
    }

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

    private void registerTools() {
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

        add("get_portfolio", "获取当前组合的持仓与盈亏概要。", empty(),
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
        add("get_dashboard", "获取仪表盘聚合数据。", empty(),
                (a, tok) -> apiGet(tok, "/dashboard"));
        add("get_cash", "获取当前组合各币种现金余额。", empty(),
                (a, tok) -> apiGet(tok, "/cash"));
        add("get_closed_positions", "获取已平仓头寸及已实现盈亏。", empty(),
                (a, tok) -> apiGet(tok, "/closed-positions"));
        add("get_pnl_history", "获取组合累计收益走势。",
                prop(obj(), "days", "integer", "回看天数，默认90", false),
                (a, tok) -> apiGet(tok, "/chart?type=cumulative_return&days=" + argInt(a, "days", 90)));
        add("list_portfolios", "列出当前用户的所有投资组合。", empty(),
                (a, tok) -> apiGet(tok, "/portfolios"));

        add("search_stocks", "按名称/代码模糊搜索股票。",
                prop(obj(), "query", "string", "搜索关键词", true),
                (a, tok) -> apiGet(tok, "/stock/search?q=" + enc(argStr(a, "query", ""))));
        add("get_stock_quote", "获取个股当前价格。",
                prop(obj(), "symbol", "string", "股票代码", true),
                (a, tok) -> apiGet(tok, "/quote/" + enc(argStr(a, "symbol", ""))));
        add("get_stock_detail", "获取个股详情。",
                prop(obj(), "symbol", "string", "股票代码", true),
                (a, tok) -> apiGet(tok, "/stocks/" + enc(argStr(a, "symbol", ""))));
        add("get_stock_price_history", "获取个股历史 K 线。",
                prop(prop(obj(), "symbol", "string", "股票代码", true), "days", "integer", "天数，默认60", false),
                (a, tok) -> apiGet(tok, "/chart?type=price&symbol=" + enc(argStr(a, "symbol", "")) + "&days=" + argInt(a, "days", 60)));

        add("get_factor_scores", "批量获取多因子综合评分。",
                prop(obj(), "symbols", "string", "逗号分隔代码", true),
                (a, tok) -> apiGet(tok, "/stocksage/factor-scores?symbols=" + enc(argStr(a, "symbols", ""))));
        add("get_factor_breakdown", "单股逐因子拆解。",
                prop(obj(), "symbol", "string", "股票代码", true),
                (a, tok) -> apiGet(tok, "/stocksage/factor-breakdown/" + enc(argStr(a, "symbol", ""))));
        add("get_market_regime", "获取当前市场环境及评分。", empty(),
                (a, tok) -> apiGet(tok, "/stocksage/regime"));
        add("get_daily_picks", "获取今日智能选股推荐。",
                prop(prop(obj(), "strategy", "string", "main/chip/hot/golden_cross", false), "limit", "integer", "数量，默认10", false),
                (a, tok) -> apiGet(tok, "/stocksage/daily-picks?type=" + enc(argStr(a, "strategy", "main")) + "&limit=" + argInt(a, "limit", 10)));
        add("get_portfolio_analysis", "运行多因子组合分析。", empty(),
                (a, tok) -> apiSend(tok, "POST", "/stocksage/portfolio-analysis", "", "{}"));

        add("get_portfolio_style", "组合风格诊断。", empty(),
                (a, tok) -> apiGet(tok, "/quant/portfolio-style"));
        add("get_holdings_metrics", "各持仓量化指标。", empty(),
                (a, tok) -> apiGet(tok, "/quant/holdings-metrics"));
        add("compute_correlation", "持仓相关性矩阵。",
                prop(obj(), "symbol", "string", "逗号分隔代码", false),
                (a, tok) -> apiGet(tok, "/quant/holdings-correlation?symbol=" + enc(argStr(a, "symbol", ""))));
        add("optimize_portfolio", "均值-方差组合优化。",
                prop(prop(obj(), "mode", "string", "sharpe/minvar/riskparity", false), "max_weight", "number", "单票最大权重", false),
                (a, tok) -> apiGet(tok, "/quant/optimize?mode=" + enc(argStr(a, "mode", "sharpe")) + "&maxWeight=" + argNum(a, "max_weight", 0.30)));

        add("get_global_indices", "全球主要股指行情。", empty(),
                (a, tok) -> apiGet(tok, "/market/indices"));
        add("get_world_news", "今日全球财经要闻。",
                prop(obj(), "limit", "integer", "条数，默认10", false),
                (a, tok) -> apiGet(tok, "/market/news?limit=" + argInt(a, "limit", 10)));

        add("list_strategies", "列出用户已保存的策略。", empty(),
                (a, tok) -> apiGet(tok, "/backtest/strategies"));
        add("get_strategy", "获取单个策略详情。",
                prop(obj(), "id", "integer", "策略ID", true),
                (a, tok) -> apiGet(tok, "/backtest/strategies/" + argInt(a, "id", 0)));
        add("get_backtests", "获取最近的历史回测记录。", empty(),
                (a, tok) -> apiGet(tok, "/backtest/history"));
        add("get_backtest", "获取单次回测结果。",
                prop(obj(), "id", "integer", "回测结果ID", true),
                (a, tok) -> apiGet(tok, "/backtest/" + argInt(a, "id", 0)));

        add("run_backtest", "运行一次回测。",
                runBacktestSchema(),
                (a, tok) -> {
                    String jsonBody = buildRunBacktestBody(a);
                    return apiSend(tok, "POST", "/backtest/start", null, jsonBody);
                });

        add("generate_strategy", "生成并保存策略。",
                generateStrategySchema(),
                (a, tok) -> {
                    String jsonBody = buildGenerateStrategyBody(a);
                    return apiSend(tok, "POST", "/backtest/strategies", null, jsonBody);
                });

        add("analyze_backtest", "分析回测结果。",
                prop(obj(), "id", "integer", "回测结果ID", true),
                (a, tok) -> apiGet(tok, "/backtest/" + argInt(a, "id", 0)));

        add("get_stock_report", "生成单只A股分析报告。",
                prop(obj(), "symbol", "string", "股票代码", true),
                (a, tok) -> apiGet(tok, "/stocksage/stock-analysis/" + enc(argStr(a, "symbol", ""))));
        add("get_portfolio_report", "生成持仓组合分析报告。", empty(),
                (a, tok) -> apiSend(tok, "POST", "/stocksage/portfolio-analysis", null, "{}"));
        add("get_daily_picks_report", "生成选股报告。",
                prop(prop(obj(), "strategy", "string", "main/chip/hot/golden_cross", false), "limit", "integer", "数量", false),
                (a, tok) -> apiGet(tok, "/stocksage/daily-picks?type=" + enc(argStr(a, "strategy", "main")) + "&limit=" + argInt(a, "limit", 10)));

        add("compute_sector_breakdown", "分析持仓行业分布。", empty(),
                (a, tok) -> apiGet(tok, "/dashboard"));

        add("benchmark_compare", "对比组合与基准指数。",
                prop(prop(obj(), "benchmark", "string", "基准代码", false), "days", "integer", "对比天数", false),
                (a, tok) -> apiGet(tok, "/chart?type=cumulative_return&days=" + argInt(a, "days", 252)));

        add("consult_kb", "查询投资知识库。",
                prop(obj(), "topic", "string", "查询主题", true),
                (a, tok) -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("note", "知识库查询已集成到AI对话系统。请直接描述你的分析需求，AI会自动查阅相关知识。");
                    return out;
                });

        add("web_search", "联网搜索新闻。",
                prop(prop(obj(), "query", "string", "搜索关键词", true), "count", "integer", "结果数量", false),
                (a, tok) -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("note", "联网搜索功能需要在AI对话中通过 --web-search 参数启用。请在AI设置中开启联网搜索。");
                    return out;
                });

        add("manage_memory", "管理用户长期记忆。",
                manageMemorySchema(),
                (a, tok) -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("note", "记忆管理功能需要在AI对话中通过 manage_memory 工具调用。当前通过MCP暂不支持直接操作记忆。");
                    return out;
                });

        add("ask_user", "需要用户选择时调用。",
                askUserSchema(),
                (a, tok) -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("note", "ask_user 是AI对话中的交互工具，需要在AI对话流中使用。当前通过MCP暂不支持直接触发用户选择。");
                    return out;
                });

        add("get_transactions", "获取近期交易与股息流水。",
                prop(obj(), "limit", "integer", "数量上限，默认50", false),
                (a, tok) -> apiGet(tok, "/transactions"));
        add("create_transaction", "创建一笔交易（两步确认）。",
                txCreateSchema(),
                (a, tok) -> stageTx(tok, "POST", "/transactions", a, "创建交易"));
        add("update_transaction", "修改一笔交易（两步确认）。",
                prop(txCreateSchema(), "id", "integer", "交易记录ID", true),
                (a, tok) -> stageTx(tok, "PUT", "/transactions/" + argInt(a, "id", 0), a, "编辑交易 #" + argInt(a, "id", 0)));
        add("delete_transaction", "删除一笔交易（两步确认）。",
                prop(obj(), "id", "integer", "交易记录ID", true),
                (a, tok) -> confirmStore.stage(userIdOf(tok), "DELETE", "/transactions/" + argInt(a, "id", 0), null, null,
                        "删除交易 #" + argInt(a, "id", 0)));

        add("get_watchlist", "获取自选股列表。", empty(),
                (a, tok) -> apiGet(tok, "/watchlist"));
        add("add_to_watchlist", "添加股票到自选（两步确认）。",
                prop(prop(obj(), "stockId", "integer", "股票ID", true), "name", "string", "股票名", false),
                (a, tok) -> confirmStore.stage(userIdOf(tok), "POST", "/watchlist",
                        "stockId=" + argInt(a, "stockId", 0), null,
                        "添加 " + argStr(a, "name", "stockId=" + argInt(a, "stockId", 0)) + " 到自选"));
        add("remove_from_watchlist", "从自选移除股票（两步确认）。",
                prop(prop(obj(), "stockId", "integer", "股票ID", true), "name", "string", "股票名", false),
                (a, tok) -> confirmStore.stage(userIdOf(tok), "DELETE", "/watchlist/" + argInt(a, "stockId", 0), null, null,
                        "从自选移除 " + argStr(a, "name", "stockId=" + argInt(a, "stockId", 0))));
    }

    private ObjectNode txCreateSchema() {
        ObjectNode s = obj();
        prop(s, "stockId", "integer", "股票ID（TRANSFER 类可传0）", true);
        prop(s, "type", "string", "BUY/SELL/TRANSFER_IN/TRANSFER_OUT", true);
        prop(s, "shares", "number", "股数（划转类为金额）", true);
        prop(s, "price", "number", "每股价格", false);
        prop(s, "fee", "number", "手续费，默认0", false);
        prop(s, "tradeDate", "string", "交易日期 YYYY-MM-DD", false);
        prop(s, "currency", "string", "币种 CNY/HKD/USD", false);
        prop(s, "note", "string", "备注", false);
        return s;
    }

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

    private JsonNode execWrite(String token, McpConfirmStore.Pending p) throws Exception {
        return apiSend(token, p.method(), p.path(), p.form(), p.json());
    }

    private long userIdOf(String token) {
        McpTokenDao.TokenInfo info = tokenDao.resolveToken(token);
        return info != null ? info.userId() : 0;
    }

    private ObjectNode runBacktestSchema() {
        ObjectNode s = obj();
        prop(s, "strategy_id", "integer", "已保存策略的ID", false);
        prop(s, "code", "string", "未保存策略时直接传入的完整Python代码", false);
        prop(s, "stocks", "array", "回测标的代码列表", false);
        prop(s, "start_date", "string", "回测起始日期", false);
        prop(s, "end_date", "string", "回测结束日期", false);
        prop(s, "initial_capital", "number", "初始资金", false);
        prop(s, "commission_pct", "number", "手续费率(小数)", false);
        return s;
    }

    private String buildRunBacktestBody(JsonNode a) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\":\"").append(escJson(argStr(a, "name", "未命名策略"))).append("\",");
        sb.append("\"strategyType\":\"advanced\",");
        sb.append("\"strategy\":{");
        String code = argStr(a, "code", "");
        if (!code.isEmpty()) {
            sb.append("\"code\":\"").append(escJson(code)).append("\"");
        }
        sb.append("},\"config\":{");
        sb.append("\"startDate\":\"").append(argStr(a, "start_date", "")).append("\",");
        sb.append("\"endDate\":\"").append(argStr(a, "end_date", "")).append("\",");
        sb.append("\"initialCapital\":").append(argNum(a, "initial_capital", 100000)).append(",");
        sb.append("\"commissionPct\":").append(argNum(a, "commission_pct", 0.008)).append(",");
        sb.append("\"slippagePct\":0.001");
        sb.append("}}");
        return sb.toString();
    }

    private ObjectNode generateStrategySchema() {
        ObjectNode s = obj();
        prop(s, "name", "string", "策略名称", true);
        prop(s, "description", "string", "一句话描述策略思路", true);
        prop(s, "code", "string", "完整Python代码", true);
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

    private ObjectNode manageMemorySchema() {
        ObjectNode s = obj();
        prop(s, "action", "string", "remember 或 forget", true);
        prop(s, "fact", "string", "要记住的事实", false);
        prop(s, "keyword", "string", "要删除的关键词", false);
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
