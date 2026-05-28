package com.investory.controller.api;

import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.model.Quote;
import com.investory.service.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * 股票行情与持仓快照控制器
 *
 * <p>负责模块：当前持仓列表查询、历史已平仓头寸查询、
 *   个股详情（含实时报价）、单只股票/全组合行情刷新。
 * <p>API 基础路径：/api
 *
 * <p>所有接口均从 HttpSession 中隐式读取当前用户的 portfolioId，
 * 无需在请求参数中显式传递，确保数据隔离。
 */
@RestController
@RequestMapping("/api")
public class StockController {

    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private HoldingService holdingService;
    @Autowired private TransactionDao transactionDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private RealtimeQuoteService quoteService;
    @Autowired private PortfolioAnalysisService analysisService;

    /**
     * 从 Session 中读取当前用户的组合 ID
     *
     * <p>校验规则：Session 不存在或未设置 portfolioId 时返回 0，
     *   接口方法应判断返回值为 0 时拒绝处理并返回错误响应。
     *
     * @param req HTTP 请求
     * @return 组合 ID，未登录或未选择组合时为 0
     */
    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    /**
     * 查询当前组合的所有持仓快照
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/holdings
     * <p>功能说明：返回当前用户活跃组合中所有持仓的实时聚合快照，
     *   包含持股数量、成本、市值、未实现盈亏等核心字段，供持仓页面展示。
     *
     * <p>请求参数：无
     *
     * <p>响应格式：
     * <pre>
     * {
     *   "snapshots": [ HoldingSnapshot, ... ]  // 持仓快照列表
     * }
     * </pre>
     *
     * @param req HTTP 请求，用于读取 Session 中的 portfolioId
     * @return 包含 snapshots 列表的 Map
     */
    @GetMapping("/holdings")
    public Map<String, Object> holdings(HttpServletRequest req) {
        // 通过 HoldingService 聚合计算当前组合的所有持仓快照
        return Map.of("snapshots", holdingService.getSnapshots(getPortfolioId(req)));
    }

    /**
     * 查询当前组合已平仓头寸列表
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/closed-positions
     * <p>功能说明：返回历史上已全部卖出的头寸，包含已实现盈亏、
     *   买入/卖出均价等字段，用于复盘分析。
     *
     * <p>请求参数：无
     *
     * <p>响应格式：List&lt;Map&gt;，每项包含已平仓股票的盈亏汇总信息
     *
     * @param req HTTP 请求，用于读取 Session 中的 portfolioId
     * @return 已平仓头寸列表
     */
    @GetMapping("/closed-positions")
    public List<Map<String, Object>> closedPositions(HttpServletRequest req) {
        return analysisService.getClosedPositions(getPortfolioId(req));
    }

    /**
     * 查询指定股票的详情页聚合数据
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/stocks/{symbol}
     * <p>功能说明：聚合返回一只股票的基础信息、持仓快照、历史交易记录、
     *   股息记录及实时/最新收盘价，供股票详情页一次性加载所有数据。
     *
     * <p>请求参数：
     *   - symbol（路径参数）：股票代码，例如 600519.SH、0700.HK、AAPL.US
     *
     * <p>响应格式：
     * <pre>
     * {
     *   "stock":        Stock,         // 股票基础信息（名称、市场、货币等）
     *   "holding":      Holding,       // 当前持仓（可为 null 表示未持有）
     *   "transactions": [ Transaction ],// 该股票在本组合的全部交易记录
     *   "dividends":    [ Dividend ],  // 该股票在本组合的全部股息记录
     *   "livePrice":    BigDecimal,    // 实时报价（抓取失败时为 null）
     *   "livePriceTs":  String         // 报价时间戳；无实时价时退化为最新收盘日期
     * }
     * </pre>
     *
     * @param symbol 股票代码（路径参数）
     * @param req    HTTP 请求，用于读取 Session 中的 portfolioId
     * @return 股票详情聚合 Map，或含 error 字段的错误 Map
     */
    @GetMapping("/stocks/{symbol}")
    public Map<String, Object> detail(@PathVariable String symbol, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        // 校验规则：portfolioId 为 0 表示未登录或未选择组合，直接返回错误
        if (pid == 0) return Map.of("error", "No portfolio");
        Stock stock = stockDao.findBySymbol(symbol);
        // 校验规则：股票代码在数据库中不存在时返回 404 语义的错误
        if (stock == null) return Map.of("error", "Stock not found");
        Map<String, Object> r = new LinkedHashMap<>();
        // 组装基础信息：股票对象、持仓记录（可 null）、交易记录、股息记录
        r.put("stock", stock); r.put("holding", holdingDao.findByPortfolioAndStock(pid, stock.getId()));
        r.put("transactions", transactionDao.findByPortfolioAndStock(pid, stock.getId()));
        r.put("dividends", dividendDao.findByPortfolioAndStock(pid, stock.getId()));
        // 尝试从实时报价服务获取当前价格；失败时 q 为 null
        Quote q = quoteService.getQuote(stock);
        // livePrice：优先使用实时价，无实时价时该字段为 null（前端自行处理降级展示）
        r.put("livePrice", q != null ? q.price() : null);
        // livePriceTs：实时报价的抓取时间；无实时价时退化为数据库中最新收盘日期字符串
        r.put("livePriceTs", q != null ? q.fetchedAt().toString() : (stockPriceDao.findLatest(stock.getId()) != null ? stockPriceDao.findLatest(stock.getId()).getTradeDate().toString() : null));
        return r;
    }

    /**
     * 查询指定股票的当前报价（轻量接口）
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/quote/{symbol}
     * <p>功能说明：仅返回最新价格，不包含完整持仓详情，适合轮询刷新场景。
     *   优先返回实时报价，失败时降级为数据库最新收盘价。
     *
     * <p>请求参数：
     *   - symbol（路径参数）：股票代码
     *
     * <p>响应格式：
     * <pre>
     * {
     *   "symbol": String,     // 股票代码
     *   "price":  BigDecimal, // 当前价格（实时或收盘价）
     *   "live":   boolean     // true 表示为实时价，false 表示为数据库收盘价
     * }
     * </pre>
     *
     * @param symbol 股票代码（路径参数）
     * @return 报价信息 Map，或含 error 字段的错误 Map
     */
    @GetMapping("/quote/{symbol}")
    public Map<String, Object> quote(@PathVariable String symbol) {
        Stock stock = stockDao.findBySymbol(symbol);
        // 校验规则：股票代码不存在时返回错误
        if (stock == null) return Map.of("error", "Stock not found");
        BigDecimal price = quoteService.getPrice(stock);
        // 数据库中最新收盘价，作为实时报价失败时的兜底数据
        BigDecimal cached = stockPriceDao.findLatestClose(stock.getId());
        // live 字段标识 price 是否为实时抓取（true）或历史收盘价（false）
        return Map.of("symbol", symbol, "price", price != null ? price : cached, "live", price != null);
    }

    /**
     * 触发单只股票的行情缓存刷新
     *
     * <p>HTTP 方法：POST
     * <p>路径：/api/stocks/{symbol}/refresh
     * <p>功能说明：主动调用实时报价服务抓取该股票的最新价格并更新缓存，
     *   供用户手动点击"刷新"按钮时使用。
     *
     * <p>请求参数：
     *   - symbol（路径参数）：股票代码
     *
     * <p>响应格式：
     * <pre>
     * 成功：{ "status": "ok" }
     * 未找到：{ "error": "Stock not found" }
     * </pre>
     *
     * @param symbol 股票代码（路径参数）
     * @return 操作结果 Map
     */
    @PostMapping("/stocks/{symbol}/refresh")
    public Map<String, String> refresh(@PathVariable String symbol) {
        Stock stock = stockDao.findBySymbol(symbol);
        // 校验规则：股票代码不存在时返回错误，不进行无效的抓取调用
        if (stock == null) return Map.of("error", "Stock not found");
        // 调用报价服务抓取最新价格，结果会更新内存缓存（返回值此处不使用）
        quoteService.getPrice(stock);
        return Map.of("status", "ok");
    }

    /**
     * 触发整个组合所有持仓股票的行情批量刷新
     *
     * <p>HTTP 方法：POST
     * <p>路径：/api/portfolio/refresh
     * <p>功能说明：遍历当前组合的所有持仓快照，依次触发每只股票的实时报价抓取，
     *   适用于用户手动全量刷新或首次打开组合页面时的批量预热。
     *
     * <p>请求参数：无（portfolioId 从 Session 读取）
     *
     * <p>响应格式：
     * <pre>
     * 成功：{ "status": "ok", "count": "N" }  // N 为刷新的持仓数量
     * 未登录：{ "error": "No portfolio" }
     * </pre>
     *
     * @param req HTTP 请求，用于读取 Session 中的 portfolioId
     * @return 操作结果 Map，含刷新数量
     */
    @PostMapping("/portfolio/refresh")
    public Map<String, String> refreshPortfolio(HttpServletRequest req) {
        long pid = getPortfolioId(req);
        // 校验规则：portfolioId 为 0 表示未登录或未选择组合
        if (pid == 0) return Map.of("error", "No portfolio");
        // 获取当前组合的持仓快照列表，作为需要刷新的股票集合
        List<HoldingSnapshot> snaps = holdingService.getSnapshots(pid);
        // 逐只股票调用实时报价服务，触发抓取并刷新内存缓存
        for (HoldingSnapshot snap : snaps) {
            Stock s = stockDao.findBySymbol(snap.getStockSymbol());
            if (s != null) quoteService.getPrice(s);
        }
        // count 字段返回实际刷新的持仓数，便于前端展示刷新进度提示
        return Map.of("status", "ok", "count", String.valueOf(snaps.size()));
    }
}
