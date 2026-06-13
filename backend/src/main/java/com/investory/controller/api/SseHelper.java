package com.investory.controller.api;

import com.investory.server.SseClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * SSE（Server-Sent Events）推流工具类
 *
 * <p>为所有需要服务端主动推送数据的控制器提供统一的 SSE 工具方法。
 * <p>本类为 final 工具类（不可继承），所有方法均为静态方法，无需实例化。
 */
public final class SseHelper {

    private SseHelper() {}

    /**
     * Create an SseClient from the response, setting the appropriate headers.
     */
    public static SseClient createClient(HttpServletResponse resp) throws Exception {
        SseClient client = new SseClient(resp);
        client.init();
        return client;
    }

    /**
     * Send an SSE event through an SseClient.
     */
    public static void emit(SseClient client, String event, Object data) {
        client.send(event, data);
    }

    /**
     * 从请求 Session 中获取当前登录用户的 ID
     */
    public static long getUserId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return 0;
        Object uid = session.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    /**
     * 从请求 Session 中获取当前用户的活跃组合 ID
     */
    public static long getPortfolioId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return 0;
        Object pid = session.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    /**
     * 鉴权校验：若用户未登录则直接向响应写入 401 JSON 并返回 false
     */
    public static boolean requireAuth(long userId, HttpServletResponse resp) {
        if (userId == 0) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            try {
                resp.getWriter().write("{\"error\":\"unauthorized\"}");
            } catch (Exception ignored) {}
            return false;
        }
        return true;
    }
}
