package com.investory.controller;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    public String frostIdLogin(HttpServletRequest req) throws Exception {
        String verifier = generateCodeVerifier();
        String challenge = generateCodeChallenge(verifier);
        String state     = generateState();

        HttpSession session = req.getSession(true);
        session.setAttribute("frostid_verifier", verifier);
        session.setAttribute("frostid_state",    state);

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

    @GetMapping("/oauth/frost-id/callback")
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

            // 若此前是从 MCP OAuth 授权页跳来登录的，登录成功后回到该授权请求继续发码。
            Object pending = session.getAttribute("mcp_pending_authorize");
            if (pending instanceof String pendingUrl && !pendingUrl.isBlank()) {
                session.removeAttribute("mcp_pending_authorize");
                return "<script>window.location.href='" + pendingUrl.replace("'", "%27") + "'</script>";
            }

            return "<script>window.location.href='" + req.getContextPath() + "/dashboard'</script>";

        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
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
}
