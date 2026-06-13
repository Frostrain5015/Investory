package com.investory.controller;

import com.investory.dao.McpTokenDao;
import com.investory.server.AppContext;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP token 自助管理（网页设置页用）。
 */
public class McpAuthController {

    private final McpTokenDao mcpTokenDao = AppContext.get(McpTokenDao.class);

    private Long userId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null || s.getAttribute("userId") == null) return null;
        return ((Number) s.getAttribute("userId")).longValue();
    }

    private Long portfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return null;
        return s.getAttribute("portfolioId") instanceof Number
                ? ((Number) s.getAttribute("portfolioId")).longValue() : null;
    }

    public void handleCreate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Long uid = userId(req);
        if (uid == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "未登录")));
            return;
        }
        String jsonBody = new String(req.getReader().readAllBytes());
        String label = "manual";
        if (jsonBody != null && !jsonBody.isBlank()) {
            try {
                var gson = new com.google.gson.Gson();
                @SuppressWarnings("unchecked")
                Map<String, Object> body = gson.fromJson(jsonBody, Map.class);
                if (body.get("label") != null) label = body.get("label").toString();
            } catch (Exception ignored) {}
        }
        String token = mcpTokenDao.issueToken(uid, portfolioId(req), label);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("note", "此 token 仅显示一次，请立即复制保存。");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(m));
    }

    public void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Long uid = userId(req);
        if (uid == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "未登录")));
            return;
        }
        List<Map<String, Object>> rows = mcpTokenDao.listTokens(uid);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("tokens", rows, "count", rows.size())));
    }

    public void handleRevoke(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Long uid = userId(req);
        if (uid == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "未登录")));
            return;
        }
        long id = Long.parseLong(req.getParameter("id"));
        boolean ok = mcpTokenDao.revokeToken(id, uid);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", ok ? "ok" : "not_found")));
    }
}
