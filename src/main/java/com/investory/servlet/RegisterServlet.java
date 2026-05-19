package com.investory.servlet;

import com.investory.service.AuthService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.thymeleaf.context.WebContext;

import java.io.IOException;

@WebServlet("/register")
public class RegisterServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        render("register", newCtx(req, resp), resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        String username = req.getParameter("username");
        String password = req.getParameter("password");
        String email    = req.getParameter("email");

        try {
            String error = AuthService.get().register(username, password, email);
            if (error != null) {
                WebContext ctx = newCtx(req, resp);
                ctx.setVariable("error", error);
                ctx.setVariable("username", username);
                ctx.setVariable("email", email);
                render("register", ctx, resp);
                return;
            }
            resp.sendRedirect(req.getContextPath() + "/login?registered=1");
        } catch (Exception e) {
            WebContext ctx = newCtx(req, resp);
            ctx.setVariable("error", "注册失败：" + e.getMessage());
            render("register", ctx, resp);
        }
    }
}
