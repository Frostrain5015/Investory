package com.investory.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StockSage Alpha Python 桥接执行器。
 *
 * <p>封装 {@link ProcessBuilder} 调用模式，与 {@code QuantApiController}
 * 中已有的 Python 脚本调用方式保持一致。
 *
 * <p>职责：
 * <ul>
 *   <li>解析桥接脚本 {@code bridge.py} 的绝对路径</li>
 *   <li>执行同步调用，返回 JSON Map</li>
 *   <li>执行 SSE 流式调用，实时推送进度和结果</li>
 *   <li>超时处理、错误解析、工作目录管理</li>
 * </ul>
 */
public class StocksageAlphaExecutor {

    private final String pythonExecutable;
    private final ObjectMapper json = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SECONDS = 80;

    /** 进度行正则，与 QuantApiController 一致 */
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    /** 结果行正则：RESULT: 后接 JSON，与 CrawlerScheduler 一致 */
    private static final Pattern RESULT_RE = Pattern.compile(
        "RESULT:\\s*(\\{.+\\})");

    public StocksageAlphaExecutor(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    // ── 脚本定位 ─────────────────────────────────────────────────────────

    /**
     * 查找桥接脚本的绝对路径。
     *
     * <p>查找顺序：
     * <ol>
     *   <li>工作目录下的 {@code backend/src/main/python/stocksage_alpha/bridge.py}（开发环境）</li>
     *   <li>{@code ../backend/src/main/python/stocksage_alpha/bridge.py}（Maven 构建）</li>
     *   <li>{@code stocksage_alpha/bridge.py}（部署环境）</li>
     * </ol>
     */
    private File findBridgeScript() throws IOException {
        String[] candidates = {
            "backend/src/main/python/stocksage_alpha/bridge.py",
            "../backend/src/main/python/stocksage_alpha/bridge.py",
            "stocksage_alpha/bridge.py",
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) return f.getCanonicalFile();
        }
        throw new IOException("bridge.py not found in any candidate location");
    }

    // ── 同步执行 ─────────────────────────────────────────────────────────

    /**
     * 同步执行桥接命令，返回 JSON 解析结果。
     *
     * @param args 命令行参数，不含 python 和脚本路径
     * @return JSON 反序列化的 Map
     * @throws IOException 脚本未找到或进程启动失败
     */
    public Map<String, Object> execute(String... args) throws IOException, InterruptedException {
        return executeWithTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, args);
    }

    /**
     * 带超时的同步执行。
     *
     * @param timeout 超时时间
     * @param unit    时间单位
     * @param args    命令行参数
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeWithTimeout(int timeout, TimeUnit unit, String... args)
        throws IOException, InterruptedException {

        File script = findBridgeScript();
        File workDir = script.getParentFile();

        List<String> command = new ArrayList<>();
        command.add(pythonExecutable);
        command.add(script.getAbsolutePath());
        for (String arg : args) command.add(arg);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        Process p = pb.start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        boolean finished = p.waitFor(timeout, unit);
        if (!finished) {
            p.destroyForcibly();
            return Map.of("error", "StocksageAlpha 执行超时");
        }
        String output;
        try {
            output = outputFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            output = "";
        }

        if (p.exitValue() == 0 && !output.isBlank()) {
            try {
                // 查找 RESULT: 行（bridge.py 输出格式）
                Matcher rm = RESULT_RE.matcher(output);
                String jsonStr;
                if (rm.find()) {
                    jsonStr = rm.group(1);
                } else {
                    // Fallback: try last JSON object in output
                    int lastBrace = output.lastIndexOf('{');
                    int lastClose = output.lastIndexOf('}');
                    if (lastBrace >= 0 && lastClose > lastBrace) {
                        jsonStr = output.substring(lastBrace, lastClose + 1);
                    } else {
                        return Map.of("error", "无法解析输出", "raw", output.substring(0, Math.min(output.length(), 500)));
                    }
                }
                return (Map<String, Object>) json.readValue(jsonStr, Map.class);
            } catch (Exception e) {
                return Map.of("error", "JSON 解析失败: " + e.getMessage(), "raw", output.substring(0, Math.min(output.length(), 200)));
            }
        }
        return Map.of("error", "脚本退出码: " + p.exitValue(), "raw", output);
    }

    // ── SSE 流式执行 ─────────────────────────────────────────────────────

    /**
     * 异步执行命令，通过 SSE 实时推送进度行。
     *
     * <p>进度行匹配 {@link #PROGRESS_RE} 格式的将被解析为进度事件发送；
     * 脚本结束时，stdout 中最后一段 JSON 作为结果事件发送。
     *
     * @param emitter SSE 发射器
     * @param args    命令行参数
     */
    public void executeWithSse(SseEmitter emitter, String... args) {
        try {
            File script = findBridgeScript();
            File workDir = script.getParentFile();

            List<String> command = new ArrayList<>();
            command.add(pythonExecutable);
            command.add(script.getAbsolutePath());
            for (String arg : args) command.add(arg);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true);

            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = PROGRESS_RE.matcher(line);
                    if (m.matches()) {
                        emitter.send(SseEmitter.event()
                            .name("progress")
                            .data(Map.of(
                                "current", m.group(1),
                                "total", m.group(2),
                                "percent", m.group(3),
                                "description", m.group(4)
                            )));
                    }
                    output.append(line).append("\n");
                }
            }

            int exitCode = p.waitFor();

            // Parse RESULT: line from output (same as sync execute)
            String fullOutput = output.toString();
            Matcher rm = RESULT_RE.matcher(fullOutput);
            if (exitCode == 0 && rm.find()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = json.readValue(rm.group(1), Map.class);
                emitter.send(SseEmitter.event().name("result").data(result));
            } else if (exitCode != 0) {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "exitCode", exitCode, "message", fullOutput.substring(0, Math.min(fullOutput.length(), 300)))));
            }
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage())));
                emitter.complete();
            } catch (IOException ignored) {}
        }
    }
}
