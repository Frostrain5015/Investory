package com.investory.controller.api;

import com.investory.dao.StrategyDao;
import com.investory.server.AppContext;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

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
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "未登录")));
            return;
        }

        // Read JSON body
        String jsonBody = new String(req.getReader().readAllBytes());
        var gson = new com.google.gson.Gson();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = gson.fromJson(jsonBody, Map.class);

        String name = (String) body.getOrDefault("name", "未命名策略");
        String strategyType = (String) body.get("strategyType");
        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) body.get("strategy");

        if (strategyType == null || strategy == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "缺少策略类型或内容")));
            return;
        }

        try {
            String configJson = null;
            Object cfg = body.get("strategy_config");
            if (cfg instanceof Map) {
                configJson = new com.google.gson.Gson().toJson(cfg);
            }
            String json = new com.google.gson.Gson().toJson(strategy);
            Map<String, Object> result;
            if (body.containsKey("id") && body.get("id") instanceof Number) {
                long id = ((Number) body.get("id")).longValue();
                strategyDao.update(id, userId, name, json, configJson);
                result = Map.of("status", "ok", "id", id);
            } else {
                long id = strategyDao.insert(userId, name, strategyType, json, configJson);
                result = Map.of("status", "ok", "id", id);
            }
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(result));
        } catch (Exception e) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", e.getMessage())));
        }
    }

    public void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("[]");
            return;
        }
        List<Map<String, Object>> result = strategyDao.findByUser(userId);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleGet(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "unauthorized")));
            return;
        }
        long id = Long.parseLong((String) req.getAttribute("id"));
        Map<String, Object> row = strategyDao.findById(id);
        if (row == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not found")));
            return;
        }
        Long ownerId = row.get("user_id") instanceof Number ? ((Number) row.get("user_id")).longValue() : null;
        if (ownerId == null || ownerId != userId) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "not found")));
            return;
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(row));
    }

    public void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long userId = getUserId(req);
        if (userId == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "unauthorized")));
            return;
        }
        long id = Long.parseLong((String) req.getAttribute("id"));
        int deleted = strategyDao.delete(id, userId);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", deleted > 0 ? "ok" : "not_found")));
    }
}
