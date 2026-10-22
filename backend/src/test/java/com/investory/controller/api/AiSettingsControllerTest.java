package com.investory.controller.api;

import com.investory.service.AiModelConfiguration;
import com.investory.web.GlobalExceptionHandler;
import com.investory.web.LoginInterceptor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiSettingsControllerTest {
    private MockMvc mvc;
    private MockHttpSession session;
    @BeforeEach void setup() {
        var jdbc = mock(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(new AiSettingsController(new AiModelConfiguration(jdbc)))
            .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new LoginInterceptor()).build();
        session = new MockHttpSession(); session.setAttribute("userId", 7L);
    }
    @Test void rejectsOldBearerIdentityAndRemovesExternalRoutes() throws Exception {
        mvc.perform(get("/api/ai/settings").header("Authorization", "Bearer retired-token"))
            .andExpect(status().isUnauthorized());
        for (String path : new String[]{"/mcp", "/api/mcp/tokens", "/oauth/mcp/token", "/oauth/mcp/authorize", "/.well-known/oauth-authorization-server"})
            mvc.perform(get(path).session(session)).andExpect(status().isNotFound());
    }
    @Test void emptySettingsReturnNoSupplierDefaultsOrCredentials() throws Exception {
        mvc.perform(get("/api/ai/settings").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.models").isEmpty()).andExpect(jsonPath("$.maxModels").value(3))
            .andExpect(jsonPath("$.activeModelId").isEmpty()).andExpect(jsonPath("$.apiKey").doesNotExist());
    }
    @Test void rejectsFractionalContextsInsteadOfSilentlyTruncating() throws Exception {
        mvc.perform(post("/api/ai/settings").session(session).contentType("application/json")
            .content("{\"id\":1,\"provider\":\"openai_compat\",\"baseUrl\":\"http://localhost:8000/v1\",\"model\":\"local\",\"contextWindow\":8192.5}"))
            .andExpect(status().isBadRequest());
    }
    @Test void localModelsListWorksWithoutAuthorizationAndSupportsBothProtocols() throws Exception {
        var auth = new AtomicReference<String>();
        var path = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization")); path.set(exchange.getRequestURI().getPath());
            byte[] body = "{\"data\":[{\"id\":\"local/model:4bit\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            for (String provider : new String[]{"openai_compat", "anthropic"}) {
                String base = "http://127.0.0.1:" + server.getAddress().getPort() + (provider.equals("openai_compat") ? "/v1" : "");
                mvc.perform(post("/api/ai/models").session(session).contentType("application/json")
                    .content("{\"id\":1,\"provider\":\"" + provider + "\",\"baseUrl\":\"" + base + "\",\"apiKey\":\"\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.models[0]").value("local/model:4bit"));
                org.junit.jupiter.api.Assertions.assertNull(auth.get());
                org.junit.jupiter.api.Assertions.assertEquals("/v1/models", path.get());
            }
        } finally { server.stop(0); }
    }
}
