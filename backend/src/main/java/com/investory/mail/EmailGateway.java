package com.investory.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Synchronous SMTP boundary; independent of chat, agents and scheduled jobs. */
@Service
public class EmailGateway {
    private static final Logger log = LoggerFactory.getLogger(EmailGateway.class);
    private final MailGatewayProperties settings;
    private final JavaMailSender sender;

    public EmailGateway(MailGatewayProperties settings,
                        @Qualifier("emailGatewayMailSender") JavaMailSender sender) {
        this.settings = settings;
        this.sender = sender;
    }

    public record EmailMessage(String to, String subject, String text, String html) { }
    public record GatewayStatus(boolean enabled, boolean configured, String provider,
                                List<String> configurationProblems) { }
    public record SendResult(String status, String provider, String requestId,
                             String messageId, Instant acceptedAt) { }

    public enum ErrorCode { MAIL_DISABLED, MAIL_NOT_CONFIGURED, SMTP_SEND_UNCONFIRMED }

    public static class GatewayException extends RuntimeException {
        private final ErrorCode code;

        GatewayException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ErrorCode code() { return code; }
    }

    /** Configuration readiness only; does not imply an SMTP connection or inbox delivery. */
    public GatewayStatus status() {
        List<String> problems = settings.configurationProblems();
        return new GatewayStatus(settings.enabled(), problems.isEmpty(), "smtp", problems);
    }

    public SendResult send(EmailMessage message) {
        if (!settings.enabled()) throw new GatewayException(ErrorCode.MAIL_DISABLED, "邮件网关尚未启用");
        if (!settings.configurationProblems().isEmpty()) {
            throw new GatewayException(ErrorCode.MAIL_NOT_CONFIGURED, "邮件网关配置缺失或无效，请检查配置状态");
        }
        validate(message);
        String requestId = UUID.randomUUID().toString();
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, message.html() != null, "UTF-8");
            helper.setFrom(MailGatewayProperties.parseAddress(settings.from(), true));
            helper.setTo(MailGatewayProperties.parseAddress(message.to(), false));
            helper.setSubject(message.subject());
            if (message.html() == null) helper.setText(message.text());
            else helper.setText(message.text(), message.html());
            mime.setHeader("X-Investory-Request-Id", requestId);
            sender.send(mime);
            log.info("Email gateway request={} status=accepted", requestId);
            return new SendResult("accepted", "smtp", requestId, mime.getMessageID(), Instant.now());
        } catch (MailException | MessagingException e) {
            // Provider errors can contain credentials, recipient addresses or message contents.
            log.warn("Email gateway request={} status=unconfirmed type={}", requestId, e.getClass().getSimpleName());
            throw new GatewayException(ErrorCode.SMTP_SEND_UNCONFIRMED,
                    "未能确认邮件提交结果，请检查 SMTP 服务；网关不会自动重试");
        }
    }

    private static void validate(EmailMessage message) {
        if (message == null) throw new IllegalArgumentException("邮件内容不能为空");
        MailGatewayProperties.parseAddress(message.to(), false);
        if (MailGatewayProperties.blank(message.subject()) || message.subject().length() > 200
                || message.subject().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("邮件主题须为 1–200 个字符，且不能包含换行或控制字符");
        }
        if (MailGatewayProperties.blank(message.text()) || message.text().length() > 50000) {
            throw new IllegalArgumentException("纯文本正文须为 1–50000 个字符");
        }
        if (message.html() != null && (message.html().isBlank() || message.html().length() > 100000)) {
            throw new IllegalArgumentException("HTML 正文须为 1–100000 个字符");
        }
    }
}
