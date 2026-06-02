package com.investory.controller;

import com.investory.dao.McpTokenDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP token 自助管理（网页设置页用）。
 *
 * <p>路径前缀 /api/mcp，走正常 session 登录拦截。用户在设置页生成静态 token，
 * 用于 Claude Code / Cursor 等以 {@code Authorization: Bearer <token>} 手动接入
 * （连接器原生 OAuth 流程见 {@link McpOAuthController}）。</p>
 */
@RestController
@RequestMapping("/api/mcp")
public class McpAuthController {

    @Autowired private McpTokenDao mcpTokenDao;

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

    /** 生成一个静态 MCP token（明文仅此一次返回）。 */
    @PostMapping(value = "/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest req) {
        Long uid = userId(req);
        if (uid == null) return Map.of("error", "未登录");
        String label = body != null && body.get("label") != null ? body.get("label").toString() : "manual";
        String token = mcpTokenDao.issueToken(uid, portfolioId(req), label);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("note", "此 token 仅显示一次，请立即复制保存。");
        return m;
    }

    /** 列出当前用户已生成的 token（不含明文）。 */
    @GetMapping(value = "/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object list(HttpServletRequest req) {
        Long uid = userId(req);
        if (uid == null) return Map.of("error", "未登录");
        List<Map<String, Object>> rows = mcpTokenDao.listTokens(uid);
        return Map.of("tokens", rows, "count", rows.size());
    }

    /** 吊销一个 token。 */
    @DeleteMapping(value = "/tokens/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> revoke(@PathVariable long id, HttpServletRequest req) {
        Long uid = userId(req);
        if (uid == null) return Map.of("error", "未登录");
        boolean ok = mcpTokenDao.revokeToken(id, uid);
        return Map.of("status", ok ? "ok" : "not_found");
    }
}
