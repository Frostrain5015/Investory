package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.investory.crawler.BacktestSessionManager;
import com.investory.dao.BacktestDao;
import com.investory.model.BacktestRequest;
import com.investory.service.BacktestExecutionService;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;


@RestController
@RequestMapping("/api/backtest")
public class BacktestApiController {

    private static final ObjectMapper json = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> CURVE = new TypeReference<>() {};
    private final BacktestDao backtestDao;
    private final BacktestSessionManager session;
    private final BacktestExecutionService execution;

    public BacktestApiController(BacktestDao backtestDao, BacktestSessionManager session,
                                 BacktestExecutionService execution) {
        this.backtestDao = backtestDao;
        this.session = session;
        this.execution = execution;
    }

    // ── Session helpers ──────────────────────────────────────────────────

    private long getUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object uid = s.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    private Long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return null;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : null;
    }

    // ── Start backtest (JSON response, then stream via GET /stream) ──────

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startBacktest(@RequestBody BacktestRequest body,
                                                            HttpServletRequest req) throws IOException {
        long userId = getUserId(req);
        if (userId == 0) return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        java.util.OptionalLong resultId = execution.start(userId, getPortfolioId(req), body);
        if (resultId.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("error", "已有回测正在运行"));
        }
        return ResponseEntity.ok(Map.of("status", "started", "resultId", resultId.getAsLong()));
    }

    // ── Reconnect to active session ──────────────────────────────────────

    @GetMapping("/status")
    public Map<String, Object> getStatus(HttpServletRequest req) {
        return session.getStatus(getUserId(req));
    }

    @GetMapping("/stream")
    public SseEmitter stream(HttpServletRequest req, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return session.subscribe(getUserId(req));
    }

    // ── CRUD for saved results ───────────────────────────────────────────

    @GetMapping("/history")
    public List<Map<String, Object>> history(HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return List.of();
        return backtestDao.findByUser(userId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getResult(@PathVariable long id, HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "unauthorized");
        Map<String, Object> row = backtestDao.findById(id);
        if (row == null) return Map.of("error", "not found");
        Long ownerId = row.get("user_id") instanceof Number ? ((Number) row.get("user_id")).longValue() : null;
        if (ownerId == null || ownerId != userId) return Map.of("error", "not found");
        return row;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteResult(@PathVariable long id, HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "unauthorized");
        int deleted = backtestDao.delete(id, userId);
        return Map.of("status", deleted > 0 ? "ok" : "not_found");
    }

    @GetMapping("/compare")
    public List<Map<String, Object>> compare(@RequestParam String ids, HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return List.of();
        List<Long> idList = Arrays.stream(ids.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .map(Long::parseLong).limit(10).toList();
        if (idList.size() < 2) return List.of();

        List<Map<String, Object>> rows = backtestDao.findByIds(userId, idList);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("name", row.get("name"));
            item.put("strategyType", row.get("strategy_type"));
            item.put("startDate", row.get("start_date") != null ? row.get("start_date").toString().substring(0, 10) : "");
            item.put("endDate", row.get("end_date") != null ? row.get("end_date").toString().substring(0, 10) : "");
            // Parse metrics
            try {
                String mj = (String) row.get("metrics_json");
                item.put("metrics", mj != null ? json.readValue(mj, DOCUMENT) : Map.of());
            } catch (Exception e) { item.put("metrics", Map.of()); }
            // Normalize equity curve to base-100
            try {
                String ej = (String) row.get("equity_curve_json");
                if (ej != null) {
                    List<Map<String, Object>> curve = json.readValue(ej, CURVE);
                    if (!curve.isEmpty()) {
                        Object baseObj = curve.get(0).get("equity");
                        double base = baseObj instanceof Number ? ((Number) baseObj).doubleValue() : 1.0;
                        if (base == 0) base = 1;
                        final double b = base;
                        List<Map<String, Object>> norm = curve.stream().map(pt -> {
                            Object eqObj = pt.get("equity");
                            double eq = eqObj instanceof Number ? ((Number) eqObj).doubleValue() : b;
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("date", pt.get("date"));
                            p.put("value", Math.round(eq / b * 10000.0) / 100.0);
                            return p;
                        }).toList();
                        item.put("equityCurveNormalized", norm);
                    } else { item.put("equityCurveNormalized", List.of()); }
                } else { item.put("equityCurveNormalized", List.of()); }
            } catch (Exception e) { item.put("equityCurveNormalized", List.of()); }
            result.add(item);
        }
        return result;
    }

}
