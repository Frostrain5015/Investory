package com.investory.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 写操作两步确认存储。
 *
 * <p>外部 MCP 客户端没有「观澜」的 Accept/Refuse UI，所以每个写工具拆成两步：
 * 第一步 {@link #stage} 校验参数并返回 confirm_token（不写入）；
 * 第二步 {@code confirm_action(confirm_token)} 才真正执行。</p>
 *
 * <p>待定操作存内存、带 TTL，并**按 userId 隔离**——确认时校验 token 所属用户与暂存时一致，
 * 防止越权确认他人暂存的写。</p>
 */
public class McpConfirmStore {

    private static final long TTL_MS = 10 * 60 * 1000;  // 10 分钟
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 一个待确认的写：HTTP 方法 + /api 路径 + body（form 或 json，二选一）+ 归属 user + 摘要 + 过期。 */
    public record Pending(long userId, String method, String path, String form, String json,
                          String summary, long expiresAt) {}

    /** 执行器：用调用者 token 真正发起内部写请求。 */
    @FunctionalInterface
    public interface Executor {
        JsonNode run(Pending pending) throws Exception;
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private void gc() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    /**
     * 暂存一个写操作，返回给模型的确认提示（含 confirm_token）。
     *
     * @return Map：status=confirmation_required, confirm_token, summary, note
     */
    public Map<String, Object> stage(long userId, String method, String path, String form, String json, String summary) {
        gc();
        String token = "cfm_" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomBytes());
        pending.put(token, new Pending(userId, method, path, form, json, summary,
                System.currentTimeMillis() + TTL_MS));
        return Map.of(
                "status", "confirmation_required",
                "confirm_token", token,
                "summary", summary,
                "note", "这是写操作，尚未执行。请调用 confirm_action(confirm_token) 确认，或忽略以取消。"
        );
    }

    /**
     * 确认并执行一个暂存的写操作。
     *
     * @param token   confirm_token
     * @param userId  当前调用者（必须与暂存时一致）
     * @param exec    实际执行逻辑（由 registry 用调用者 token 发内部 REST）
     */
    public Object confirm(String token, long userId, Executor exec) {
        gc();
        Pending p = pending.remove(token);
        if (p == null) {
            return Map.of("status", "error", "error", "confirm_token 无效或已过期，请重新发起写操作。");
        }
        if (p.userId() != userId) {
            return Map.of("status", "error", "error", "confirm_token 不属于当前用户。");
        }
        try {
            JsonNode result = exec.run(p);
            return Map.of("status", "done", "summary", p.summary(), "result", result);
        } catch (Exception e) {
            String msg = e.getMessage();
            return Map.of("status", "error", "summary", p.summary(),
                    "error", msg != null && msg.length() > 300 ? msg.substring(0, 300) : String.valueOf(msg));
        }
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return b;
    }
}
