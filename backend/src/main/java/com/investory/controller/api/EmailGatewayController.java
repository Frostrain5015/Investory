package com.investory.controller.api;

import com.investory.mail.EmailGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manual administration only. No MCP tool, agent callback or automatic trigger. */
@RestController
@RequestMapping("/api/admin/mail")
public class EmailGatewayController {
    private final EmailGateway gateway;

    public EmailGatewayController(EmailGateway gateway) { this.gateway = gateway; }

    public record TestMailRequest(String to) { }
    public record ErrorResponse(String code, String error) { }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = checkAdmin(request);
        return denied != null ? denied : ResponseEntity.ok(gateway.status());
    }

    @PostMapping(value = "/send", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> send(HttpServletRequest request, @RequestBody EmailGateway.EmailMessage message) {
        ResponseEntity<ErrorResponse> denied = checkAdmin(request);
        return denied != null ? denied : ResponseEntity.ok(gateway.send(message));
    }

    @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> test(HttpServletRequest request, @RequestBody TestMailRequest message) {
        ResponseEntity<ErrorResponse> denied = checkAdmin(request);
        if (denied != null) return denied;
        return ResponseEntity.ok(gateway.send(new EmailGateway.EmailMessage(message.to(),
                "Investory 邮件网关测试", "这是一封由管理员手动发送的邮件网关测试邮件。\n"
                + "本邮件仅用于检查发送通道，不包含交易指令或审批操作。", null)));
    }

    @ExceptionHandler(EmailGateway.GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGatewayError(EmailGateway.GatewayException error) {
        HttpStatus status = error.code() == EmailGateway.ErrorCode.SMTP_SEND_UNCONFIRMED
                ? HttpStatus.BAD_GATEWAY : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new ErrorResponse(error.code().name(), error.getMessage()));
    }

    private static ResponseEntity<ErrorResponse> checkAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("UNAUTHORIZED", "请先登录"));
        }
        if (!Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("FORBIDDEN", "仅管理员可使用邮件网关"));
        }
        return null;
    }
}
