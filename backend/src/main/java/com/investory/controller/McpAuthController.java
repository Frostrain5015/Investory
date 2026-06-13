package com.investory.controller;

import com.google.gson.Gson;
import com.investory.dao.McpTokenDao;
import com.investory.server.AppContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

public class McpAuthController {

    private static final Gson gson = new Gson();
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
        resp.setContentType("application/json;charset=UTF-8");
        if (uid == null) { resp.getWriter().write("{\"error\":\"未登录\"}"); return; }

        StringBuilder sb = new StringBuilder();
        try (var reader = req.getReader()) { String l; while ((l = reader.readLine()) != null) sb.append(l); }
        @SuppressWarnings("unchecked") Map<String, Object> body = sb.length() > 0 ? gson.fromJson(sb.toString(), Map.class) : null;
        String label = body != null && body.get("label") != null ? body.get("label").toString() : "manual";
        String token = mcpTokenDao.issueToken(uid, portfolioId(req), label);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("note", "此 token 仅显示一次，请立即复制保存。");
        resp.getWriter().write(gson.toJson(m));
    }

    public void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Long uid = userId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (uid == null) { resp.getWriter().write("{\"error\":\"未登录\"}"); return; }
        var rows = mcpTokenDao.listTokens(uid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tokens", rows); result.put("count", rows.size());
        resp.getWriter().write(gson.toJson(result));
    }

    public void handleRevoke(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Long uid = userId(req);
        long id = Long.parseLong((String) req.getAttribute("id"));
        resp.setContentType("application/json;charset=UTF-8");
        if (uid == null) { resp.getWriter().write("{\"error\":\"未登录\"}"); return; }
        boolean ok = mcpTokenDao.revokeToken(id, uid);
        resp.getWriter().write(ok ? "{\"status\":\"ok\"}" : "{\"status\":\"not_found\"}");
    }
}
