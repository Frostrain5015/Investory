package com.investory.servlet;

import com.investory.dao.PortfolioDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import com.investory.service.AuthService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.thymeleaf.context.WebContext;

import java.io.IOException;
import java.util.List;

@WebServlet("/login")
public class LoginServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (getSessionUserId(req) != null) {
            resp.sendRedirect(req.getContextPath() + "/dashboard");
            return;
        }
        render("login", newCtx(req, resp), resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        String username = req.getParameter("username");
        String password = req.getParameter("password");

        try {
            User user = AuthService.get().login(username, password);
            if (user == null) {
                WebContext ctx = newCtx(req, resp);
                ctx.setVariable("error", "用户名或密码错误");
                render("login", ctx, resp);
                return;
            }
            // Set up session
            HttpSession session = req.getSession(true);
            session.setAttribute("userId",   user.getId());
            session.setAttribute("username", user.getUsername());

            // Default to first portfolio
            List<Portfolio> portfolios = PortfolioDao.get().findByUser(user.getId());
            if (!portfolios.isEmpty()) {
                session.setAttribute("portfolioId", portfolios.get(0).getId());
            }
            resp.sendRedirect(req.getContextPath() + "/dashboard");
        } catch (Exception e) {
            WebContext ctx = newCtx(req, resp);
            ctx.setVariable("error", "系统错误，请稍后重试");
            render("login", ctx, resp);
        }
    }
}
