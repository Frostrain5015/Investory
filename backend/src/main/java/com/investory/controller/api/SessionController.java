package com.investory.controller.api;

import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话与账户管理控制器
 *
 * <p>负责模块：用户登录态查询。
 * <p>API 基础路径：/api
 *
 * <p>该控制器通过 HttpSession 维护用户身份信息，无需 JWT Token，
 * 适用于同源 Web 应用的传统 Session 认证方式。
 */
public class SessionController {

    /**
     * 查询当前用户的登录状态
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/session
     * <p>功能说明：从服务端 Session 中读取用户信息，判断是否已认证。
     *   前端在每次页面初始化时调用，以决定是否跳转到登录页。
     *
     * @param req HTTP 请求，用于获取 Session
     * @param resp HTTP 响应
     */
    public void handleGetSession(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        // getSession(false) 不自动创建新 Session，避免为匿名请求分配 Session 对象
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            // Session 存在且 userId 不为 null，表示用户已完成登录
            result.put("userId",   session.getAttribute("userId"));
            result.put("username", session.getAttribute("username"));
            result.put("portfolioId", session.getAttribute("portfolioId"));
            // isAdmin 使用 Boolean.TRUE.equals 进行安全比较，防止 null 造成 NPE
            result.put("isAdmin",  Boolean.TRUE.equals(session.getAttribute("isAdmin")));
            result.put("authenticated", true);
        } else {
            // Session 不存在或 userId 为 null，用户未登录
            result.put("authenticated", false);
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    /**
     * Handles test login and register (from RouteRegistrar).
     */
    public void handleTestLogin(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"error\":\"登录需通过 Frost ID OAuth\"}");
    }
}
