package com.investory.controller.api;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话与账户管理控制器
 *
 * <p>负责模块：用户登录态查询、账户注销删除。
 * <p>API 基础路径：/api
 *
 * <p>该控制器通过 HttpSession 维护用户身份信息，无需 JWT Token，
 * 适用于同源 Web 应用的传统 Session 认证方式。
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    @Autowired private UserDao userDao;
    @Autowired private PortfolioDao portfolioDao;

    /**
     * 查询当前用户的登录状态
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/session
     * <p>功能说明：从服务端 Session 中读取用户信息，判断是否已认证。
     *   前端在每次页面初始化时调用，以决定是否跳转到登录页。
     *
     * <p>请求参数：无（Session 由 Cookie 隐式携带）
     *
     * <p>响应格式：
     * <pre>
     * 已登录：
     * {
     *   "userId":      Long,     // 用户数据库 ID
     *   "username":    String,   // 用户名
     *   "portfolioId": Long,     // 当前活跃组合 ID
     *   "isAdmin":     boolean,  // 是否管理员
     *   "authenticated": true
     * }
     * 未登录：
     * { "authenticated": false }
     * </pre>
     *
     * @param req HTTP 请求，用于获取 Session
     * @return 包含用户信息或未认证标志的 Map
     */
    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest req) {
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
        return result;
    }

    /**
     * 删除当前登录用户的账户
     *
     * <p>HTTP 方法：DELETE
     * <p>路径：/api/account
     * <p>功能说明：永久删除当前登录用户的所有数据，并同步注销 Session。
     *   该操作不可逆，前端应在调用前弹出二次确认。
     *
     * <p>请求参数：无（从 Session 中读取 userId）
     *
     * <p>响应格式：
     * <pre>
     * 成功：{ "status": "ok" }
     * 未登录：{ "error": "not authenticated" }
     * </pre>
     *
     * @param req HTTP 请求，用于获取 Session
     * @return 操作结果 Map
     */
    @DeleteMapping("/account")
    public Map<String, String> deleteAccount(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        // 校验规则：Session 不存在或未包含 userId 属性，视为未认证，拒绝操作
        if (session == null || session.getAttribute("userId") == null) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("error", "not authenticated");
            return err;
        }
        Long userId = (Long) session.getAttribute("userId");
        // 调用 DAO 层删除用户及其所有关联数据（级联删除由数据库外键约束保证）
        userDao.delete(userId);
        // 删除成功后立即注销 Session，使客户端 Cookie 失效
        session.invalidate();
        // 返回操作成功标志
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return result;
    }

    /**
     * 测试用登录端点：直接用用户名密码创建会话（供自动化测试脚本使用）。
     * 仅验证数据库中存在的用户，不检查 Frost ID OAuth。
     * 需要在 WebConfig 中放行路径 /api/session/test-login。
     */
    @PostMapping("/session/test-login")
    public Map<String, Object> testLogin(HttpServletRequest req) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String username = req.getParameter("username");
            String password = req.getParameter("password");
            if (username == null || password == null) {
                result.put("error", "username and password required");
                return result;
            }
            var user = userDao.findByUsername(username);
            if (user == null) {
                result.put("error", "user not found");
                return result;
            }
            // Verify password using BCrypt
            var found = userDao.findByUsername(username.trim());
            if (found == null || !org.mindrot.jbcrypt.BCrypt.checkpw(password, found.getPasswordHash())) {
                result.put("error", "invalid credentials");
                return result;
            }
            HttpSession session = req.getSession(true);
            session.setAttribute("userId", found.getId());
            session.setAttribute("username", found.getUsername());
            session.setAttribute("isAdmin", found.isAdmin());
            // Pick first portfolio
            var portfolios = portfolioDao.findByUser(found.getId());
            if (!portfolios.isEmpty()) {
                session.setAttribute("portfolioId", portfolios.get(0).getId());
            }
            result.put("authenticated", true);
            result.put("userId", found.getId());
            result.put("username", found.getUsername());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }
}
