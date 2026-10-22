package com.investory.mail;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("investory.mail")
public record MailGatewayProperties(boolean enabled, String host, int port,
                                    String username, String password, String from,
                                    Security security, int timeoutMs) {
    public enum Security { SSL, STARTTLS, PLAIN }

    /** Names only: callers and logs must never receive SMTP credentials. */
    public List<String> configurationProblems() {
        List<String> problems = new ArrayList<>();
        if (blank(host)) problems.add("SMTP_HOST");
        if (port < 1 || port > 65535) problems.add("SMTP_PORT");
        if (security == null) problems.add("SMTP_SECURITY");
        if (timeoutMs < 1 || timeoutMs > 60000) problems.add("SMTP_TIMEOUT_MS");
        if (security == Security.PLAIN) {
            if (!List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host)) {
                problems.add("SMTP_SECURITY (PLAIN 仅限本机测试)");
            }
            if (blank(username) != blank(password)) problems.add("SMTP_USER / SMTP_PASS");
        } else {
            if (blank(username)) problems.add("SMTP_USER");
            if (blank(password)) problems.add("SMTP_PASS");
        }
        try {
            parseAddress(from, true);
        } catch (IllegalArgumentException e) {
            problems.add("SMTP_FROM");
        }
        return List.copyOf(problems);
    }

    static InternetAddress parseAddress(String value, boolean allowDisplayName) {
        if (blank(value) || value.length() > 320 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("邮箱地址格式不正确");
        }
        try {
            InternetAddress[] addresses = InternetAddress.parse(value, true);
            if (addresses.length != 1 || addresses[0].isGroup()
                    || (!allowDisplayName && !value.equals(addresses[0].getAddress()))) {
                throw new IllegalArgumentException("仅支持单个邮箱地址");
            }
            addresses[0].validate();
            if (!addresses[0].getAddress().contains("@")) throw new AddressException();
            return addresses[0];
        } catch (AddressException e) {
            throw new IllegalArgumentException("邮箱地址格式不正确");
        }
    }

    static boolean blank(String value) { return value == null || value.isBlank(); }

    @Override
    public String toString() {
        return "MailGatewayProperties[enabled=" + enabled + ", security=" + security + "]";
    }
}
