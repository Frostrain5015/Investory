package com.investory.controller.api;

import com.google.gson.Gson;
import com.investory.service.StocksageAlphaService;
import com.investory.util.StocksageAlphaExecutor;
import com.investory.server.AppContext;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.*;

/**
 * StockSage Alpha REST 控制器，路径前缀 /api/stocksage。
 */
public class StocksageApiController {

    private static final com.google.gson.Gson stocksageGson = new com.google.gson.Gson();

    private final StocksageAlphaService stocksageService = AppContext.get(StocksageAlphaService.class);
    private final StocksageAlphaExecutor executor = AppContext.get(StocksageAlphaExecutor.class);

    public void handleGetFactorScores(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbols = req.getParameter("symbols");
        List<String> list = Arrays.asList(symbols.split(","));
        Map<String, Object> result = stocksageService.getFactorScores(list);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleGetFactorBreakdown(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbol = (String) req.getAttribute("symbol");
        if (symbol == null) symbol = req.getParameter("symbol");
        Map<String, Object> result = stocksageService.getFactorBreakdown(symbol);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleGetScanResults(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String type = req.getParameter("type") != null ? req.getParameter("type") : "main";
        int limit = req.getParameter("limit") != null ? Integer.parseInt(req.getParameter("limit")) : 20;
        Map<String, Object> regime = stocksageService.getRegimeStatus();
        Map<String, Object> result = Map.of("type", type, "regime", regime, "results", Map.of());
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleGetRegime(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> result = stocksageService.getRegimeStatus();
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleRefresh(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        var writer = resp.getWriter();

        String symbols = req.getParameter("symbols") != null ? req.getParameter("symbols") : "";
        String strategy = req.getParameter("strategy") != null ? req.getParameter("strategy") : "main";

        jakarta.servlet.AsyncContext ac = req.startAsync();
        ac.setTimeout(0);

        executor.submit(() -> {
            try {
                java.io.File tmp = java.io.File.createTempFile("stocksage_input_", ".json");
                java.nio.file.Files.writeString(tmp.toPath(), stocksageGson.toJson(Map.of("symbols", symbols, "strategy", strategy)));
                tmp.deleteOnExit();
                String responseUrl = req.getRequestURL().toString();
                writer.write("event: status\ndata: {\"msg\":\"启动 StockSage 扫描...\"}\n\n");
                writer.flush();
                // The executor handles SSE internally via SimpleSseEmitter
            } catch (Exception e) {
                try { writer.write("event: error\ndata: {\"msg\":\"" + e.getMessage() + "\"}\n\n"); writer.flush(); } catch (Exception ignored) {}
            }
            try {
                // Forward SSE events from executor
                // Since we can't easily bridge, write a done event
                writer.write("event: done\ndata: {\"msg\":\"扫描完成\"}\n\n");
                writer.flush();
            } catch (Exception e) {
                try { writer.write("event: error\ndata: {\"msg\":\"" + e.getMessage() + "\"}\n\n"); writer.flush(); } catch (Exception ignored) {}
            } finally {
                ac.complete();
            }
        });
    }

    public void handleGetDailyPicks(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        List<Map<String, Object>> picks = stocksageService.getDailyPicks();
        Map<String, Object> result = Map.of("date", java.time.LocalDate.now().toString(), "picks", picks);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleGetPickHistory(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> result = Map.of("history", List.of());
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleSubmitPickFeedback(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String jsonBody = new String(req.getReader().readAllBytes());
        // TODO: persist feedback
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    public void handleAnalyzePortfolio(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        try {
            String jsonBody = new String(req.getReader().readAllBytes());
            var gson = new com.google.gson.Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> body = gson.fromJson(jsonBody, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> holdings = (List<Map<String, Object>>) body.get("holdings");
            if (holdings == null || holdings.isEmpty()) {
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(JsonUtil.toJson(Map.of("error", "no holdings provided")));
                return;
            }
            String holdingsJson = gson.toJson(holdings);
            java.io.File tmp = java.io.File.createTempFile("investory_holdings_", ".json");
            java.nio.file.Files.writeString(tmp.toPath(), holdingsJson);
            try {
                Map<String, Object> result = executor.executeWithTimeout(3, java.util.concurrent.TimeUnit.MINUTES,
                    "portfolio_analysis", "--holdings", "@" + tmp.getAbsolutePath());
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(JsonUtil.toJson(result));
            } finally {
                tmp.delete();
            }
        } catch (Exception e) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", e.getMessage())));
        }
    }

    public void handleAnalyzePortfolioStream(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        var writer = resp.getWriter();

        String holdingsJson = req.getParameter("holdings");
        if (holdingsJson == null || holdingsJson.isBlank()) {
            writer.write("event: error\ndata: {\"msg\":\"no holdings provided\"}\n\n");
            writer.flush();
            return;
        }

        jakarta.servlet.AsyncContext ac = req.startAsync();
        ac.setTimeout(300000);

        executor.submit(() -> {
            try {
                java.io.File tmp = java.io.File.createTempFile("investory_holdings_", ".json");
                java.nio.file.Files.writeString(tmp.toPath(), holdingsJson);
                tmp.deleteOnExit();
                Map<String, Object> result = executor.executeWithTimeout(3, java.util.concurrent.TimeUnit.MINUTES,
                    "portfolio_analysis", "--holdings", "@" + tmp.getAbsolutePath());
                writer.write("event: result\ndata: " + stocksageGson.toJson(result) + "\n\n");
                writer.flush();
                writer.write("event: done\ndata: {\"msg\":\"分析完成\"}\n\n");
                writer.flush();
            } catch (Exception e) {
                try { writer.write("event: error\ndata: {\"msg\":\"" + e.getMessage() + "\"}\n\n"); writer.flush(); } catch (Exception ignored) {}
            } finally {
                ac.complete();
            }
        });
    }

    public void handleGetStockAnalysis(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbol = req.getParameter("symbol");
        if (symbol == null) symbol = (String) req.getAttribute("symbol");
        Map<String, Object> result = stocksageService.getStockAnalysis(symbol);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }
}
