package com.investory.mail;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A real SMTP conversation over loopback; nothing leaves the machine. */
class EmailGatewaySmtpTest {
    @Test
    void submitsUtf8PlainAndHtmlThroughActualSmtpClient() throws Exception {
        try (SmtpCapture capture = new SmtpCapture()) {
            var settings = new MailGatewayProperties(true, "127.0.0.1", capture.port(), "", "",
                    "Investory <sender@example.com>", MailGatewayProperties.Security.PLAIN, 3000);
            var gateway = new EmailGateway(settings, new MailGatewayConfiguration().mailSender(settings));
            var result = gateway.send(new EmailGateway.EmailMessage("recipient@example.com", "中文网关测试",
                    "持仓通知\n金额：-¥22.65", "<p>持仓通知：-¥22.65</p>"));
            assertEquals("accepted", result.status());
            assertNotNull(result.messageId());
            assertNotNull(result.acceptedAt());
            String wire = capture.message.get(5, TimeUnit.SECONDS);
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
            assertEquals("中文网关测试", mime.getSubject());
            assertEquals("recipient@example.com", mime.getAllRecipients()[0].toString());
            assertEquals(result.messageId(), mime.getMessageID());
            assertEquals(result.requestId(), mime.getHeader("X-Investory-Request-Id", null));
            Multipart mixed = (Multipart) mime.getContent();
            Multipart related = (Multipart) mixed.getBodyPart(0).getContent();
            Multipart alternative = (Multipart) related.getBodyPart(0).getContent();
            assertEquals("持仓通知\n金额：-¥22.65", alternative.getBodyPart(0).getContent().toString().replace("\r\n", "\n"));
            assertEquals("<p>持仓通知：-¥22.65</p>", alternative.getBodyPart(1).getContent());
        }
    }

    @Test
    void defaultConfigurationBindsWithoutCredentialsOrConnecting() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(MailGatewayConfiguration.class)
                .withPropertyValues("investory.mail.enabled=false", "investory.mail.host=",
                        "investory.mail.username=", "investory.mail.password=", "investory.mail.from=")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var settings = context.getBean(MailGatewayProperties.class);
                    assertFalse(settings.enabled());
                    assertFalse(settings.configurationProblems().isEmpty());
                    assertEquals(465, settings.port());
                    assertEquals(MailGatewayProperties.Security.SSL, settings.security());
                });
    }

    private static final class SmtpCapture implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;
        private final CompletableFuture<String> message = new CompletableFuture<>();

        SmtpCapture() throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve, "smtp-loopback-test");
            worker.setDaemon(true);
            worker.start();
        }

        int port() { return server.getLocalPort(); }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                out.print("220 localhost test SMTP\r\n"); out.flush();
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                        out.print("250 localhost\r\n");
                    } else if (line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:")) {
                        out.print("250 OK\r\n");
                    } else if (line.equals("DATA")) {
                        out.print("354 Send message\r\n"); out.flush();
                        StringBuilder data = new StringBuilder();
                        while ((line = in.readLine()) != null && !line.equals(".")) {
                            data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                        }
                        message.complete(data.toString());
                        out.print("250 Accepted\r\n");
                    } else if (line.equals("QUIT")) {
                        out.print("221 Bye\r\n"); out.flush(); break;
                    } else {
                        out.print("500 Unsupported test command\r\n");
                    }
                    out.flush();
                }
            } catch (Exception error) {
                message.completeExceptionally(error);
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            worker.join(6000);
        }
    }
}
