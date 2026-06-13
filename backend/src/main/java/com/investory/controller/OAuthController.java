package com.investory.controller;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import com.investory.server.AppContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

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
 */
public class OAuthController {

    private final String clientId = System.getProperty("frostid.oauth.client-id", "");
    private final String clientSecret = System.getProperty("frostid.oauth.client-secret", "");
    private final String authorizeUrl = System.getProperty("frostid.oauth.authorize-url", "");
    private final String tokenUrl = System.getProperty("frostid.oauth.token-url", "");
    private final String userinfoUrl = System.getProperty("frostid.oauth.userinfo-url", "");
    private final String redirectUrl = System.getProperty("frostid.oauth.redirect-url", "");

    private final UserDao userDao = AppContext.get(UserDao.class);
    private final PortfolioDao portfolioDao = AppContext.get(PortfolioDao.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SecureRandom secureRandom = new SecureRandom();

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

    private Long consumeDesktopToken(String token) {
        if (token == null || token.isBlank()) return null;
        DesktopToken dt = desktopTokens.remove(token);
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

    public void handleFrostIdLogin(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String returnTo = req.getParameter("return_to");
        String client = req.getParameter("client");

        String verifier = generateCodeVerifier();
        String challenge = generateCodeChallenge(verifier);
        String state     = generateState();

        HttpSession session = req.getSession(true);
        session.setAttribute("frostid_verifier", verifier);
        session.setAttribute("frostid_state",    state);
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
        resp.sendRedirect(authorizeUrl + "?" + params);
    }

    public void handleFrostIdCallback(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String code = req.getParameter("code");
        String state = req.getParameter("state");

        HttpSession session = req.getSession(false);
        if (session == null) {
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().write("error: session expired");
            return;
        }

        String savedState = (String) session.getAttribute("frostid_state");
        if (savedState == null || !savedState.equals(state)) {
            session.invalidate();
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().write("error: invalid state");
            return;
        }

        String verifier = (String) session.getAttribute("frostid_verifier");
        session.removeAttribute("frostid_state");
        session.removeAttribute("frostid_verifier");

        try {
            String tokenBody = exchangeCode(code, verifier);
            String accessToken = extractJsonString(tokenBody, "access_token");
            if (accessToken == null) {
                resp.setContentType("text/html;charset=UTF-8");
                resp.getWriter().write("error: no access_token in response: " + tokenBody);
                return;
            }

            String userInfoBody = fetchUserInfo(accessToken);
            String frostIdUserId = extractJsonString(userInfoBody, "sub");
            String email         = extractJsonString(userInfoBody, "email");
            String username      = extractJsonString(userInfoBody, "preferred_username");

            if (frostIdUserId == null) {
                resp.setContentType("text/html;charset=UTF-8");
                resp.getWriter().write("error: no sub in userinfo: " + userInfoBody);
                return;
            }
            if (email == null) email = username + "@frost-id.local";

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

            session.setAttribute("userId",      user.getId());
            session.setAttribute("username",     user.getUsername());
            session.setAttribute("isAdmin",      user.isAdmin());
            List<Portfolio> portfolios = portfolioDao.findByUser(user.getId());
            if (!portfolios.isEmpty()) {
                session.setAttribute("portfolioId", portfolios.get(0).getId());
            }

            Object desktop = session.getAttribute("frostid_client_desktop");
            if (Boolean.TRUE.equals(desktop)) {
                session.removeAttribute("frostid_client_desktop");
                String handoff = issueDesktopToken(user.getId());
                resp.setContentType("text/html;charset=UTF-8");
                resp.getWriter().write(desktopHandoffPage("investory://auth?token=" + urlEncode(handoff)));
                return;
            }

            Object pending = session.getAttribute("mcp_pending_authorize");
            if (pending instanceof String pendingUrl && !pendingUrl.isBlank()) {
                session.removeAttribute("mcp_pending_authorize");
                resp.setContentType("text/html;charset=UTF-8");
                resp.getWriter().write(redirectScript(pendingUrl));
                return;
            }

            Object returnTo = session.getAttribute("frostid_return_to");
            if (returnTo instanceof String returnToUrl && !returnToUrl.isBlank()) {
                session.removeAttribute("frostid_return_to");
                resp.setContentType("text/html;charset=UTF-8");
                resp.getWriter().write(redirectScript(returnToUrl));
                return;
            }

            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().write(redirectScript(req.getContextPath() + "/dashboard"));

        } catch (Exception e) {
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().write("error: " + e.getMessage());
        }
    }

    public void handleFrostIdExchange(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String token = req.getParameter("token");
        Long userId = consumeDesktopToken(token);
        if (userId == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"ok\":false,\"error\":\"invalid_or_expired_token\"}");
            return;
        }
        User user = userDao.findById(userId);
        if (user == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"ok\":false,\"error\":\"user_not_found\"}");
            return;
        }
        HttpSession session = req.getSession(true);
        session.setAttribute("userId",   user.getId());
        session.setAttribute("username", user.getUsername());
        session.setAttribute("isAdmin",  user.isAdmin());
        List<Portfolio> portfolios = portfolioDao.findByUser(user.getId());
        if (!portfolios.isEmpty()) {
            session.setAttribute("portfolioId", portfolios.get(0).getId());
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"ok\":true}");
    }

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
            if (localHost && http && port == 18256) return uri.toString();
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    private String redirectScript(String url) {
        return "<script>window.location.replace(" + jsString(url) + ")</script>";
    }

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
