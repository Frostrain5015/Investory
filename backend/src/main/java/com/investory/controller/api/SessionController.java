package com.investory.controller.api;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import com.investory.server.AppContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话与账户管理控制器
 */
public class SessionController {

    private final UserDao userDao = AppContext.get(UserDao.class);
    private final PortfolioDao portfolioDao = AppContext.get(PortfolioDao.class);

    public void handleGetSession(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            result.put("userId",   session.getAttribute("userId"));
            result.put("username", session.getAttribute("username"));
            result.put("portfolioId", session.getAttribute("portfolioId"));
            result.put("isAdmin",  Boolean.TRUE.equals(session.getAttribute("isAdmin")));
            result.put("authenticated", true);
        } else {
            result.put("authenticated", false);
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(com.investory.util.JsonUtil.toJson(result));
    }

    public void handleTestLogin(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String username = req.getParameter("username");
            String password = req.getParameter("password");
            if (username == null || password == null) {
                result.put("error", "username and password required");
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(com.investory.util.JsonUtil.toJson(result));
                return;
            }
            var found = userDao.findByUsername(username.trim());
            if (found == null || !org.mindrot.jbcrypt.BCrypt.checkpw(password, found.getPasswordHash())) {
                result.put("error", "invalid credentials");
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(com.investory.util.JsonUtil.toJson(result));
                return;
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
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(com.investory.util.JsonUtil.toJson(result));
    }
}
