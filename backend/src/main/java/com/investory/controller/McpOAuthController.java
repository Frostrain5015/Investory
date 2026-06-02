package com.investory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.dao.McpTokenDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * MCP 的 OAuth 2.1 授权服务器（最小集），让 Claude Desktop 等连接器原生连接。
 *
 * <p>Investory 自身作为 MCP 资源 + 授权服务器，**用户登录委托给上游 Frost ID**
 * （复用 {@link OAuthController}），但签发 Investory 自己的、audience 绑定到 MCP
 * 端点的 token（MCP 规范禁止透传上游 token）。</p>
 *
 * <p>实现：RFC9728 protected-resource 元数据 + RFC8414 AS 元数据 + RFC7591 最简动态
 * 客户端注册 + PKCE(S256) 授权码流。</p>
 */
@Controller
public class McpOAuthController {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired private McpTokenDao tokenDao;

    @Value("${server.servlet.context-path:/investory}")
    private String contextPath;

    // ── 发现元数据 ────────────────────────────────────────────────────────

    /** RFC9728：受保护资源元数据，指向授权服务器。 */
    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> protectedResource(HttpServletRequest req) {
        String base = base(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", base + contextPath + "/mcp");
        m.put("authorization_servers", List.of(base + contextPath));
        m.put("bearer_methods_supported", List.of("header"));
        return m;
    }

    /** RFC8414：授权服务器元数据。 */
    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> authServerMetadata(HttpServletRequest req) {
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
        return m;
    }

    // ── RFC7591 动态客户端注册（最简，公共客户端）────────────────────────

    @PostMapping(value = "/oauth/mcp/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
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
        return m;
    }

    // ── 授权端点（PKCE）──────────────────────────────────────────────────

    @GetMapping("/oauth/mcp/authorize")
    public String authorize(@RequestParam("client_id") String clientId,
                            @RequestParam("redirect_uri") String redirectUri,
                            @RequestParam(value = "code_challenge") String codeChallenge,
                            @RequestParam(value = "code_challenge_method", defaultValue = "S256") String method,
                            @RequestParam(value = "state", required = false, defaultValue = "") String state,
                            @RequestParam(value = "resource", required = false, defaultValue = "") String resource,
                            @RequestParam(value = "scope", required = false, defaultValue = "") String scope,
                            HttpServletRequest req) {
        if (!"S256".equals(method)) {
            return "redirect:" + redirectUri + "?error=invalid_request&error_description=PKCE+S256+required"
                    + stateParam(state);
        }
        // 校验 client + redirect_uri
        String allowed = tokenDao.clientRedirectUris(clientId);
        if (allowed == null || Arrays.stream(allowed.split("\n")).noneMatch(u -> u.equals(redirectUri))) {
            return "redirect:" + redirectUri + "?error=invalid_client" + stateParam(state);
        }

        HttpSession s = req.getSession(true);
        Object uid = s.getAttribute("userId");
        if (uid == null) {
            // 未登录：暂存本次授权请求，先去 Frost ID 登录，回来后续接（见 OAuthController 回调）。
            s.setAttribute("mcp_pending_authorize", req.getRequestURI()
                    + (req.getQueryString() != null ? "?" + req.getQueryString() : ""));
            return "redirect:" + contextPath + "/oauth/frost-id/login";
        }

        long userId = ((Number) uid).longValue();
        Long portfolioId = s.getAttribute("portfolioId") instanceof Number
                ? ((Number) s.getAttribute("portfolioId")).longValue() : null;

        String code = McpTokenDao.randomToken(24);
        tokenDao.insertOAuthCode(code, clientId, userId, portfolioId, redirectUri, codeChallenge,
                resource.isEmpty() ? null : resource);
        // 清掉可能残留的 pending
        s.removeAttribute("mcp_pending_authorize");

        return "redirect:" + redirectUri + "?code=" + enc(code) + stateParam(state);
    }

    // ── token 端点 ───────────────────────────────────────────────────────

    @PostMapping(value = "/oauth/mcp/token", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> token(@RequestParam("grant_type") String grantType,
                                     @RequestParam(value = "code", required = false) String code,
                                     @RequestParam(value = "code_verifier", required = false) String codeVerifier,
                                     @RequestParam(value = "redirect_uri", required = false) String redirectUri,
                                     @RequestParam(value = "resource", required = false) String resource,
                                     HttpServletRequest req) {
        if (!"authorization_code".equals(grantType)) {
            return Map.of("error", "unsupported_grant_type");
        }
        if (code == null || codeVerifier == null) {
            return Map.of("error", "invalid_request");
        }
        McpTokenDao.AuthCode ac = tokenDao.consumeOAuthCode(code);
        if (ac == null) {
            return Map.of("error", "invalid_grant", "error_description", "code invalid or expired");
        }
        if (redirectUri != null && !redirectUri.equals(ac.redirectUri())) {
            return Map.of("error", "invalid_grant", "error_description", "redirect_uri mismatch");
        }
        // PKCE 校验：BASE64URL(SHA256(verifier)) == stored challenge
        if (!verifyPkce(codeVerifier, ac.codeChallenge())) {
            return Map.of("error", "invalid_grant", "error_description", "PKCE verification failed");
        }
        // audience 绑定到 MCP 资源
        String audience = ac.resource() != null ? ac.resource() : base(req) + contextPath + "/mcp";
        String token = tokenDao.issueToken(ac.userId(), ac.portfolioId(), "oauth:" + ac.clientId(), audience);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access_token", token);
        m.put("token_type", "Bearer");
        m.put("scope", "mcp");
        return m;
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

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
