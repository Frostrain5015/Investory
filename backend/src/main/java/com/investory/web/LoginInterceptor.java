package com.investory.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录状态拦截器。
 *
 * <p>在每次 HTTP 请求到达 Controller 之前执行校验，确保只有已登录用户才能访问受保护资源。
 * 该拦截器由 {@link WebConfig#addInterceptors} 注册，并通过路径白名单排除公开端点。
 *
 * <p>拦截逻辑分为三种情形：
 * <ol>
 *   <li>CORS 预检请求（OPTIONS 方法）— 直接放行，避免浏览器跨域握手被拦截。</li>
 *   <li>会话有效（Session 中存在 {@code userId}）— 放行，请求继续流转到 Controller。</li>
 *   <li>未登录：
 *     <ul>
 *       <li>API 请求（路径以 {@code /api/} 开头）— 返回 HTTP 401 + JSON 错误体，
 *           让前端可统一处理未授权响应（如跳转登录页）。</li>
 *       <li>普通页面请求 — 302 重定向到应用根路径（即 React SPA 登录入口）。</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 请求预处理：在 Controller 方法执行前进行登录状态校验。
     *
     * <p>返回 {@code true} 表示放行，请求继续向下传递；
     * 返回 {@code false} 表示中断，响应已由本方法直接写出，框架不再调用 Controller。
     *
     * @param req     当前 HTTP 请求对象，用于读取方法、路径、Session 等信息
     * @param resp    当前 HTTP 响应对象，用于写出 401 状态码或重定向
     * @param handler 即将执行的处理器（通常为 Controller 方法的包装对象），本方法未使用
     * @return {@code true} 放行；{@code false} 终止请求处理
     * @throws Exception 写出响应时若发生 IO 异常则向上抛出
     */
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        // CORS 预检请求（浏览器在正式跨域请求前自动发送 OPTIONS），直接放行，无需鉴权
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;

        // 读取已有 Session（false 表示不主动创建新 Session），检查 userId 属性是否存在
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("userId") != null) return true;

        // 未登录：针对 API 接口返回 JSON 格式的 401 响应，前端可据此统一跳转登录
        if (req.getRequestURI().startsWith(req.getContextPath() + "/api/")) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"unauthorized\"}");
            return false;
        }
        // 未登录：普通页面请求重定向到根路径，由 React SPA 负责渲染登录界面
        resp.sendRedirect(req.getContextPath() + "/");
        return false;
    }
}
