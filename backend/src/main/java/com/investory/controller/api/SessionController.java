package com.investory.controller.api;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SessionController {

    @Autowired private UserDao userDao;
    @Autowired private PortfolioDao portfolioDao;

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest req) {
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
        return result;
    }

    @DeleteMapping("/account")
    public Map<String, String> deleteAccount(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("error", "not authenticated");
            return err;
        }
        Long userId = (Long) session.getAttribute("userId");
        userDao.delete(userId);
        session.invalidate();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return result;
    }
}
