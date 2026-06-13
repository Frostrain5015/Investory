package com.investory.controller.api;

import com.google.gson.Gson;
import com.investory.server.AppContext;
import com.investory.server.SseClient;
import com.investory.service.StocksageAlphaService;
import com.investory.util.StocksageAlphaExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class StocksageApiController {

    private static final Gson gson = new Gson();

    private final StocksageAlphaService stocksageService = AppContext.get(StocksageAlphaService.class);
    private final StocksageAlphaExecutor executor = AppContext.get(StocksageAlphaExecutor.class);

    public void handleGetFactorScores(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbols = req.getParameter("symbols");
        List<String> list = Arrays.asList(symbols.split(","));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(stocksageService.getFactorScores(list)));
    }

    public void handleGetFactorBreakdown(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbol = (String) req.getAttribute("symbol");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(stocksageService.getFactorBreakdown(symbol)));
    }

    public void handleGetScanResults(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String type = req.getParameter("type");
        if (type == null || type.isBlank()) type = "main";
        String limitStr = req.getParameter("limit");
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 20;
        resp.setContentType("application/json;charset=UTF-8");
        Map<String, Object> regime = stocksageService.getRegimeStatus();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type); result.put("regime", regime); result.put("results", Map.of());
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleGetRegime(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(stocksageService.getRegimeStatus()));
    }

    public void handleRefresh(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setHeader("X-Accel-Buffering", "no");
        resp.setHeader("Cache-Control", "no-cache");
        String symbols = req.getParameter("symbols");
        String strategy = req.getParameter("strategy");
        if (strategy == null || strategy.isBlank()) strategy = "main";

        SseClient client = new SseClient(resp);
        client.init();

        if (symbols != null && !symbols.isBlank()) {
            executor.executeWithSse(client, "score_stocks", "--symbols", symbols);
        } else {
            executor.executeWithSse(client, "scan_universe", "--type", strategy);
        }

        req.startAsync();
        while (!client.isCompleted()) { try { Thread.sleep(1000); } catch (InterruptedException e) { break; } }
    }

    public void handleGetDailyPicks(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        List<Map<String, Object>> picks = stocksageService.getDailyPicks();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", java.time.LocalDate.now().toString()); result.put("picks", picks);
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleGetPickHistory(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        Map<String, Object> result = new LinkedHashMap<>(); result.put("history", List.of());
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleSubmitPickFeedback(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    public void handleAnalyzePortfolio(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        StringBuilder sb = new StringBuilder();
        try (var reader = req.getReader()) { String l; while ((l = reader.readLine()) != null) sb.append(l); }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = gson.fromJson(sb.toString(), Map.class);
            @SuppressWarnings("unchecked") List<Map<String, Object>> holdings = body != null ? (List<Map<String, Object>>) body.get("holdings") : null;
            if (holdings == null || holdings.isEmpty()) { resp.getWriter().write("{\"error\":\"no holdings provided\"}"); return; }
            String holdingsJson = gson.toJson(holdings);
            File tmp = File.createTempFile("investory_holdings_", ".json");
            Files.writeString(tmp.toPath(), holdingsJson);
            try { resp.getWriter().write(gson.toJson(executor.executeWithTimeout(3, TimeUnit.MINUTES, "portfolio_analysis", "--holdings", "@" + tmp.getAbsolutePath()))); }
            finally { tmp.delete(); }
        } catch (Exception e) { resp.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}"); }
    }

    public void handleAnalyzePortfolioStream(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setHeader("X-Accel-Buffering", "no");
        resp.setHeader("Cache-Control", "no-cache");
        String holdingsJson = req.getParameter("holdings");
        SseClient client = new SseClient(resp);
        client.init();

        try {
            File tmp = File.createTempFile("investory_holdings_", ".json");
            Files.writeString(tmp.toPath(), holdingsJson);
            tmp.deleteOnExit();
            executor.executeWithSse(client, "portfolio_analysis", "--holdings", "@" + tmp.getAbsolutePath());
        } catch (Exception e) { client.send("error", Map.of("message", e.getMessage())); client.complete(); }

        req.startAsync();
        while (!client.isCompleted()) { try { Thread.sleep(1000); } catch (InterruptedException e) { break; } }
    }

    public void handleGetStockAnalysis(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbol = (String) req.getAttribute("symbol");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(gson.toJson(stocksageService.getStockAnalysis(symbol)));
    }

}
