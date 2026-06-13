package com.investory.controller.api;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试 / 开发环境专用登录控制器。
 *
 * <p>仅在 Spring profile 为 {@code test} 或 {@code dev} 时注册（{@link Profile}）。
 * 生产环境（默认 profile）下该 Bean 不会被创建，端点 {@code /api/session/test-login}
 * 也就不存在，从根本上避免被外部探测利用。
 *
 * <p>用途：自动化测试脚本用用户名+密码直接建立会话，绕过 Frost ID OAuth 浏览器流程。
 * 仍走 BCrypt 密码校验，并非无鉴权后门。
 */
@RestController
@RequestMapping("/api")
@Profile({"test", "dev"})
public class TestLoginController {

    @Autowired private UserDao userDao;
    @Autowired private PortfolioDao portfolioDao;

    /**
     * 测试用登录端点：用用户名密码创建会话。
     * 需要在 WebConfig 中放行路径 /api/session/test-login（仅 test/dev profile 下生效）。
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
            var found = userDao.findByUsername(username.trim());
            if (found == null || !org.mindrot.jbcrypt.BCrypt.checkpw(password, found.getPasswordHash())) {
                result.put("error", "invalid credentials");
                return result;
            }
            HttpSession session = req.getSession(true);
            session.setAttribute("userId", found.getId());
            session.setAttribute("username", found.getUsername());
            session.setAttribute("isAdmin", found.isAdmin());
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
