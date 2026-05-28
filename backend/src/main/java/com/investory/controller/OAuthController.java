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
 * <p>处理 Frost ID 授权服务器的 OAuth 2.1 Authorization Code + PKCE 流程。
 * 用户点击"Frost ID 登录"后重定向至 Frost ID 授权页面，
 * 授权成功后通过回调完成用户身份绑定与本地 Session 创建。</p>
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

    @Value("${frostid.oauth.redirect-url}")
    private String redirectUrl;

    @Autowired private UserDao userDao;
    @Autowired private PortfolioDao portfolioDao;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成 PKCE code_verifier（43-128 字符的随机字符串）。
     */
    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 对 code_verifier 进行 SHA-256 哈希并返回 base64url 编码的 code_challenge。
     */
    private String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    /**
     * 生成随机 state 参数用于 CSRF 保护。
     */
    private String generateState() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 步骤 1：重定向至 Frost ID 授权页面。
     *
     * <p>生成 PKCE code_verifier 和 state，存入 Session 后重定向。
     */
    @GetMapping("/oauth/frost-id/login")
    public String frostIdLogin(HttpServletRequest req) throws Exception {
        String verifier = generateCodeVerifier();
        String challenge = generateCodeChallenge(verifier);
        String state = generateState();

        HttpSession session = req.getSession(true);
        session.setAttribute("frostid_verifier", verifier);
        session.setAttribute("frostid_state", state);

        String params = String.join("&",
                "response_type=code",
                "client_id=" + urlEncode(clientId),
                "redirect_uri=" + urlEncode(redirectUrl),
                "code_challenge=" + urlEncode(challenge),
                "code_challenge_method=S256",
                "state=" + urlEncode(state),
                "scope=openid+profile+email"
        );

        return "redirect:" + authorizeUrl + "?" + params;
    }

    /**
     * 步骤 2：Frost ID 授权回调处理。
     *
     * <p>验证 state，用授权码换取令牌，从 ID Token 提取用户信息，
     * 查找或创建本地用户，建立 Session。
     */
    @GetMapping("/oauth/frost-id/callback")
    @ResponseBody
    public String frostIdCallback(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            HttpServletRequest req) {

        HttpSession session = req.getSession(false);
        if (session == null) return "error: session expired";

        // 验证 state（CSRF 保护）
        String savedState = (String) session.getAttribute("frostid_state");
        if (savedState == null || !savedState.equals(state)) {
            session.invalidate();
            return "error: invalid state";
        }

        // 取出 PKCE verifier
        String verifier = (String) session.getAttribute("frostid_verifier");
        session.removeAttribute("frostid_state");
        session.removeAttribute("frostid_verifier");

        try {
            // 用授权码换令牌
            String tokenResponse = exchangeCode(code, verifier);
            if (tokenResponse == null) return "error: token exchange failed";

            // 解析 ID Token 中的用户信息
            String idToken = extractIdToken(tokenResponse);
            if (idToken == null) return "error: no id_token";

            String email = extractClaim(idToken, "email");
            String name = extractClaim(idToken, "name");
            String frostIdUserId = extractClaim(idToken, "sub");
            if (email == null || frostIdUserId == null) return "error: missing user info";

            // 按 Frost ID 用户 ID 查找已有绑定
            User user = userDao.findByFrostIdId(frostIdUserId);
            if (user == null) {
                // 无绑定：按邮箱查找已有账户
                user = userDao.findByEmail(email);
                if (user == null) {
                    // 创建新用户
                    user = new User();
                    user.setUsername(email.split("@")[0]);
                    user.setEmail(email);
                    user.setPasswordHash(""); // OAuth 用户，无需密码
                    user.setFrostIdId(frostIdUserId);
                    long userId = userDao.insert(user);
                    user.setId(userId);

                    // 创建默认投资组合
                    Portfolio portfolio = new Portfolio();
                    portfolio.setUserId(userId);
                    portfolio.setName("我的投资组合");
                    portfolioDao.insert(portfolio);
                } else {
                    // 已有账户，关联 Frost ID
                    userDao.updateFrostIdId(user.getId(), frostIdUserId);
                }
            }

            // 建立 Session
            session.setAttribute("userId", user.getId());
            session.setAttribute("username", user.getUsername());
            session.setAttribute("isAdmin", user.isAdmin());
            List<Portfolio> portfolios = portfolioDao.findByUser(user.getId());
            if (!portfolios.isEmpty()) {
                session.setAttribute("portfolioId", portfolios.get(0).getId());
            }

            // 重定向到首页
            return "<script>window.location.href='" + req.getContextPath() + "/dashboard'</script>";

        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * 用授权码 + PKCE verifier 换取令牌。
     */
    private String exchangeCode(String code, String verifier) throws Exception {
        String body = String.join("&",
                "grant_type=authorization_code",
                "code=" + urlEncode(code),
                "redirect_uri=" + urlEncode(redirectUrl),
                "client_id=" + urlEncode(clientId),
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

        if (response.statusCode() != 200) return null;
        return response.body();
    }

    /**
     * 从令牌响应中提取 ID Token（JWT）。
     *
     * <p>响应格式：{"access_token":"...","id_token":"...","token_type":"Bearer",...}
     */
    private String extractIdToken(String jsonResponse) {
        // 简单 JSON 解析（不使用 Gson，避免引入新依赖）
        String key = "\"id_token\":\"";
        int start = jsonResponse.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = jsonResponse.indexOf("\"", start);
        return end > start ? jsonResponse.substring(start, end) : null;
    }

    /**
     * 从 JWT ID Token 中提取指定 claim。
     *
     * <p>JWT 格式：header.payload.signature。payload 是 base64url 编码的 JSON。
     */
    private String extractClaim(String jwt, String claim) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8);

            String key = "\"" + claim + "\":\"";
            int start = payload.indexOf(key);
            if (start < 0) {
                // 尝试非字符串类型（数字、布尔）
                String key2 = "\"" + claim + "\":";
                int start2 = payload.indexOf(key2);
                if (start2 < 0) return null;
                int end2 = payload.indexOf(",", start2);
                if (end2 < 0) end2 = payload.indexOf("}", start2);
                return payload.substring(start2 + key2.length(), end2).replace("\"", "");
            }
            start += key.length();
            int end = payload.indexOf("\"", start);
            return end > start ? payload.substring(start, end) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
