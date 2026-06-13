package com.investory.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.investory.dao.McpTokenDao;
import com.investory.service.McpToolRegistry;
import com.investory.server.AppContext;
import com.investory.server.ConfigLoader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * MCP（Model Context Protocol）Streamable HTTP 端点。
 */
public class McpController {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Gson gson = new Gson();

    private final McpTokenDao tokenDao = AppContext.get(McpTokenDao.class);
    private final McpToolRegistry registry = AppContext.get(McpToolRegistry.class);
    private final String contextPath = ConfigLoader.get("server.servlet.context-path", "");

    public void handlePost(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String token = bearer(req);
        McpTokenDao.TokenInfo user = token == null ? null : tokenDao.resolveToken(token);

        String jsonBody = new String(req.getReader().readAllBytes());
        JsonObject body = jsonBody.isBlank() ? null : gson.fromJson(jsonBody, JsonObject.class);

        if (body == null || !body.has("method")) {
            resp.setStatus(400);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(gson.toJson(rpcError(idOf(body), -32600, "Invalid Request")));
            return;
        }
        String method = body.get("method").getAsString();
        JsonElement id = body.get("id");
        boolean isNotification = id == null || id.isJsonNull();

        if (!"initialize".equals(method) && !method.startsWith("notifications/")) {
            if (user == null) {
                resp.setStatus(401);
                resp.setHeader("WWW-Authenticate", wwwAuthenticate(req));
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(gson.toJson(rpcError(id, -32001, "Unauthorized: missing or invalid Bearer token")));
                return;
            }
        }

        resp.setContentType("application/json;charset=UTF-8");
        switch (method) {
            case "initialize":
                resp.getWriter().write(gson.toJson(rpcResult(id, initializeResult())));
                break;
            case "notifications/initialized":
            case "notifications/cancelled":
                resp.setStatus(202);
                break;
            case "ping":
                resp.getWriter().write(gson.toJson(rpcResult(id, new JsonObject())));
                break;
            case "tools/list":
                resp.getWriter().write(gson.toJson(rpcResult(id, toolsList())));
                break;
            case "tools/call":
                if (isNotification) { resp.setStatus(202); break; }
                resp.getWriter().write(gson.toJson(toolsCall(id, body.get("params"), token)));
                break;
            default:
                if (isNotification) { resp.setStatus(202); break; }
                resp.getWriter().write(gson.toJson(rpcError(id, -32601, "Method not found: " + method)));
                break;
        }
    }

    public void handleGet(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String token = bearer(req);
        if (token == null || tokenDao.resolveToken(token) == null) {
            resp.setStatus(401);
            resp.setHeader("WWW-Authenticate", wwwAuthenticate(req));
            return;
        }

        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        jakarta.servlet.AsyncContext ac = req.startAsync();
        ac.setTimeout(300000);
        var writer = resp.getWriter();
        writer.write("event: endpoint\ndata: " + baseUrl(req) + contextPath + "/mcp\n\n");
        writer.flush();
    }

    private JsonObject initializeResult() {
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        r.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "investory");
        info.addProperty("version", "3.0.0");
        r.add("serverInfo", info);
        return r;
    }

    private JsonObject toolsList() {
        JsonObject r = new JsonObject();
        JsonArray arr = new JsonArray();
        for (McpToolRegistry.Tool t : registry.tools()) {
            JsonObject tn = new JsonObject();
            tn.addProperty("name", t.name());
            tn.addProperty("description", t.description());
            tn.add("inputSchema", t.inputSchema());
            arr.add(tn);
        }
        r.add("tools", arr);
        return r;
    }

    private JsonObject toolsCall(JsonElement id, JsonElement params, String token) {
        String name = params != null && params.getAsJsonObject().has("name") ? params.getAsJsonObject().get("name").getAsString() : "";
        JsonElement args = params != null ? params.getAsJsonObject().get("arguments") : null;
        McpToolRegistry.Tool tool = registry.get(name);
        if (tool == null) {
            return rpcError(id, -32602, "Unknown tool: " + name);
        }
        try {
            Object result = tool.handler().apply(args, token);
            String text = gson.toJson(result);
            JsonObject res = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", text);
            content.add(block);
            res.add("content", content);
            if (result instanceof JsonObject jn) {
                res.add("structuredContent", jn);
            }
            res.addProperty("isError", false);
            return rpcResult(id, res);
        } catch (Exception e) {
            JsonObject res = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", "工具执行失败: " + safe(e.getMessage()));
            content.add(block);
            res.add("content", content);
            res.addProperty("isError", true);
            return rpcResult(id, res);
        }
    }

    private JsonObject rpcResult(JsonElement id, JsonObject result) {
        JsonObject n = new JsonObject();
        n.addProperty("jsonrpc", "2.0");
        n.add("id", id == null || id.isJsonNull() ? null : id);
        n.add("result", result);
        return n;
    }

    private JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject n = new JsonObject();
        n.addProperty("jsonrpc", "2.0");
        n.add("id", id == null || id.isJsonNull() ? null : id);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        n.add("error", err);
        return n;
    }

    private JsonElement idOf(JsonObject body) {
        return body == null ? null : body.get("id");
    }

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
