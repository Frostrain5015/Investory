package com.investory.controller.api;

import com.investory.marketdata.HithinkDataClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.util.Arrays;

/** Initial, manually requested, administrator-only read path. Existing screens are unchanged. */
@RestController
@RequestMapping("/api/admin/market-data/hithink")
public class HithinkDataController {
    private final HithinkDataClient client;
    public HithinkDataController(HithinkDataClient client) { this.client = client; }
    public record ErrorResponse(String code, String error) { }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        var denied = checkAdmin(request);
        return denied != null ? denied : ResponseEntity.ok(client.status());
    }
    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(HttpServletRequest request, @RequestParam(defaultValue = "") String symbols) {
        var denied = checkAdmin(request);
        if (denied != null) return denied;
        return ResponseEntity.ok(client.snapshot(Arrays.asList(symbols.split(",", -1))));
    }
    @GetMapping("/history")
    public ResponseEntity<?> history(HttpServletRequest request, @RequestParam String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "none") String adjust) {
        var denied = checkAdmin(request);
        if (denied != null) return denied;
        return ResponseEntity.ok(client.historical(symbol, start, end, adjust));
    }
    @GetMapping("/search")
    public ResponseEntity<?> search(HttpServletRequest request, @RequestParam String query,
                                    @RequestParam(defaultValue = "5") int limit) {
        var denied = checkAdmin(request);
        return denied != null ? denied : ResponseEntity.ok(client.search(query, limit));
    }
    @ExceptionHandler(HithinkDataClient.DataException.class)
    public ResponseEntity<ErrorResponse> dataError(HithinkDataClient.DataException error) {
        HttpStatus status = switch (error.code()) {
            case "DATA_DISABLED", "DATA_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "DATA_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(error.code(), error.getMessage()));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> invalidParameter(Exception ignored) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_PARAMETER", "请检查参数与日期格式（yyyy-MM-dd）"));
    }
    private static ResponseEntity<ErrorResponse> checkAdmin(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("UNAUTHORIZED", "请先登录"));
        if (!Boolean.TRUE.equals(session.getAttribute("isAdmin")))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("FORBIDDEN", "数据通道联调接口仅供管理员使用"));
        return null;
    }
}
