package com.investory.web;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.logging.Logger;

/**
 * 全局 REST 异常处理器。
 *
 * <p>使用 {@link RestControllerAdvice} 注解，作用范围覆盖所有标注了
 * {@code @RestController} 或 {@code @ResponseBody} 的控制器。
 * 当 Controller 层或 Service 层抛出异常时，由此类统一捕获并转换为标准 JSON 错误响应，
 * 避免将堆栈信息暴露给前端，同时保证 HTTP 状态码语义正确。
 *
 * <p>处理优先级（Spring 按异常类型从最具体到最宽泛匹配）：
 * <ol>
 *   <li>{@link DataAccessException} — 数据库访问异常，返回 500。</li>
 *   <li>{@link IllegalArgumentException} — 业务参数校验失败，返回 400。</li>
 *   <li>{@link Exception} — 兜底处理所有未被上述规则捕获的异常，返回 500。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** JUL 日志记录器，用于将数据库错误和未知异常记录到应用日志。 */
    private static final Logger log = Logger.getLogger(GlobalExceptionHandler.class.getName());

    /**
     * 处理数据库访问异常（{@link DataAccessException} 及其所有子类）。
     *
     * <p>Spring JDBC / MyBatis / JPA 在操作数据库失败时均会抛出此类异常的子类
     * （如 {@code DuplicateKeyException}、{@code DataIntegrityViolationException} 等）。
     * 此处统一以 WARNING 级别记录详情，并向前端返回友好的中文错误提示，
     * 不暴露具体 SQL 或数据库结构信息。
     *
     * @param e 捕获到的数据库异常，其 message 会写入日志
     * @return HTTP 500 响应体，包含键 {@code error} 的 JSON 对象
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException e) {
        log.warning("Database error: " + e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "数据库操作失败"));
    }

    /**
     * 处理业务参数非法异常（{@link IllegalArgumentException}）。
     *
     * <p>Service 层在校验输入参数不合法时（如金额为负、日期格式错误等）应抛出此异常，
     * 并在异常消息中填写可直接展示给用户的描述文字。
     * 此处将消息原样透传给前端，并以 HTTP 400 Bad Request 告知调用方是客户端问题。
     *
     * @param e 捕获到的参数异常，其 message 将直接作为前端错误提示
     * @return HTTP 400 响应体，包含键 {@code error} 的 JSON 对象
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    /**
     * 兜底异常处理器，捕获所有未被前两个规则处理的异常。
     *
     * <p>有两种特殊情况需要直接返回 {@code null}（即不干预框架默认处理）：
     * <ol>
     *   <li><b>SSE 流已提交（{@code resp.isCommitted() == true}）</b> —
     *       Server-Sent Events 场景下响应头已发出，此时不能再修改状态码，
     *       强行写出会导致客户端解析错误，故放弃处理。</li>
     *   <li><b>SPA 路由 / 异步请求相关异常</b> —
     *       类名包含 {@code NoResourceFound}（前端路由刷新时找不到静态资源）
     *       或 {@code AsyncRequest}（SSE/异步请求超时）的异常，
     *       交由框架或 {@code SpaController} 处理，此处不覆盖默认行为。</li>
     * </ol>
     *
     * @param e    捕获到的未知异常，类名和消息会以 SEVERE 级别写入日志
     * @param resp 当前响应对象，用于判断响应流是否已提交
     * @return HTTP 500 响应体，或 {@code null}（表示交由框架继续处理）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAll(Exception e, jakarta.servlet.http.HttpServletResponse resp) {
        // SSE 等场景下响应已提交，无法再设置状态码，直接忽略
        if (resp.isCommitted()) return null;

        // SPA 路由 — 让 SpaController 处理；AsyncRequest 超时 — 交由框架处理
        String name = e.getClass().getName();
        if (name.contains("NoResourceFound") || name.contains("AsyncRequest")) {
            return null;
        }

        log.severe("Unhandled exception: " + name + " — " + e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务器内部错误"));
    }
}
