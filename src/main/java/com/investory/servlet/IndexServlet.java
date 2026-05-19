package com.investory.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/")
public class IndexServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long userId = getSessionUserId(req);
        if (userId != null) {
            resp.sendRedirect(req.getContextPath() + "/dashboard");
        } else {
            resp.sendRedirect(req.getContextPath() + "/login");
        }
    }
}
