package com.investory.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:mail-gateway.properties")
@EnableConfigurationProperties(MailGatewayProperties.class)
public class MailGatewayConfiguration {
    /** Configuring the client never opens a connection, including at startup. */
    @Bean("emailGatewayMailSender")
    public JavaMailSenderImpl mailSender(MailGatewayProperties settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        sender.setPassword(settings.password());
        sender.setDefaultEncoding("UTF-8");
        Properties smtp = sender.getJavaMailProperties();
        smtp.setProperty("mail.smtp.auth", String.valueOf(!MailGatewayProperties.blank(settings.username())));
        smtp.setProperty("mail.smtp.ssl.enable", String.valueOf(settings.security() == MailGatewayProperties.Security.SSL));
        smtp.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        smtp.setProperty("mail.smtp.starttls.enable", String.valueOf(settings.security() == MailGatewayProperties.Security.STARTTLS));
        smtp.setProperty("mail.smtp.starttls.required", String.valueOf(settings.security() == MailGatewayProperties.Security.STARTTLS));
        smtp.setProperty("mail.smtp.connectiontimeout", String.valueOf(settings.timeoutMs()));
        smtp.setProperty("mail.smtp.timeout", String.valueOf(settings.timeoutMs()));
        smtp.setProperty("mail.smtp.writetimeout", String.valueOf(settings.timeoutMs()));
        smtp.setProperty("mail.smtp.sendpartial", "false");
        smtp.setProperty("mail.debug", "false");
        return sender;
    }
}
