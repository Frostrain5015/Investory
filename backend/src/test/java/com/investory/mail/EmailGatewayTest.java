package com.investory.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailGatewayTest {
    private JavaMailSender sender;
    private EmailGateway gateway;

    static MailGatewayProperties settings(boolean enabled, String host, int port,
                                           MailGatewayProperties.Security security) {
        return new MailGatewayProperties(enabled, host, port, "sender@example.com", "test-secret",
                "Investory <sender@example.com>", security, 1000);
    }

    @BeforeEach
    void setUp() {
        sender = mock(JavaMailSender.class);
        gateway = new EmailGateway(settings(true, "smtp.example.com", 465,
                MailGatewayProperties.Security.SSL), sender);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private EmailGateway.EmailMessage message(String to, String subject, String text, String html) {
        return new EmailGateway.EmailMessage(to, subject, text, html);
    }

    @Test
    void statusDoesNotConnectAndContainsNoSecrets() {
        assertTrue(gateway.status().configured());
        assertTrue(gateway.status().enabled());
        assertEquals("smtp", gateway.status().provider());
        verifyNoInteractions(sender);
        assertFalse(settings(true, "smtp.example.com", 465,
                MailGatewayProperties.Security.SSL).toString().contains("test-secret"));
    }

    @Test
    void disabledNeverInvokesTransport() {
        gateway = new EmailGateway(settings(false, "smtp.example.com", 465,
                MailGatewayProperties.Security.SSL), sender);
        var error = assertThrows(EmailGateway.GatewayException.class,
                () -> gateway.send(message("user@example.com", "主题", "正文", null)));
        assertEquals(EmailGateway.ErrorCode.MAIL_DISABLED, error.code());
        verifyNoInteractions(sender);
    }

    @Test
    void missingConfigurationNeverPretendsSuccess() {
        gateway = new EmailGateway(new MailGatewayProperties(true, "", 465, "", "", "",
                MailGatewayProperties.Security.SSL, 1000), sender);
        assertFalse(gateway.status().configured());
        assertEquals(java.util.List.of("SMTP_HOST", "SMTP_USER", "SMTP_PASS", "SMTP_FROM"),
                gateway.status().configurationProblems());
        var error = assertThrows(EmailGateway.GatewayException.class,
                () -> gateway.send(message("user@example.com", "主题", "正文", null)));
        assertEquals(EmailGateway.ErrorCode.MAIL_NOT_CONFIGURED, error.code());
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-address", "a@example.com,b@example.com", "Person <a@example.com>",
            "a@example.com\r\nBcc: other@example.com", "group:a@example.com;", ""})
    void rejectsInvalidOrMultipleRecipients(String address) {
        assertThrows(IllegalArgumentException.class,
                () -> gateway.send(message(address, "主题", "正文", null)));
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "主题\r\nBcc: other@example.com", "subject\u0000"})
    void rejectsBlankSubjectsAndHeaderInjection(String subject) {
        assertThrows(IllegalArgumentException.class,
                () -> gateway.send(message("user@example.com", subject, "正文", null)));
        verifyNoInteractions(sender);
    }

    @Test
    void validatesBodyAndSizeLimitsBeforeSending() {
        assertThrows(IllegalArgumentException.class, () -> gateway.send(null));
        assertThrows(IllegalArgumentException.class,
                () -> gateway.send(message("user@example.com", "x".repeat(201), "正文", null)));
        assertThrows(IllegalArgumentException.class,
                () -> gateway.send(message("user@example.com", "主题", " ", "<p>正文</p>")));
        assertThrows(IllegalArgumentException.class,
                () -> gateway.send(message("user@example.com", "主题", "x".repeat(50001), null)));
        assertThrows(IllegalArgumentException.class,
                () -> gateway.send(message("user@example.com", "主题", "正文", "x".repeat(100001))));
        verifyNoInteractions(sender);
    }

    @Test
    void transportErrorsAreRedactedAndNeverRetried() {
        doThrow(new MailSendException("smtp-password=secret; recipient=user@example.com"))
                .when(sender).send(any(MimeMessage.class));
        var error = assertThrows(EmailGateway.GatewayException.class,
                () -> gateway.send(message("user@example.com", "主题", "正文", null)));
        assertEquals(EmailGateway.ErrorCode.SMTP_SEND_UNCONFIRMED, error.code());
        assertFalse(error.getMessage().contains("secret"));
        assertNull(error.getCause());
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void plaintextTransportIsOnlyAllowedOnLoopbackAndRangesAreChecked() {
        assertTrue(settings(true, "127.0.0.1", 2525, MailGatewayProperties.Security.PLAIN)
                .configurationProblems().isEmpty());
        assertFalse(settings(true, "smtp.example.com", 25, MailGatewayProperties.Security.PLAIN)
                .configurationProblems().isEmpty());
        assertTrue(settings(true, "smtp.example.com", 0, MailGatewayProperties.Security.SSL)
                .configurationProblems().contains("SMTP_PORT"));
        var invalid = new MailGatewayProperties(true, "smtp.example.com", 465, "user", "pass",
                "sender@example.com\r\nBcc: hidden@example.com", MailGatewayProperties.Security.SSL, 0);
        assertTrue(invalid.configurationProblems().contains("SMTP_FROM"));
        assertTrue(invalid.configurationProblems().contains("SMTP_TIMEOUT_MS"));
    }

    @Test
    void senderEnforcesTlsAndBoundedTimeouts() {
        var config = new MailGatewayConfiguration();
        var ssl = config.mailSender(settings(true, "smtp.example.com", 465, MailGatewayProperties.Security.SSL));
        assertEquals("true", ssl.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
        assertEquals("true", ssl.getJavaMailProperties().getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("1000", ssl.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout"));
        assertEquals("1000", ssl.getJavaMailProperties().getProperty("mail.smtp.timeout"));
        assertEquals("1000", ssl.getJavaMailProperties().getProperty("mail.smtp.writetimeout"));
        var tls = config.mailSender(settings(true, "smtp.example.com", 587, MailGatewayProperties.Security.STARTTLS));
        assertEquals("false", tls.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
        assertEquals("true", tls.getJavaMailProperties().getProperty("mail.smtp.starttls.required"));
    }
}
