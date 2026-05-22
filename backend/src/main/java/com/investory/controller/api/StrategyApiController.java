package com.investory.controller.api;

import com.investory.dao.StrategyDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/backtest/strategies")
public class StrategyApiController {

    private final StrategyDao strategyDao;

    @Autowired
    public StrategyApiController(StrategyDao strategyDao) {
        this.strategyDao = strategyDao;
    }

    private long getUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object uid = s.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "未登录");

        String name = (String) body.getOrDefault("name", "未命名策略");
        String strategyType = (String) body.get("strategyType");
        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) body.get("strategy");

        if (strategyType == null || strategy == null) {
            return Map.of("error", "缺少策略类型或内容");
        }

        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(strategy);
            if (body.containsKey("id") && body.get("id") instanceof Number) {
                long id = ((Number) body.get("id")).longValue();
                strategyDao.update(id, userId, name, json);
                return Map.of("status", "ok", "id", id);
            }
            long id = strategyDao.insert(userId, name, strategyType, json);
            return Map.of("status", "ok", "id", id);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return List.of();
        return strategyDao.findByUser(userId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, HttpServletRequest req) {
        Map<String, Object> row = strategyDao.findById(id);
        return row != null ? row : Map.of("error", "not found");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest req) {
        long userId = getUserId(req);
        if (userId == 0) return Map.of("error", "unauthorized");
        int deleted = strategyDao.delete(id, userId);
        return Map.of("status", deleted > 0 ? "ok" : "not_found");
    }
}
