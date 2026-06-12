package com.investory.controller;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frost ID OAuth 2.1 登录控制器。
 *
 * <p>处理 PKCE Authorization Code 流程。用户点击"Frost ID 登录"后重定向至 Frost ID
 * 授权页面，授权成功后通过回调交换访问令牌，再调用 userinfo 端点获取用户信息。</p>
 */
@Controller
public class OAuthController {

    @Value("${frostid.oauth.client-id}")
    private String clientId;

    @Value("${frostid.oauth.client-secret}")
    private String clientSecret;

    @Value("${frostid.oauth.authorize-url}")
    private String authorizeUrl;

    @Value("${frostid.oauth.token-url}")
    private String tokenUrl;

    @Value("${frostid.oauth.userinfo-url}")
    private String userinfoUrl;

    @Value("${frostid.oauth.redirect-url}")
    private String redirectUrl;

    @Autowired private UserDao userDao;
    @Autowired private PortfolioDao portfolioDao;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SecureRandom secureRandom = new SecureRandom();

    // ── 桌面客户端一次性登录令牌 ────────────────────────────────────────────────
    // 浏览器完成 OAuth 登录后，无法直接把会话 Cookie 写回 Electron 客户端（不同 Cookie
    // 罐）。改为签发一个短时、单次使用的令牌，经 investory:// 深链回传到客户端，客户端
    // 再用自身会话调用 /oauth/frost-id/exchange 兑换，从而在客户端建立登录态。
    private record DesktopToken(long userId, long expiresAt) {}
    private final Map<String, DesktopToken> desktopTokens = new ConcurrentHashMap<>();
    private static final long DESKTOP_TOKEN_TTL_MS = 120_000;

    private String issueDesktopToken(long userId) {
        long now = System.currentTimeMillis();
        desktopTokens.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        desktopTokens.put(token, new DesktopToken(userId, now + DESKTOP_TOKEN_TTL_MS));
        return token;
    }

    /** 校验并消费一次性令牌；有效则返回 userId，否则 null。 */
    private Long consumeDesktopToken(String token) {
        if (token == null || token.isBlank()) return null;
        DesktopToken dt = desktopTokens.remove(token); // 单次使用
        if (dt == null || dt.expiresAt() < System.currentTimeMillis()) return null;
        return dt.userId();
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ── 步骤 1：重定向到 Frost ID ──────────────────────────────────────────────

    @GetMapping("/oauth/frost-id/login")
    public String frostIdLogin(
            @RequestParam(value = "return_to", required = false) String returnTo,
            @RequestParam(value = "client", required = false) String client,
            HttpServletRequest req) throws Exception {
        String verifier = generateCodeVerifier();
        String challenge = generateCodeChallenge(verifier);
        String state     = generateState();

        HttpSession session = req.getSession(true);
        session.setAttribute("frostid_verifier", verifier);
        session.setAttribute("frostid_state",    state);
        // 桌面客户端登录：登录在系统浏览器中完成，回调后通过深链回传令牌给客户端。
        if ("desktop".equals(client)) {
            session.setAttribute("frostid_client_desktop", Boolean.TRUE);
        } else {
            session.removeAttribute("frostid_client_desktop");
        }
        String safeReturnTo = sanitizeReturnTo(returnTo);
        if (safeReturnTo != null) {
            session.setAttribute("frostid_return_to", safeReturnTo);
        } else {
            session.removeAttribute("frostid_return_to");
        }

        String params = String.join("&",
                "response_type=code",
                "client_id="              + urlEncode(clientId),
                "redirect_uri="           + urlEncode(redirectUrl),
                "code_challenge="         + urlEncode(challenge),
                "code_challenge_method=S256",
                "state="                  + urlEncode(state),
                "scope=openid%20profile%20email"
        );

        return "redirect:" + authorizeUrl + "?" + params;
    }

    // ── 步骤 2：回调处理 ──────────────────────────────────────────────────────

    @GetMapping(value = "/oauth/frost-id/callback", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String frostIdCallback(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            HttpServletRequest req) {

        HttpSession session = req.getSession(false);
        if (session == null) return "error: session expired";

        String savedState = (String) session.getAttribute("frostid_state");
        if (savedState == null || !savedState.equals(state)) {
            session.invalidate();
            return "error: invalid state";
        }

        String verifier = (String) session.getAttribute("frostid_verifier");
        session.removeAttribute("frostid_state");
        session.removeAttribute("frostid_verifier");

        try {
            // 1. 用授权码换取 access_token
            String tokenBody = exchangeCode(code, verifier);
            String accessToken = extractJsonString(tokenBody, "access_token");
            if (accessToken == null) {
                return "error: no access_token in response: " + tokenBody;
            }

            // 2. 调用 userinfo 端点获取用户信息
            String userInfoBody = fetchUserInfo(accessToken);
            String frostIdUserId = extractJsonString(userInfoBody, "sub");
            String email         = extractJsonString(userInfoBody, "email");
            String username      = extractJsonString(userInfoBody, "preferred_username");

            if (frostIdUserId == null) {
                return "error: no sub in userinfo: " + userInfoBody;
            }
            if (email == null) email = username + "@frost-id.local";

            // 3. 查找或创建 Investory 用户
            User user = userDao.findByFrostIdId(frostIdUserId);
            if (user == null) {
                user = userDao.findByEmail(email);
                if (user == null) {
                    user = new User();
                    user.setUsername(username != null ? username : frostIdUserId.substring(0, 8));
                    user.setEmail(email);
                    user.setPasswordHash("");
                    user.setFrostIdId(frostIdUserId);
                    long userId = userDao.insert(user);
                    user.setId(userId);

                    Portfolio portfolio = new Portfolio();
                    portfolio.setUserId(userId);
                    portfolio.setName("我的投资组合");
                    portfolioDao.insert(portfolio);
                } else {
                    userDao.updateFrostIdId(user.getId(), frostIdUserId);
                }
            }

            // 4. 建立 Session
            session.setAttribute("userId",      user.getId());
            session.setAttribute("username",     user.getUsername());
            session.setAttribute("isAdmin",      user.isAdmin());
            List<Portfolio> portfolios = portfolioDao.findByUser(user.getId());
            if (!portfolios.isEmpty()) {
                session.setAttribute("portfolioId", portfolios.get(0).getId());
            }

            // 桌面客户端：签发一次性令牌，经 investory:// 深链回传到 Electron 应用，
            // 由应用用自身会话调用 /oauth/frost-id/exchange 兑换并建立登录态。
            Object desktop = session.getAttribute("frostid_client_desktop");
            if (Boolean.TRUE.equals(desktop)) {
                session.removeAttribute("frostid_client_desktop");
                String handoff = issueDesktopToken(user.getId());
                return desktopHandoffPage("investory://auth?token=" + urlEncode(handoff));
            }

            // 若此前是从 MCP OAuth 授权页跳来登录的，登录成功后回到该授权请求继续发码。
            Object pending = session.getAttribute("mcp_pending_authorize");
            if (pending instanceof String pendingUrl && !pendingUrl.isBlank()) {
                session.removeAttribute("mcp_pending_authorize");
                return redirectScript(pendingUrl);
            }

            Object returnTo = session.getAttribute("frostid_return_to");
            if (returnTo instanceof String returnToUrl && !returnToUrl.isBlank()) {
                session.removeAttribute("frostid_return_to");
                return redirectScript(returnToUrl);
            }

            return redirectScript(req.getContextPath() + "/dashboard");

        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    // ── 步骤 3（桌面端）：客户端用一次性令牌兑换会话 ──────────────────────────

    /**
     * 桌面客户端在收到 investory:// 深链后，用自身（Electron）会话调用此端点，
     * 用一次性令牌换取登录态。请求所携带的 Cookie 即写入客户端的会话罐，从而完成登录。
     */
    @GetMapping(value = "/oauth/frost-id/exchange", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String frostIdExchange(@RequestParam String token, HttpServletRequest req) {
        Long userId = consumeDesktopToken(token);
        if (userId == null) return "{\"ok\":false,\"error\":\"invalid_or_expired_token\"}";

        User user = userDao.findById(userId);
        if (user == null) return "{\"ok\":false,\"error\":\"user_not_found\"}";

        HttpSession session = req.getSession(true);
        session.setAttribute("userId",   user.getId());
        session.setAttribute("username", user.getUsername());
        session.setAttribute("isAdmin",  user.isAdmin());
        List<Portfolio> portfolios = portfolioDao.findByUser(user.getId());
        if (!portfolios.isEmpty()) {
            session.setAttribute("portfolioId", portfolios.get(0).getId());
        }
        return "{\"ok\":true}";
    }

    // ── 私有辅助方法 ──────────────────────────────────────────────────────────

    private String exchangeCode(String code, String verifier) throws Exception {
        String body = String.join("&",
                "grant_type=authorization_code",
                "code="          + urlEncode(code),
                "redirect_uri="  + urlEncode(redirectUrl),
                "client_id="     + urlEncode(clientId),
                "client_secret=" + urlEncode(clientSecret),
                "code_verifier=" + urlEncode(verifier)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new Exception("Token endpoint " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private String fetchUserInfo(String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(userinfoUrl))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new Exception("Userinfo endpoint " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /** 从 JSON 字符串中提取字符串类型的字段（简单实现，无需引入 Gson）。 */
    private String extractJsonString(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String sanitizeReturnTo(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            int port = uri.getPort();
            boolean localHost = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
            boolean http = "http".equalsIgnoreCase(scheme);
            if (localHost && http && port == 18256) {
                return uri.toString();
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private String redirectScript(String url) {
        return "<script>window.location.replace(" + jsString(url) + ")</script>";
    }

    /** 桌面端登录成功页：自动唤起 investory:// 深链返回客户端，并提供手动回退链接。 */
    private String desktopHandoffPage(String deepLink) {
        String js = jsString(deepLink);
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>登录成功</title><style>"
                + "html,body{height:100%;margin:0}body{font-family:system-ui,-apple-system,sans-serif;"
                + "background:#0f172a;color:#e2e8f0;display:flex;align-items:center;justify-content:center;text-align:center}"
                + ".card{max-width:420px;padding:32px}h2{margin:0 0 12px;font-weight:600}"
                + "p{margin:8px 0;font-size:14px;color:#94a3b8;line-height:1.6}a{color:#a5b4fc}"
                + "</style></head><body><div class=\"card\"><h2>❄ 登录成功</h2>"
                + "<p>正在返回 Investory 桌面应用…</p>"
                + "<p>若未自动跳转，请点击 <a id=\"lnk\" href=\"#\">打开 Investory</a> 并允许浏览器启动应用。</p>"
                + "<p style=\"color:#64748b;font-size:12px\">完成后可关闭此页面。</p></div>"
                + "<script>var u=" + js + ";var a=document.getElementById('lnk');a.href=u;"
                + "setTimeout(function(){window.location.href=u},300);</script>"
                + "</body></html>";
    }

    private String jsString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("<", "\\u003C")
                .replace(">", "\\u003E")
                .replace("&", "\\u0026") + "\"";
    }
}
