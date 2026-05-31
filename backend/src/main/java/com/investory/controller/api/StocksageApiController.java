package com.investory.controller.api;

import com.investory.service.StocksageAlphaService;
import com.investory.util.StocksageAlphaExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * StockSage Alpha REST 控制器，路径前缀 /api/stocksage。
 *
 * <p>核心职责：
 * <ol>
 *   <li>批量获取多因子评分：GET /factor-scores</li>
 *   <li>单股因子拆解：GET /factor-breakdown/{symbol}</li>
 *   <li>最新扫描结果：GET /scan-results</li>
 *   <li>当前市场环境：GET /regime</li>
 *   <li>SSE 流式刷新：GET /refresh</li>
 *   <li>每日推荐：GET /daily-picks</li>
 *   <li>股票综合分析：GET /stock-analysis/{symbol}</li>
 *   <li>用户反馈：POST /pick-feedback</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/stocksage")
public class StocksageApiController {

    private final StocksageAlphaService stocksageService;
    private final StocksageAlphaExecutor executor;

    @Autowired
    public StocksageApiController(StocksageAlphaService stocksageService, StocksageAlphaExecutor executor) {
        this.stocksageService = stocksageService;
        this.executor = executor;
    }

    // ── 因子评分 ─────────────────────────────────────────────────────────

    /** GET /api/stocksage/factor-scores?symbols=600519,000858 */
    @GetMapping("/factor-scores")
    public Map<String, Object> getFactorScores(@RequestParam("symbols") String symbols) {
        List<String> list = Arrays.asList(symbols.split(","));
        return stocksageService.getFactorScores(list);
    }

    /** GET /api/stocksage/factor-breakdown/{symbol} */
    @GetMapping("/factor-breakdown/{symbol}")
    public Map<String, Object> getFactorBreakdown(@PathVariable String symbol) {
        return stocksageService.getFactorBreakdown(symbol);
    }

    // ── 扫描结果 ─────────────────────────────────────────────────────────

    /** GET /api/stocksage/scan-results?type=main&limit=20 */
    @GetMapping("/scan-results")
    public Map<String, Object> getScanResults(
        @RequestParam(defaultValue = "main") String type,
        @RequestParam(defaultValue = "20") int limit) {
        // 返回最新扫描缓存 + 当前环境
        Map<String, Object> regime = stocksageService.getRegimeStatus();
        return Map.of(
            "type", type,
            "regime", regime,
            "results", Map.of()   // TODO: 从 stocksage_dao 读取
        );
    }

    // ── 市场环境 ─────────────────────────────────────────────────────────

    /** GET /api/stocksage/regime */
    @GetMapping("/regime")
    public Map<String, Object> getRegime() {
        return stocksageService.getRegimeStatus();
    }

    // ── SSE 刷新 ─────────────────────────────────────────────────────────

    /**
     * GET /api/stocksage/refresh — 触发扫描刷新，SSE 流式推送进度。
     * 可选参数 ?symbols=600519,000858 指定只扫描特定股票（逗号分隔）。
     * 不传则扫描默认测试集（CSI300 前 30 只）。
     */
    @GetMapping("/refresh")
    public SseEmitter refresh(@RequestParam(value = "symbols", defaultValue = "") String symbols,
                               HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(0L);

        if (!symbols.isBlank()) {
            executor.executeWithSse(emitter, "score_stocks", "--symbols", symbols);
        } else {
            executor.executeWithSse(emitter, "scan_universe", "--type", "main");
        }
        return emitter;
    }

    // ── 每日推荐 ─────────────────────────────────────────────────────────

    /** GET /api/stocksage/daily-picks */
    @GetMapping("/daily-picks")
    public Map<String, Object> getDailyPicks() {
        List<Map<String, Object>> picks = stocksageService.getDailyPicks();
        return Map.of("date", java.time.LocalDate.now().toString(),
                      "picks", picks);
    }

    /**
     * GET /api/stocksage/pick-history?from=2026-05-01&to=2026-05-31
     * TODO: 实现从 stocksage_daily_picks 读取历史记录
     */
    @GetMapping("/pick-history")
    public Map<String, Object> getPickHistory(
        @RequestParam(defaultValue = "") String from,
        @RequestParam(defaultValue = "") String to) {
        return Map.of("history", List.of());
    }

    /** POST /api/stocksage/pick-feedback */
    @PostMapping("/pick-feedback")
    public Map<String, Object> submitPickFeedback(@RequestBody Map<String, Object> body) {
        // TODO: 写入 stocksage_pick_feedback 表
        return Map.of("status", "ok");
    }

    // ── 组合因子分析 ─────────────────────────────────────────────────────

    /**
     * POST /api/stocksage/portfolio-analysis
     * Body: {"holdings": [{"symbol":"600519","weight":30,"name":"茅台"},...]}
     * 对组合持仓逐只调用 research()，加权聚合所有因子得分。
     * 返回: portfolio_score, group_exposure, factor_exposure, top/bottom holdings
     */
    @PostMapping("/portfolio-analysis")
    public Map<String, Object> analyzePortfolio(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> holdings = (List<Map<String, Object>>) body.get("holdings");
            if (holdings == null || holdings.isEmpty()) {
                return Map.of("error", "no holdings provided");
            }
            String holdingsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(holdings);
            return executor.execute("portfolio_analysis", "--holdings", holdingsJson);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // ── 股票综合分析 ─────────────────────────────────────────────────────

    /** GET /api/stocksage/stock-analysis/{symbol} */
    @GetMapping("/stock-analysis/{symbol}")
    public Map<String, Object> getStockAnalysis(@PathVariable String symbol) {
        return stocksageService.getStockAnalysis(symbol);
    }
}
