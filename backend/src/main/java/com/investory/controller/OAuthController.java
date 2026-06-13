package com.investory.controller;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import com.investory.server.AppContext;
import com.investory.server.ConfigLoader;
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

public class OAuthController {

    private final String clientId = ConfigLoader.get("frostid.oauth.client-id");
    private final String clientSecret = ConfigLoader.get("frostid.oauth.client-secret");
    private final String authorizeUrl = ConfigLoader.get("frostid.oauth.authorize-url");
    private final String tokenUrl = ConfigLoader.get("frostid.oauth.token-url");
    private final String userinfoUrl = ConfigLoader.get("frostid.oauth.userinfo-url");
    private final String redirectUrl = ConfigLoader.get("frostid.oauth.redirect-url");

    private final UserDao userDao = AppContext.get(UserDao.class);
    private final PortfolioDao portfolioDao = AppContext.get(PortfolioDao.class);

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

    public void handleFrostIdLogin(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String returnTo = req.getParameter("return_to");
        String verifier = generateCodeVerifier();
        String challenge = generateCodeChallenge(verifier);
        String state = generateState();

        HttpSession session = req.getSession(true);
        session.setAttribute("frostid_verifier", verifier);
        session.setAttribute("frostid_state", state);
        String safeReturnTo = sanitizeReturnTo(returnTo);
        if (safeReturnTo != null) session.setAttribute("frostid_return_to", safeReturnTo);
        else session.removeAttribute("frostid_return_to");

        String params = String.join("&",
                "response_type=code",
                "client_id=" + urlEncode(clientId),
                "redirect_uri=" + urlEncode(redirectUrl),
                "code_challenge=" + urlEncode(challenge),
                "code_challenge_method=S256",
                "state=" + urlEncode(state),
                "scope=openid%20profile%20email"
        );
        resp.sendRedirect(authorizeUrl + "?" + params);
    }

    public void handleFrostIdCallback(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String code = req.getParameter("code");
        String state = req.getParameter("state");

        HttpSession session = req.getSession(false);
        if (session == null) { resp.setContentType("text/html;charset=UTF-8"); resp.getWriter().write("error: session expired"); return; }

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
            String email = extractJsonString(userInfoBody, "email");
            String username = extractJsonString(userInfoBody, "preferred_username");

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

            session.setAttribute("userId", user.getId());
            session.setAttribute("username", user.getUsername());
            session.setAttribute("isAdmin", user.isAdmin());
            List<Portfolio> portfolios = portfolioDao.findByUser(user.getId());
            if (!portfolios.isEmpty()) session.setAttribute("portfolioId", portfolios.get(0).getId());

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
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new Exception("Token endpoint " + response.statusCode() + ": " + response.body());
        return response.body();
    }

    private String fetchUserInfo(String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(userinfoUrl))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new Exception("Userinfo endpoint " + response.statusCode() + ": " + response.body());
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

    private static String urlEncode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

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
        } catch (IllegalArgumentException ignored) { return null; }
        return null;
    }

    private String redirectScript(String url) {
        return "<script>window.location.replace(" + jsString(url) + ")</script>";
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
