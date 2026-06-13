package com.investory.controller;

import com.investory.dao.McpTokenDao;
import com.investory.server.AppContext;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * MCP 的 OAuth 2.1 授权服务器（最小集），让 Claude Desktop 等连接器原生连接。
 */
public class McpOAuthController {

    private final McpTokenDao tokenDao = AppContext.get(McpTokenDao.class);
    private String contextPath = "";

    public void handleProtectedResource(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String base = base(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", base + contextPath + "/mcp");
        m.put("authorization_servers", List.of(base + contextPath));
        m.put("bearer_methods_supported", List.of("header"));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(m));
    }

    public void handleAuthServerMetadata(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String as = base(req) + contextPath;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", as);
        m.put("authorization_endpoint", as + "/oauth/mcp/authorize");
        m.put("token_endpoint", as + "/oauth/mcp/token");
        m.put("registration_endpoint", as + "/oauth/mcp/register");
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of("authorization_code"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("token_endpoint_auth_methods_supported", List.of("none"));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(m));
    }

    public void handleRegister(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String jsonBody = new String(req.getReader().readAllBytes());
        var gson = new com.google.gson.Gson();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = gson.fromJson(jsonBody, Map.class);

        @SuppressWarnings("unchecked")
        List<String> redirects = body.get("redirect_uris") instanceof List
                ? (List<String>) body.get("redirect_uris") : List.of();
        String clientName = body.get("client_name") != null ? body.get("client_name").toString() : "mcp-client";
        String redirectUris = String.join("\n", redirects);
        String clientId = tokenDao.registerClient(redirectUris, clientName);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client_id", clientId);
        m.put("redirect_uris", redirects);
        m.put("token_endpoint_auth_method", "none");
        m.put("grant_types", List.of("authorization_code"));
        m.put("response_types", List.of("code"));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(m));
    }

    public void handleAuthorize(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String clientId = req.getParameter("client_id");
        String redirectUri = req.getParameter("redirect_uri");
        String codeChallenge = req.getParameter("code_challenge");
        String method = req.getParameter("code_challenge_method") != null ? req.getParameter("code_challenge_method") : "S256";
        String state = req.getParameter("state") != null ? req.getParameter("state") : "";
        String resource = req.getParameter("resource") != null ? req.getParameter("resource") : "";
        String scope = req.getParameter("scope") != null ? req.getParameter("scope") : "";

        if (!"S256".equals(method)) {
            resp.sendRedirect(redirectUri + "?error=invalid_request&error_description=PKCE+S256+required" + stateParam(state));
            return;
        }
        String allowed = tokenDao.clientRedirectUris(clientId);
        if (allowed == null || Arrays.stream(allowed.split("\n")).noneMatch(u -> u.equals(redirectUri))) {
            resp.sendRedirect(redirectUri + "?error=invalid_client" + stateParam(state));
            return;
        }

        HttpSession s = req.getSession(true);
        Object uid = s.getAttribute("userId");
        if (uid == null) {
            s.setAttribute("mcp_pending_authorize", req.getRequestURI()
                    + (req.getQueryString() != null ? "?" + req.getQueryString() : ""));
            resp.sendRedirect(contextPath + "/oauth/frost-id/login");
            return;
        }

        long userId = ((Number) uid).longValue();
        Long portfolioId = s.getAttribute("portfolioId") instanceof Number
                ? ((Number) s.getAttribute("portfolioId")).longValue() : null;

        String code = McpTokenDao.randomToken(24);
        tokenDao.insertOAuthCode(code, clientId, userId, portfolioId, redirectUri, codeChallenge,
                resource.isEmpty() ? null : resource);
        s.removeAttribute("mcp_pending_authorize");

        resp.sendRedirect(redirectUri + "?code=" + enc(code) + stateParam(state));
    }

    public void handleToken(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String grantType = req.getParameter("grant_type");
        String code = req.getParameter("code");
        String codeVerifier = req.getParameter("code_verifier");
        String redirectUri = req.getParameter("redirect_uri");
        String resource = req.getParameter("resource");

        if (!"authorization_code".equals(grantType)) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "unsupported_grant_type")));
            return;
        }
        if (code == null || codeVerifier == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "invalid_request")));
            return;
        }
        McpTokenDao.AuthCode ac = tokenDao.consumeOAuthCode(code);
        if (ac == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "invalid_grant", "error_description", "code invalid or expired")));
            return;
        }
        if (redirectUri != null && !redirectUri.equals(ac.redirectUri())) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "invalid_grant", "error_description", "redirect_uri mismatch")));
            return;
        }
        if (!verifyPkce(codeVerifier, ac.codeChallenge())) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "invalid_grant", "error_description", "PKCE verification failed")));
            return;
        }
        String audience = ac.resource() != null ? ac.resource() : base(req) + contextPath + "/mcp";
        String token = tokenDao.issueToken(ac.userId(), ac.portfolioId(), "oauth:" + ac.clientId(), audience);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access_token", token);
        m.put("token_type", "Bearer");
        m.put("scope", "mcp");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(m));
    }

    private boolean verifyPkce(String verifier, String challenge) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
                    challenge.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    private static String stateParam(String state) {
        return state == null || state.isEmpty() ? "" : "&state=" + enc(state);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private String base(HttpServletRequest req) {
        String scheme = req.getScheme();
        int port = req.getServerPort();
        boolean def = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return scheme + "://" + req.getServerName() + (def ? "" : ":" + port);
    }
}
