package com.investory.controller.api;

import com.investory.mail.EmailGateway;
import com.investory.mail.MailGatewayConfiguration;
import com.investory.mail.MailGatewayProperties;
import com.investory.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailGatewayControllerTest {
    private EmailGateway gateway;
    private MockMvc mvc;
    private MockHttpSession admin;

    @BeforeEach
    void setUp() {
        gateway = mock(EmailGateway.class);
        mvc = build(gateway);
        admin = new MockHttpSession();
        admin.setAttribute("userId", 1L);
        admin.setAttribute("isAdmin", true);
    }

    private MockMvc build(EmailGateway service) {
        return MockMvcBuilders.standaloneSetup(new EmailGatewayController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void rejectsAnonymousAndNonAdminRequestsBeforeCallingGateway() throws Exception {
        mvc.perform(get("/api/admin/mail/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/mail/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"recipient@example.com\",\"subject\":\"test\",\"text\":\"body\"}"))
                .andExpect(status().isUnauthorized());
        var user = new MockHttpSession();
        user.setAttribute("userId", 2L);
        user.setAttribute("isAdmin", false);
        mvc.perform(get("/api/admin/mail/status").session(user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/mail/test").session(user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"recipient@example.com\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
    }

    @Test
    void returnsConfigurationStatusAndSmtpAcceptanceWithoutClaimingDelivery() throws Exception {
        when(gateway.status()).thenReturn(new EmailGateway.GatewayStatus(true, true, "smtp", List.of()));
        mvc.perform(get("/api/admin/mail/status").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.password").doesNotExist());
        when(gateway.send(any())).thenReturn(new EmailGateway.SendResult("accepted", "smtp", "request-id",
                "<message@example.com>", Instant.parse("2026-10-21T00:00:00Z")));
        mvc.perform(post("/api/admin/mail/test").session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"recipient@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.messageId").value("<message@example.com>"))
                .andExpect(jsonPath("$.delivered").doesNotExist());
        verify(gateway).send(argThat(mail -> mail.to().equals("recipient@example.com")
                && mail.subject().equals("Investory 邮件网关测试") && mail.html() == null));
    }

    @Test
    void acceptsCustomPlainAndHtmlPayload() throws Exception {
        mvc.perform(post("/api/admin/mail/send").session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"recipient@example.com\",\"subject\":\"主题\",\"text\":\"正文\",\"html\":\"<p>正文</p>\"}"))
                .andExpect(status().isOk());
        verify(gateway).send(new EmailGateway.EmailMessage("recipient@example.com", "主题", "正文", "<p>正文</p>"));
    }

    @Test
    void disabledAndInvalidInputReturnExplicitErrors() throws Exception {
        var settings = new MailGatewayProperties(false, "", 465, "", "", "", MailGatewayProperties.Security.SSL, 1000);
        mvc = build(new EmailGateway(settings, new MailGatewayConfiguration().mailSender(settings)));
        mvc.perform(post("/api/admin/mail/test").session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"recipient@example.com\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MAIL_DISABLED"));
        when(gateway.send(any())).thenThrow(new IllegalArgumentException("邮箱地址格式不正确"));
        mvc = build(gateway);
        mvc.perform(post("/api/admin/mail/test").session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"bad-address\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("邮箱地址格式不正确"));
        mvc.perform(post("/api/admin/mail/test").session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{")).andExpect(status().isBadRequest());
    }
}
