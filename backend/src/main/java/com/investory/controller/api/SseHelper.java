package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

public final class SseHelper {

    private static final ObjectMapper json = new ObjectMapper();

    private SseHelper() {}

    public static void emit(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(data)));
        } catch (Exception e) {
            // SSE connection likely closed by client — safe to ignore
        }
    }

    public static long getUserId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return 0;
        Object uid = session.getAttribute("userId");
        return uid instanceof Number ? ((Number) uid).longValue() : 0;
    }

    public static long getPortfolioId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return 0;
        Object pid = session.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    /** Write 401 JSON response when user is not authenticated. Returns true if unauthenticated. */
    public static boolean requireAuth(long userId, jakarta.servlet.http.HttpServletResponse resp) {
        if (userId == 0) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            try {
                resp.getWriter().write("{\"error\":\"unauthorized\"}");
            } catch (Exception ignored) {}
            return false;
        }
        return true;
    }

    public static Map<String, Object> unauthorized() {
        return Map.of("error", "unauthorized");
    }
}
