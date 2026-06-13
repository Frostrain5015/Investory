package com.investory.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.server.SseClient;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 */
public class StocksageAlphaExecutor {

    private final String pythonExecutable;
    private final ObjectMapper json = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SECONDS = 130;
    private static final String ENGINE_BASE_URL = "http://127.0.0.1:8200";
    private static final List<String> POST_COMMANDS = List.of(
        "portfolio_analysis", "prefetch_data", "stocksage_report");
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    private static final Pattern RESULT_RE = Pattern.compile(
        "RESULT:\\s*(\\{.+\\})");

    public StocksageAlphaExecutor(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

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

    public Map<String, Object> execute(String... args) throws IOException, InterruptedException {
        return executeWithTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, args);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> executeWithTimeout(int timeout, TimeUnit unit, String... args)
        throws IOException, InterruptedException {

        Map<String, Object> residentResult = executeResident(timeout, unit, args);
        if (residentResult != null) {
            return residentResult;
        }

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
                Matcher rm = RESULT_RE.matcher(output);
                String jsonStr;
                if (rm.find()) {
                    jsonStr = rm.group(1);
                } else {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeResident(int timeout, TimeUnit unit, String... args)
        throws InterruptedException {
        if (args == null || args.length == 0) return null;
        String command = args[0];
        Map<String, Object> params;
        try {
            params = parseParams(command, args);
        } catch (IOException e) {
            return Map.of("error", "参数读取失败: " + e.getMessage());
        }

        long timeoutMillis = Math.max(1, unit.toMillis(timeout));
        try {
            HttpRequest request;
            if (POST_COMMANDS.contains(command)) {
                String body = json.writeValueAsString(params);
                request = HttpRequest.newBuilder()
                    .uri(URI.create(ENGINE_BASE_URL + "/" + command))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            } else {
                request = HttpRequest.newBuilder()
                    .uri(URI.create(ENGINE_BASE_URL + "/" + command + queryString(params)))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .GET()
                    .build();
            }
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return (Map<String, Object>) json.readValue(response.body(), Map.class);
            }
            return Map.of("error", "常驻引擎 HTTP " + response.statusCode());
        } catch (HttpTimeoutException e) {
            return Map.of("error", "StocksageAlpha 常驻引擎执行超时");
        } catch (ConnectException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, Object> parseParams(String command, String... args) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            String key = arg.substring(2).replace('-', '_');
            String value = (i + 1 < args.length) ? args[++i] : "";
            if ("holdings".equals(key) && value.startsWith("@")) {
                value = Files.readString(new File(value.substring(1)).toPath(), StandardCharsets.UTF_8);
            }
            params.put(key, value);
        }
        if ("prefetch_data".equals(command) && params.isEmpty()) {
            return params;
        }
        return params;
    }

    private String queryString(Map<String, Object> params) {
        if (params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!first) sb.append("&");
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 异步执行命令，通过 SSE 实时推送进度行。
     *
     * @param client SSE 客户端
     * @param args   命令行参数
     */
    public void executeWithSse(SseClient client, String... args) {
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
                        client.send("progress", Map.of(
                            "current", m.group(1),
                            "total", m.group(2),
                            "percent", m.group(3),
                            "description", m.group(4)
                        ));
                    }
                    output.append(line).append("\n");
                }
            }

            int exitCode = p.waitFor();

            String fullOutput = output.toString();
            Matcher rm = RESULT_RE.matcher(fullOutput);
            if (exitCode == 0 && rm.find()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = json.readValue(rm.group(1), Map.class);
                client.send("result", result);
            } else if (exitCode != 0) {
                client.send("error", Map.of(
                    "exitCode", exitCode, "message", fullOutput.substring(0, Math.min(fullOutput.length(), 300))));
            }
            client.send("done", Map.of("msg", "complete"));
            client.complete();
        } catch (Exception e) {
            client.send("error", Map.of("message", e.getMessage()));
            client.complete();
        }
    }
}
