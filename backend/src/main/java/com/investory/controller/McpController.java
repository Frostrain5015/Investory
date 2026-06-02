package com.investory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investory.dao.McpTokenDao;
import com.investory.service.McpToolRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * MCP（Model Context Protocol）Streamable HTTP 端点。
 *
 * <p>单个 {@code POST /mcp}，无状态 JSON-RPC：实现 initialize / notifications.* /
 * tools/list / tools/call。每请求用 {@code Authorization: Bearer <token>} 区分用户
 * （多用户云端共享实例）；token 经 {@link McpTokenDao} 解析，且工具执行复用现有
 * {@code /api/*} 控制器逻辑（见 {@link McpToolRegistry}）。</p>
 *
 * <p>未授权时按 OAuth 2.1 资源服务器规范返回 401 + WWW-Authenticate，指向
 * protected-resource 元数据，触发连接器的 OAuth 发现流程。</p>
 */
@RestController
public class McpController {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired private McpTokenDao tokenDao;
    @Autowired private McpToolRegistry registry;

    @Value("${server.servlet.context-path:/investory}")
    private String contextPath;

    @PostMapping(value = "/mcp", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(@RequestBody(required = false) JsonNode body, HttpServletRequest req) {
        String token = bearer(req);
        McpTokenDao.TokenInfo user = token == null ? null : tokenDao.resolveToken(token);

        // 解析 JSON-RPC
        if (body == null || !body.hasNonNull("method")) {
            return ResponseEntity.badRequest().body(rpcError(idOf(body), -32600, "Invalid Request"));
        }
        String method = body.get("method").asText();
        JsonNode id = body.get("id");
        boolean isNotification = id == null || id.isNull();

        // initialize 不需要 token（握手）；其余方法需要有效 token。
        if (!"initialize".equals(method) && !method.startsWith("notifications/")) {
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header("WWW-Authenticate", wwwAuthenticate(req))
                        .body(rpcError(id, -32001, "Unauthorized: missing or invalid Bearer token"));
            }
        }

        switch (method) {
            case "initialize":
                return ResponseEntity.ok(rpcResult(id, initializeResult()));
            case "notifications/initialized":
            case "notifications/cancelled":
                // 通知无需响应 body
                return ResponseEntity.accepted().build();
            case "ping":
                return ResponseEntity.ok(rpcResult(id, mapper.createObjectNode()));
            case "tools/list":
                return ResponseEntity.ok(rpcResult(id, toolsList()));
            case "tools/call":
                if (isNotification) return ResponseEntity.accepted().build();
                return ResponseEntity.ok(toolsCall(id, body.get("params"), token));
            default:
                if (isNotification) return ResponseEntity.accepted().build();
                return ResponseEntity.ok(rpcError(id, -32601, "Method not found: " + method));
        }
    }

    /**
     * GET /mcp：SSE 传输（Streamable HTTP 可选 GET）。
     * WorkBuddy 等客户端先探测 GET(SSE) 建立通知通道，再通过 POST 发 JSON-RPC。
     * 使用 SseEmitter 保持连接存活，发送 endpoint 事件后等待客户端后续 POST 请求。
     */
    @GetMapping(value = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> get(HttpServletRequest req) {
        String token = bearer(req);
        if (token == null || tokenDao.resolveToken(token) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("WWW-Authenticate", wwwAuthenticate(req)).build();
        }
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        try {
            emitter.send(SseEmitter.event().name("endpoint")
                    .data(baseUrl(req) + contextPath + "/mcp"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .body(emitter);
    }

    // ── JSON-RPC 方法实现 ─────────────────────────────────────────────────

    private ObjectNode initializeResult() {
        ObjectNode r = mapper.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = r.putObject("capabilities");
        caps.putObject("tools");
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "investory");
        info.put("version", "3.0.0");
        return r;
    }

    private ObjectNode toolsList() {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (McpToolRegistry.Tool t : registry.tools()) {
            ObjectNode tn = arr.addObject();
            tn.put("name", t.name());
            tn.put("description", t.description());
            tn.set("inputSchema", t.inputSchema());
        }
        return r;
    }

    private ObjectNode toolsCall(JsonNode id, JsonNode params, String token) {
        String name = params != null && params.hasNonNull("name") ? params.get("name").asText() : "";
        JsonNode args = params != null ? params.get("arguments") : null;
        McpToolRegistry.Tool tool = registry.get(name);
        if (tool == null) {
            return rpcError(id, -32602, "Unknown tool: " + name);
        }
        try {
            Object result = tool.handler().apply(args, token);
            String text = mapper.writeValueAsString(result);
            ObjectNode res = mapper.createObjectNode();
            ArrayNode content = res.putArray("content");
            ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", text);
            // 同时给结构化结果（规范建议两者并存）
            if (result instanceof JsonNode jn && jn.isObject()) {
                res.set("structuredContent", jn);
            }
            res.put("isError", false);
            return rpcResult(id, res);
        } catch (Exception e) {
            ObjectNode res = mapper.createObjectNode();
            ArrayNode content = res.putArray("content");
            content.addObject().put("type", "text")
                    .put("text", "工具执行失败: " + safe(e.getMessage()));
            res.put("isError", true);
            return rpcResult(id, res);
        }
    }

    // ── JSON-RPC 封装 ────────────────────────────────────────────────────

    private ObjectNode rpcResult(JsonNode id, JsonNode result) {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id == null ? null : id);
        n.set("result", result);
        return n;
    }

    private ObjectNode rpcError(JsonNode id, int code, String message) {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id == null ? null : id);
        ObjectNode err = n.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return n;
    }

    private JsonNode idOf(JsonNode body) {
        return body == null ? null : body.get("id");
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    private static String bearer(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth == null) return null;
        if (auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = auth.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    private String wwwAuthenticate(HttpServletRequest req) {
        String base = baseUrl(req);
        String prm = base + contextPath + "/.well-known/oauth-protected-resource";
        return "Bearer resource_metadata=\"" + prm + "\"";
    }

    private String baseUrl(HttpServletRequest req) {
        String scheme = req.getScheme();
        int port = req.getServerPort();
        boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return scheme + "://" + req.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static String safe(String s) {
        if (s == null) return "unknown";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
