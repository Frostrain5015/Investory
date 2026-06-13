package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SSE（Server-Sent Events）推流工具类
 *
 * <p>负责模块：为所有需要服务端主动推送数据的控制器提供统一的 SSE 工具方法。
 * <p>本类为 final 工具类（不可继承），所有方法均为静态方法，无需实例化。
 *
 * <p>SSE 机制说明：
 *   服务端通过 {@link SseEmitter} 向客户端建立持久 HTTP 连接，
 *   以 text/event-stream 格式逐条推送 JSON 事件；客户端（浏览器）
 *   使用 EventSource API 监听，无需轮询即可实时接收数据。
 *   连接由客户端主动关闭或服务端调用 emitter.complete() 结束。
 */
public final class SseHelper {

    /** Jackson 序列化器，用于将 Java 对象转为 JSON 字符串后写入 SSE 数据帧 */
    private static final ObjectMapper json = new ObjectMapper();

    /** 工具类禁止实例化 */
    private SseHelper() {}

    /**
     * 向客户端推送一个具名 SSE 事件
     *
     * <p>SSE 推流机制：
     *   调用 {@link SseEmitter#send} 将数据序列化为 JSON 字符串，
     *   封装成格式为 {@code event: <name>\ndata: <json>\n\n} 的文本帧，
     *   通过已建立的持久 HTTP 连接实时发送至浏览器端 EventSource。
     *
     * <p>异常处理：客户端主动断开连接后，emitter.send() 会抛出异常，
     *   此时连接已失效，安全忽略即可，无需额外清理。
     *
     * @param emitter SSE 推送器，代表一个持久客户端连接
     * @param event   事件名称，客户端通过 addEventListener(event, ...) 订阅
     * @param data    事件数据载体，将被序列化为 JSON 字符串
     */
    public static void emit(SseEmitter emitter, String event, Object data) {
        try {
            // 构建命名事件帧：name 字段供客户端按事件类型过滤，data 字段为 JSON 正文
            emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(data)));
        } catch (Exception e) {
            // SSE connection likely closed by client — safe to ignore
        }
    }

    /**
     * 从请求 Session 中获取当前登录用户的 ID
     *
     * <p>校验规则：Session 不存在或未携带 userId 属性时，返回 0 表示未认证。
     *   调用方应检查返回值是否为 0 来决定是否拒绝请求。
     *
     * @param req HTTP 请求
     * @return 用户 ID（未登录时返回 0）
     */
    public static long getUserId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        // getSession(false) 不创建新 Session，未登录时返回 null
        if (session == null) return 0;
        Object uid = session.getAttribute("userId");
        // instanceof Number 兼容 Integer / Long 两种存储类型，统一转为 long
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    /**
     * 从请求 Session 中获取当前用户的活跃组合 ID
     *
     * <p>校验规则：Session 不存在或未携带 portfolioId 属性时，返回 0。
     *
     * @param req HTTP 请求
     * @return 当前组合 ID（未选择组合时返回 0）
     */
    public static long getPortfolioId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return 0;
        Object pid = session.getAttribute("portfolioId");
        // 同 getUserId，用 instanceof Number 兼容多种数值类型
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    /**
     * 鉴权校验：若用户未登录则直接向响应写入 401 JSON 并返回 false
     *
     * <p>功能说明：供各 Controller 方法在执行业务逻辑前调用，
     *   统一处理未认证场景，避免重复编写鉴权代码。
     *
     * <p>响应数据：HTTP 状态码 401，Content-Type: application/json，
     *   正文：{"error":"unauthorized"}
     *
     * @param userId 从 Session 读取的用户 ID，为 0 表示未登录
     * @param resp   HTTP 响应对象，用于写入 401 错误
     * @return true 表示已认证（可继续处理），false 表示未认证（响应已写入）
     */
    /** Write 401 JSON response when user is not authenticated. Returns true if unauthenticated. */
    public static boolean requireAuth(long userId, jakarta.servlet.http.HttpServletResponse resp) {
        if (userId == 0) {
            // 用户未登录，设置 401 状态码并写入 JSON 错误体
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            try {
                resp.getWriter().write("{\"error\":\"unauthorized\"}");
            } catch (Exception ignored) {}
            return false;
        }
        return true;
    }

    /**
     * 构建未认证错误响应 Map
     *
     * <p>功能说明：用于返回 Map 类型响应的接口方法中，快速生成标准 401 错误结构。
     *
     * @return 包含 error 字段的不可变 Map：{"error": "unauthorized"}
     */
    public static Map<String, Object> unauthorized() {
        return Map.of("error", "unauthorized");
    }
}
