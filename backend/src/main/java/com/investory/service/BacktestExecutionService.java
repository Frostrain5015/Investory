package com.investory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.crawler.BacktestSessionManager;
import com.investory.dao.BacktestDao;
import com.investory.model.BacktestRequest;
import com.investory.util.ProcessRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns backtest execution, persistence and temporary files, independently of HTTP requests. */
@Service
public class BacktestExecutionService {
    private static final Logger log = Logger.getLogger(BacktestExecutionService.class.getName());
    private static final Pattern PROGRESS = Pattern.compile("\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");
    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};
    private final BacktestDao results;
    private final BacktestSessionManager sessions;
    private final ObjectMapper json;
    private final ExecutorService executor;
    private final String python;
    private final Duration timeout;

    public BacktestExecutionService(BacktestDao results, BacktestSessionManager sessions, ObjectMapper json,
            @Qualifier("backtestExecutor") ExecutorService executor,
            @Value("${python.executable:python3}") String python,
            @Value("${backtest.timeout-seconds:900}") long timeoutSeconds) {
        this.results = results;
        this.sessions = sessions;
        this.json = json;
        this.executor = executor;
        this.python = python;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public OptionalLong start(long userId, Long portfolioId, BacktestRequest request) throws IOException {
        BacktestSessionManager.Run run = sessions.tryStart(userId);
        if (run == null) return OptionalLong.empty();
        long resultId = 0;
        try {
            resultId = results.insert(userId, portfolioId, request.name(), request.strategyType(),
                    json.writeValueAsString(request.strategy()), json.writeValueAsString(request.config()),
                    request.config().get("startDate").toString(), request.config().get("endDate").toString(),
                    "[]", "{}", "[]");
            long id = resultId;
            executor.submit(() -> execute(id, request, run));
            return OptionalLong.of(id);
        } catch (IOException | RuntimeException error) {
            if (resultId != 0) fail(resultId, run, error);
            else run.emitError(message(error));
            run.finish();
            throw error;
        }
    }

    private void execute(long id, BacktestRequest request, BacktestSessionManager.Run run) {
        Path input = null;
        Path output = null;
        Path errorFile = null;
        try {
            run.emitStatus("启动回测: " + request.name());
            Path script = locateScript();
            output = script.getParent().resolve("backtest_output_" + id + ".json");
            errorFile = script.getParent().resolve("backtest_error_" + id + ".json");
            input = Files.createTempFile("backtest_input_", ".json");
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("strategy_type", request.strategyType());
            document.put("strategy", request.strategy());
            document.put("config", request.config());
            document.put("result_id", id);
            Files.writeString(input, json.writeValueAsString(document));
            ProcessBuilder builder = new ProcessBuilder(python, "-u", script.toString(), "--input", input.toString());
            builder.directory(script.getParent().toFile());
            builder.environment().put("PYTHONUNBUFFERED", "1");
            int exit = ProcessRunner.run(builder, timeout, line -> acceptLine(run, line));
            if (exit != 0 || !Files.exists(output)) {
                String detail = Files.exists(errorFile)
                        ? String.valueOf(json.readValue(Files.readString(errorFile), DOCUMENT).getOrDefault("error", "")) : "";
                throw new IOException("回测引擎异常退出 (code=" + exit + ")" + (detail.isBlank() ? "" : ": " + detail));
            }
            Map<String, Object> result = json.readValue(Files.readString(output), DOCUMENT);
            results.updateResult(id, json.writeValueAsString(result.get("equityCurve")),
                    json.writeValueAsString(result.get("metrics")), json.writeValueAsString(result.get("tradeLog")));
            run.emitDone("回测完成", id);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            fail(id, run, error);
        } finally {
            remove(input);
            remove(output);
            remove(errorFile);
            run.finish();
        }
    }

    private Path locateScript() throws IOException {
        for (Path path : new Path[] {Path.of("script/backtest_engine.py"), Path.of("../script/backtest_engine.py")}) {
            if (Files.isRegularFile(path)) return path.toRealPath();
        }
        throw new IOException("回测引擎脚本未找到");
    }

    private void acceptLine(BacktestSessionManager.Run run, String line) {
        Matcher match = PROGRESS.matcher(line);
        if (match.find()) {
            run.updateProgress(Map.of("current", Integer.parseInt(match.group(1)),
                    "total", Integer.parseInt(match.group(2)), "pct", Double.parseDouble(match.group(3)),
                    "name", match.group(4).trim()));
        } else if (line.contains("===")) {
            run.emitInfo(line.replaceFirst("^.*?INFO\\s*", "").trim());
        } else {
            run.addLog(line.trim());
        }
    }

    private void fail(long id, BacktestSessionManager.Run run, Exception error) {
        String detail = error instanceof TimeoutException ? "回测超时，已终止" : message(error);
        run.emitError(detail);
        try {
            results.updateResult(id, "[]", json.writeValueAsString(Map.of("error", detail)), "[]");
        } catch (Exception persistenceError) {
            log.log(Level.WARNING, "Cannot persist failed backtest " + id, persistenceError);
        }
    }

    private String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void remove(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException error) {
            log.log(Level.WARNING, "Cannot remove backtest temporary file " + file, error);
        }
    }
}
