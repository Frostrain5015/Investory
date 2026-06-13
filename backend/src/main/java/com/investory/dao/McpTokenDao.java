package com.investory.dao;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * MCP 鉴权数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code mcp_tokens}（长期 token）与 {@code mcp_auth_codes}（一次性授权码）。</p>
 *
 * <p>外部 AI 客户端通过 loopback OAuth 流程绑定：浏览器授权后后端写入一次性 code，
 * 客户端用 code 换取长期 token；token 以 SHA-256 hash 存储（不存明文），
 * 每个 token 映射到一个 Investory user_id（及绑定时的活跃组合），
 * 后续带 token 的 /api/* 请求由 LoginInterceptor 解析为该用户身份。</p>
 */
public class McpTokenDao extends BaseDao {

    /** 一次性授权码有效期（秒）。 */
    public static final long AUTH_CODE_TTL_SECONDS = 300;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** token 解析结果：携带 user 与活跃组合。 */
    public record TokenInfo(long userId, Long portfolioId, String username) {}

    // ── token hash ────────────────────────────────────────────────────────

    /** 计算 token 的 SHA-256 十六进制摘要（与存储格式一致）。 */
    public static String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 生成一个随机 URL-safe 字符串（用于 token 主体与一次性 code）。 */
    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    // ── 一次性授权码 ─────────────────────────────────────────────────────

    /**
     * 写入一次性授权码（浏览器授权成功后调用）。
     *
     * @param code         随机码
     * @param userId       授权用户
     * @param portfolioId  当前活跃组合（可空）
     * @param redirectPort 本地 loopback 回调端口
     */
    public void insertAuthCode(String code, long userId, Long portfolioId, int redirectPort) {
        update("INSERT INTO mcp_auth_codes (code, user_id, portfolio_id, redirect_port) VALUES (?, ?, ?, ?)",
                code, userId, portfolioId, redirectPort);
    }

    /**
     * 消费一次性授权码：校验存在、未消费、未过期，标记已消费并返回身份。
     *
     * @param code 客户端回传的授权码
     * @return 身份信息，校验失败时返回 {@code null}
     */
    public TokenInfo consumeAuthCode(String code) {
        TokenInfo info = queryOne(
                "SELECT ac.user_id, ac.portfolio_id, u.username FROM mcp_auth_codes ac " +
                "JOIN users u ON ac.user_id = u.id " +
                "WHERE ac.code = ? AND ac.consumed = 0 " +
                "AND ac.created_at >= (NOW() - INTERVAL ? SECOND)",
                this::mapTokenInfo, code, AUTH_CODE_TTL_SECONDS);
        if (info == null) return null;
        // 标记已消费（即使后续 token 写入失败也不允许复用）
        update("UPDATE mcp_auth_codes SET consumed = 1 WHERE code = ?", code);
        return info;
    }

    // ── 长期 token ───────────────────────────────────────────────────────

    /**
     * 签发并存储一个新的长期 token。
     *
     * @param userId      映射用户
     * @param portfolioId 绑定时活跃组合（可空）
     * @param label       客户端标识
     * @return 明文 token（仅此一次返回，数据库只存 hash）
     */
    public String issueToken(long userId, Long portfolioId, String label) {
        return issueToken(userId, portfolioId, label, null);
    }

    /**
     * 签发 token，并可绑定 audience（MCP 资源 URI，OAuth 流程用）。
     *
     * @param audience 该 token 仅可用于此 MCP 资源（null 表示不限定）
     */
    public String issueToken(long userId, Long portfolioId, String label, String audience) {
        String token = "sk-investory-" + randomToken(32);
        update("INSERT INTO mcp_tokens (token_hash, user_id, portfolio_id, label, audience) VALUES (?, ?, ?, ?, ?)",
                hashToken(token), userId, portfolioId, label, audience);
        return token;
    }

    /**
     * 按明文 token 解析身份（用于 LoginInterceptor 与 /mcp/whoami）。
     *
     * @param token 明文 Bearer token
     * @return 身份信息，无效/已吊销时返回 {@code null}
     */
    public TokenInfo resolveToken(String token) {
        if (token == null || token.isBlank()) return null;
        return queryOne(
                "SELECT t.user_id, t.portfolio_id, u.username FROM mcp_tokens t " +
                "JOIN users u ON t.user_id = u.id " +
                "WHERE t.token_hash = ? AND t.revoked = 0",
                this::mapTokenInfo, hashToken(token));
    }

    /** 更新 token 的最近使用时间（whoami / 周期性调用时）。 */
    public void touch(String token) {
        update("UPDATE mcp_tokens SET last_used_at = NOW() WHERE token_hash = ? AND revoked = 0",
                hashToken(token));
    }

    private TokenInfo mapTokenInfo(ResultSet rs) throws SQLException {
        long uid = rs.getLong("user_id");
        long pid = rs.getLong("portfolio_id");
        Long portfolioId = rs.wasNull() ? null : pid;
        return new TokenInfo(uid, portfolioId, rs.getString("username"));
    }

    // ── token 自助管理（设置页 /api/mcp/tokens）───────────────────────────

    /** 列出某用户的有效 token（不含明文，只给展示用元信息）。 */
    public List<Map<String, Object>> listTokens(long userId) {
        return queryForList(
                "SELECT id, label, created_at, last_used_at FROM mcp_tokens " +
                "WHERE user_id = ? AND revoked = 0 ORDER BY created_at DESC", userId);
    }

    /** 吊销某用户名下指定 token（归属校验防越权）。返回是否生效。 */
    public boolean revokeToken(long id, long userId) {
        return update("UPDATE mcp_tokens SET revoked = 1 WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    // ── OAuth：动态客户端注册（RFC7591，公共客户端）─────────────────────

    /** 注册一个公共客户端，返回生成的 client_id。 */
    public String registerClient(String redirectUris, String clientName) {
        String clientId = "mcpc_" + randomToken(18);
        update("INSERT INTO mcp_oauth_clients (client_id, redirect_uris, client_name) VALUES (?, ?, ?)",
                clientId, redirectUris, clientName);
        return clientId;
    }

    /** 返回客户端的换行分隔 redirect_uris，不存在返回 null。 */
    public String clientRedirectUris(String clientId) {
        List<String> rows = queryForList(
                "SELECT redirect_uris FROM mcp_oauth_clients WHERE client_id = ?", String.class, clientId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── OAuth：授权码（PKCE）──────────────────────────────────────────────

    /** 授权码解析结果。 */
    public record AuthCode(String clientId, long userId, Long portfolioId, String redirectUri,
                           String codeChallenge, String resource) {}

    public void insertOAuthCode(String code, String clientId, long userId, Long portfolioId,
                                String redirectUri, String codeChallenge, String resource) {
        update("INSERT INTO mcp_oauth_codes (code, client_id, user_id, portfolio_id, redirect_uri, code_challenge, resource) " +
               "VALUES (?, ?, ?, ?, ?, ?, ?)",
                code, clientId, userId, portfolioId, redirectUri, codeChallenge, resource);
    }

    /** 消费授权码：校验存在、未消费、未过期（复用 AUTH_CODE_TTL_SECONDS），标记已消费。 */
    public AuthCode consumeOAuthCode(String code) {
        AuthCode ac = queryOne(
                "SELECT client_id, user_id, portfolio_id, redirect_uri, code_challenge, resource " +
                "FROM mcp_oauth_codes WHERE code = ? AND consumed = 0 " +
                "AND created_at >= (NOW() - INTERVAL ? SECOND)",
                rs -> {
                    long pid = rs.getLong("portfolio_id");
                    Long portfolioId = rs.wasNull() ? null : pid;
                    return new AuthCode(rs.getString("client_id"), rs.getLong("user_id"), portfolioId,
                            rs.getString("redirect_uri"), rs.getString("code_challenge"), rs.getString("resource"));
                }, code, AUTH_CODE_TTL_SECONDS);
        if (ac == null) return null;
        update("UPDATE mcp_oauth_codes SET consumed = 1 WHERE code = ?", code);
        return ac;
    }
}
