package com.investory.server;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Central request router. Routes method+path to registered handlers.
 * Replaces Spring's @RequestMapping mechanism.
 */
public class ServletRouter extends HttpServlet {

    private static final Logger log = Logger.getLogger(ServletRouter.class.getName());

    private final List<Route> routes = new ArrayList<>();
    private final List<Route> orderedRoutes = new ArrayList<>();

    public static class Route {
        public final String method;   // GET, POST, PUT, DELETE
        public final String pattern;  // e.g. /api/transactions/{id}
        public final Handler handler;

        public Route(String method, String pattern, Handler handler) {
            this.method = method;
            this.pattern = pattern;
            this.handler = handler;
        }
    }

    @FunctionalInterface
    public interface Handler {
        void handle(HttpServletRequest req, HttpServletResponse resp) throws Exception;
    }

    @Override
    public void init(ServletConfig config) {
        // Routes are registered via addRoute() before init
        // Build ordered list: static paths first, then parameterized
        orderedRoutes.addAll(routes);
        orderedRoutes.sort((a, b) -> {
            boolean aHasVar = a.pattern.contains("{");
            boolean bHasVar = b.pattern.contains("{");
            if (aHasVar && !bHasVar) return 1;
            if (!aHasVar && bHasVar) return -1;
            return 0;
        });
        log.info("ServletRouter initialized with " + routes.size() + " routes");
    }

    public void addRoute(String method, String pattern, Handler handler) {
        routes.add(new Route(method, pattern, handler));
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String method = req.getMethod().toUpperCase();
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        String path = uri.substring(ctx.length());
        // Strip trailing slash
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        resp.setCharacterEncoding("UTF-8");

        for (Route route : orderedRoutes) {
            if (!route.method.equals(method)) continue;
            Map<String, String> params = matchPath(route.pattern, path);
            if (params != null) {
                // Set path params as request attributes
                params.forEach(req::setAttribute);
                try {
                    route.handler.handle(req, resp);
                } catch (Exception e) {
                    log.warning("Handler error [" + method + " " + path + "]: " + e.getMessage());
                    if (!resp.isCommitted()) {
                        resp.setStatus(500);
                        resp.setContentType("application/json;charset=UTF-8");
                        resp.getWriter().write("{\"error\":\"服务器内部错误\"}");
                    }
                }
                return;
            }
        }

        // No route matched
        if (path.startsWith("/api/")) {
            resp.setStatus(404);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"not_found\"}");
        } else {
            resp.setStatus(404);
        }
    }

    /** Match a pattern like /api/transactions/{id} against /api/transactions/123 */
    private Map<String, String> matchPath(String pattern, String path) {
        String[] pParts = pattern.split("/");
        String[] uParts = path.split("/");
        if (pParts.length != uParts.length) return null;

        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pParts.length; i++) {
            if (pParts[i].startsWith("{") && pParts[i].endsWith("}")) {
                params.put(pParts[i].substring(1, pParts[i].length() - 1), uParts[i]);
            } else if (!pParts[i].equals(uParts[i])) {
                return null;
            }
        }
        return params;
    }
}
