package com.investory.controller.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SessionController {

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest req) {
        Map<String, Object> result = new LinkedHashMap<>();
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            result.put("userId",   session.getAttribute("userId"));
            result.put("username", session.getAttribute("username"));
            result.put("portfolioId", session.getAttribute("portfolioId"));
            result.put("authenticated", true);
        } else {
            result.put("authenticated", false);
        }
        return result;
    }
}
