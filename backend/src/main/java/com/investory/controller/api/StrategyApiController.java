package com.investory.controller.api;

import com.investory.dao.StrategyDao;
import com.investory.server.AppContext;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.StringReader;
import java.util.*;

public class StrategyApiController {

    private final StrategyDao strategyDao = AppContext.get(StrategyDao.class);

    private long getUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object uid = s.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    public void handleSave(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) {
            resp.getWriter().write("{\"error\":\"未登录\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (var reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JsonUtil.fromJson(sb.toString(), Map.class);

        String name = (String) body.getOrDefault("name", "未命名策略");
        String strategyType = (String) body.get("strategyType");
        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) body.get("strategy");

        if (strategyType == null || strategy == null) {
            resp.getWriter().write("{\"error\":\"缺少策略类型或内容\"}");
            return;
        }

        try {
            String configJson = null;
            Object cfg = body.get("strategy_config");
            if (cfg instanceof Map) {
                configJson = JsonUtil.toJson(cfg);
            }
            String json = JsonUtil.toJson(strategy);
            if (body.containsKey("id") && body.get("id") instanceof Number) {
                long id = ((Number) body.get("id")).longValue();
                strategyDao.update(id, userId, name, json, configJson);
                resp.getWriter().write("{\"status\":\"ok\",\"id\":" + id + "}");
            } else {
                long id = strategyDao.insert(userId, name, strategyType, json, configJson);
                resp.getWriter().write("{\"status\":\"ok\",\"id\":" + id + "}");
            }
        } catch (Exception e) {
            resp.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    public void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) {
            resp.getWriter().write("[]");
            return;
        }
        resp.getWriter().write(JsonUtil.toJson(strategyDao.findByUser(userId)));
    }

    public void handleGet(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) {
            resp.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        Map<String, Object> row = strategyDao.findById(id);
        if (row == null) {
            resp.getWriter().write("{\"error\":\"not found\"}");
            return;
        }
        Long ownerId = row.get("user_id") instanceof Number ? ((Number) row.get("user_id")).longValue() : null;
        if (ownerId == null || ownerId != userId) {
            resp.getWriter().write("{\"error\":\"not found\"}");
            return;
        }
        resp.getWriter().write(JsonUtil.toJson(row));
    }

    public void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        resp.setContentType("application/json;charset=UTF-8");
        if (userId == 0) {
            resp.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        int deleted = strategyDao.delete(id, userId);
        resp.getWriter().write(deleted > 0 ? "{\"status\":\"ok\"}" : "{\"status\":\"not_found\"}");
    }
}
