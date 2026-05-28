package com.investory.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.crawler.CrawlSessionManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台管理 REST 控制器，路径前缀 /api/admin。
 *
 * 核心职责：
 * 1. 数据库状态统计（各市场股票数量、K线行数、表磁盘占用）
 * 2. 用户管理（列表、删除、切换登录身份）
 * 3. 爬虫控制——通过 SSE 推流实时进度，支持启动/停止/暂停/恢复
 * 4. 抓取历史记录查询与清理
 *
 * 所有接口均需管理员身份校验（Session 中 isAdmin=true）。
 * 爬虫通过子进程调用 Python 脚本 script/fetch_stocks.py，
 * 进度通过 {@link CrawlSessionManager} 广播给所有 SSE 订阅者。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** Jackson JSON 序列化器，用于将 Map 序列化为 SSE 数据帧 */
    private static final ObjectMapper json = new ObjectMapper();

    /** 固定线程池，用于异步运行爬虫子进程（避免阻塞 HTTP 线程） */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private final JdbcTemplate jdbc;

    /** 爬虫会话管理器，用于多 SSE 订阅者共享进度 */
    private final CrawlSessionManager session;

    /** 当前正在运行的 Python 子进程（volatile 保证可见性） */
    private volatile Process  currentProcess  = null;

    /** 是否已请求停止当前爬虫进程 */
    private volatile boolean  stopRequested   = false;

    /** 是否已请求暂停当前爬虫进程 */
    private volatile boolean  pauseRequested  = false;

    /** 暂停/恢复同步锁，配合 pauseLock.wait/notifyAll 实现线程挂起 */
    private final Object      pauseLock       = new Object();

    /**
     * 进度行正则，匹配 Python 脚本输出的格式：
     *   "[313/324 96.6%] WMB.US → 3行"
     * 捕获组：(当前数) (总数) (百分比) (股票名/描述)
     */
    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[(\\d+)/(\\d+)\\s+(\\d+(?:\\.\\d+)?)%\\]\\s+(.+)");

    /** Python 可执行文件路径，默认值 python3，可在 application.properties 中覆盖 */
    @Value("${python.executable:python3}")
    private String pythonExecutable;

    public AdminController(JdbcTemplate jdbc, @Autowired CrawlSessionManager session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    /**
     * 检查当前请求是否为管理员身份。
     *
     * @param req HTTP 请求
     * @return true 表示已通过管理员校验
     */
    private boolean checkAdmin(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    /**
     * 获取数据库统计状态，包含各市场股票数量、K线行数、最早/最新日期，
     * 以及各数据表的磁盘占用（MB）。
     *
     * <p>执行前先 ANALYZE TABLE stock_prices 以刷新 InnoDB 行数统计，
     * 确保 information_schema.TABLES 返回准确的估算值。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return 包含 markets（按市场分组统计）、totals（全局合计）、tables（表磁盘信息）的 Map
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus(HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");

        // Refresh InnoDB stats for accurate row counts and disk sizes
        jdbc.execute("ANALYZE TABLE stock_prices");

        Map<String, Object> result = new LinkedHashMap<>();

        // 按市场分组统计 A股(SH/SZ合并为A)、港股(HK)、美股(US) 的股票数量与K线数
        List<Map<String, Object>> markets = jdbc.queryForList("""
            SELECT CASE WHEN s.market IN ('SH','SZ') THEN 'A' ELSE s.market END AS market,
                   COUNT(DISTINCT s.id) AS stock_count,
                   COUNT(sp.id)          AS price_rows,
                   COALESCE(MAX(sp.trade_date), '-') AS latest_date,
                   COALESCE(MIN(sp.trade_date), '-') AS earliest_date
            FROM stocks s
            LEFT JOIN stock_prices sp ON sp.stock_id = s.id
            WHERE s.market IN ('SH','SZ','HK','US')
            GROUP BY CASE WHEN s.market IN ('SH','SZ') THEN 'A' ELSE s.market END
            ORDER BY market
            """);
        // 单独汇总指数/大宗商品/货币等其他市场（JP/KR/GB/…/IDX/CMD/CCY）
        Map<String, Object> idxStats = jdbc.queryForMap("""
            SELECT 'IDX' AS market,
                   COUNT(DISTINCT s.id) AS stock_count,
                   COUNT(sp.id)          AS price_rows,
                   COALESCE(MAX(sp.trade_date), '-') AS latest_date,
                   COALESCE(MIN(sp.trade_date), '-') AS earliest_date
            FROM stocks s
            LEFT JOIN stock_prices sp ON sp.stock_id = s.id
            WHERE s.market IN ('JP','KR','GB','DE','FR','TW','SG','IN','AU','CA','BR','IDX','CMD','CCY')
            """);
        if (idxStats != null) {
            markets.add(idxStats);
        }
        result.put("markets", markets);

        // 计算全市场合计（股票总数、K线总行数）
        Map<String, Object> totals = new LinkedHashMap<>();
        long totalStocks = 0, totalRows = 0;
        for (var m : markets) {
            totalStocks += ((Number) m.get("stock_count")).longValue();
            totalRows += ((Number) m.get("price_rows")).longValue();
        }
        totals.put("stock_count", totalStocks);
        totals.put("price_rows", totalRows);
        result.put("totals", totals);

        // 按 data+index 总大小降序列出各表的磁盘占用
        List<Map<String, Object>> tables = jdbc.queryForList("""
            SELECT table_name,
                   ROUND(data_length/1024/1024, 1)  AS data_mb,
                   ROUND(index_length/1024/1024, 1) AS index_mb,
                   ROUND((data_length+index_length)/1024/1024, 1) AS total_mb,
                   table_rows
            FROM information_schema.TABLES
            WHERE table_schema = DATABASE()
            ORDER BY (data_length+index_length) DESC
            """);
        result.put("tables", tables);

        return result;
    }

    // ── 用户管理 ──────────────────────────────────────────────────────────

    /**
     * 获取所有用户列表，附带每位用户的交易记录数和组合数。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return 用户列表，每条记录包含 id、username、email、created_at、txn_count、portfolio_count
     */
    @GetMapping("/users")
    public List<Map<String, Object>> getUsers(HttpServletRequest req) {
        if (!checkAdmin(req)) return List.of(Map.of("error", "unauthorized"));
        return jdbc.queryForList("""
            SELECT u.id, u.username, u.email, u.created_at,
                   (SELECT COUNT(*) FROM transactions t
                    JOIN portfolios p ON t.portfolio_id = p.id
                    WHERE p.user_id = u.id) AS txn_count,
                   (SELECT COUNT(*) FROM portfolios WHERE user_id = u.id) AS portfolio_count
            FROM users u
            ORDER BY u.id
            """);
    }

    /**
     * 以指定用户身份登录（管理员模拟登录），同时保留 isAdmin=true 使侧边栏链接可见。
     * 将目标用户的第一个组合 id 也写入 Session，确保页面数据正确加载。
     *
     * @param userId 要模拟的目标用户 id
     * @param req    HTTP 请求（用于管理员校验和 Session 修改）
     * @return 包含 status 和 username 的结果 Map
     */
    @PostMapping("/impersonate/{userId}")
    public Map<String, Object> impersonate(@PathVariable long userId, HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        var userRow = jdbc.queryForList(
            "SELECT id, username FROM users WHERE id = ?", userId);
        if (userRow.isEmpty()) return Map.of("error", "user not found");
        var portfolios = jdbc.queryForList(
            "SELECT id FROM portfolios WHERE user_id = ? ORDER BY id LIMIT 1", userId);

        HttpSession session = req.getSession();
        session.setAttribute("userId", userId);
        session.setAttribute("username", userRow.get(0).get("username"));
        // Keep isAdmin = true so the sidebar nav link stays visible
        if (!portfolios.isEmpty()) {
            session.setAttribute("portfolioId", ((Number) portfolios.get(0).get("id")).longValue());
        }
        return Map.of("status", "ok", "username", userRow.get(0).get("username"));
    }

    /**
     * 删除指定用户及其所有关联数据（组合、持仓、交易、股息、AI设置、回测结果等）。
     * 禁止管理员删除自己的账号。
     *
     * @param userId 要删除的用户 id
     * @param req    HTTP 请求（用于管理员校验及自我删除防护）
     * @return 包含 status 的结果 Map
     */
    @DeleteMapping("/users/{userId}")
    public Map<String, Object> deleteUser(@PathVariable long userId, HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        // Don't allow deleting yourself
        HttpSession session = req.getSession(false);
        if (session != null && Long.valueOf(userId).equals(session.getAttribute("userId"))) {
            return Map.of("error", "不能删除自己");
        }
        // 按外键依赖顺序逐表删除，避免约束冲突
        jdbc.update("DELETE FROM daily_portfolio_value WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM dividends WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM transactions WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM holdings WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM cash_balances WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM watchlist WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ai_settings WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM backtest_results WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM backtest_strategies WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        return Map.of("status", "ok");
    }

    /**
     * 获取各市场最近一次抓取的历史记录（每市场取 id 最大的一条）。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return 列表，每条包含 market、started_at、ended_at、rows_written、stocks_failed、status
     */
    @GetMapping("/crawl-history")
    public List<Map<String, Object>> getCrawlHistory(HttpServletRequest req) {
        if (!checkAdmin(req)) return List.of(Map.of("error", "unauthorized"));
        // Latest run per market
        return jdbc.queryForList("""
            SELECT m.market, m.started_at, m.ended_at, m.rows_written, m.stocks_failed, m.status
            FROM crawl_history m
            INNER JOIN (
                SELECT market, MAX(id) AS max_id FROM crawl_history GROUP BY market
            ) latest ON m.id = latest.max_id
            ORDER BY m.market
            """);
    }

    /**
     * 查询当前爬虫会话状态（是否活跃、市场、进度、日志等）。
     * 前端页面刷新时调用，用于判断是否需要自动重连 SSE 流。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return 会话状态 Map，包含 active、market、label、progress、recentLogs 等字段
     */
    @GetMapping("/crawl/status")
    public Map<String, Object> crawlStatus(HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        return session.getStatus();
    }

    /**
     * 启动指定市场的股票数据抓取，通过 SSE 实时推送进度。
     *
     * <p>支持市场代码：a（A股全部）、sh（沪市）、sz（深市）、hk（港股）、us（美股）、idx（指数）、all（全市场）。
     *
     * <p>若 reconnect=true 且已有同市场的活跃会话，则直接订阅现有进度流，不重复启动脚本。
     *
     * <p>SSE 事件说明：
     * <ul>
     *   <li>{@code status} — 启动状态描述，data: {msg, market}</li>
     *   <li>{@code progress} — 爬取进度，data: {current, total, pct, name}</li>
     *   <li>{@code info} — 阶段信息（含"==="或"完成"的行），data: {msg}</li>
     *   <li>{@code log} — 普通日志行，data: {msg}</li>
     *   <li>{@code done} — 抓取完成，data: {market, msg}</li>
     *   <li>{@code stopped} — 抓取被手动停止，data: {market, msg}</li>
     *   <li>{@code error} — 抓取出错，data: {msg}</li>
     * </ul>
     *
     * @param market    市场代码
     * @param start     起始日期（yyyy-MM-dd），空则取今天减10天
     * @param end       结束日期（yyyy-MM-dd），空则取今天
     * @param reconnect 是否为重连模式（不重启脚本，仅订阅现有进度）
     * @param req       HTTP 请求（用于管理员校验）
     * @param response  HTTP 响应（设置 SSE 相关响应头）
     * @return SseEmitter 流对象
     */
    @GetMapping("/crawl/{market}")
    public SseEmitter startCrawl(@PathVariable String market,
            @RequestParam(defaultValue = "") String start,
            @RequestParam(defaultValue = "") String end,
            @RequestParam(defaultValue = "false") boolean reconnect,
            HttpServletRequest req,
            HttpServletResponse response) {
        // 告知 nginx/CDN 不要缓冲此流式响应
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(0L);

        if (!checkAdmin(req)) {
            emit(emitter, "error", Map.of("msg", "未授权"));
            emitter.complete();
            return emitter;
        }

        if (!List.of("a", "sh", "sz", "hk", "us", "idx", "all").contains(market)) {
            emit(emitter, "error", Map.of("msg", "无效市场: " + market));
            emitter.complete();
            return emitter;
        }

        // 重连模式：加入已有爬虫会话的 SSE 广播，不重启脚本
        if (reconnect && session.isActive() && market.equals(session.getMarket())) {
            SseEmitter sub = session.subscribe();
            // Copy settings from the session manager into this emitter
            sub.onCompletion(() -> {}); // don't clear session on subscriber disconnect
            return sub;
        }

        // 新建爬虫会话：构建市场中文标签并确定日期范围
        Map<String, String> LABELS = Map.of(
            "all", "全市场", "a", "A股", "sh", "A股(沪)", "sz", "A股(深)",
            "hk", "港股", "us", "美股", "idx", "指数"
        );
        String label = LABELS.getOrDefault(market, market.toUpperCase());
        final String startDate = start.isBlank() ? java.time.LocalDate.now().minusDays(10).toString() : start;
        final String endDate = end.isBlank() ? java.time.LocalDate.now().toString() : end;

        session.startSession(market, label, startDate, endDate);
        SseEmitter sub = session.subscribe();

        System.err.println("[SSE] market=" + market + " start=" + startDate + " end=" + endDate + " python=" + pythonExecutable);
        executor.submit(() -> {
            try {
                System.err.println("[SSE] task started");
                // 优先在工作目录 script/ 下找脚本，找不到则回退到 ../script/
                File script = new File("script/fetch_stocks.py");
                if (!script.exists()) {
                    script = new File("../script/fetch_stocks.py").getCanonicalFile();
                }
                if (!script.exists()) {
                    session.emitError("脚本未找到: " + script.getAbsolutePath());
                    session.clearSession();
                    return;
                }
                File scriptDir = script.getParentFile();
                boolean isFirstStart = true;

                // 主循环：支持暂停后从断点继续（pauseRequested 为 true 时重新进入 loop）
                while (!stopRequested) {
                    if (isFirstStart) {
                        session.emitStatus(
                            String.format("启动 %s 抓取 (%s ~ %s)...", label, startDate, endDate), market);
                        isFirstStart = false;
                    }

                    // 构建 Python 子进程：-u 表示无缓冲模式，确保实时输出
                    ProcessBuilder pb = new ProcessBuilder(
                        pythonExecutable, "-u", script.getAbsolutePath(),
                        "-m", market, "--start", startDate, "--end", endDate
                    );
                    pb.directory(scriptDir);
                    pb.redirectErrorStream(true);
                    pb.environment().put("PYTHONUNBUFFERED", "1");

                    Process p = pb.start();
                    currentProcess = p;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (stopRequested) { p.destroyForcibly(); break; }
                            // 尝试解析进度行，匹配格式 "[current/total pct%] name"
                            Matcher m = PROGRESS_RE.matcher(line);
                            if (m.find()) {
                                Map<String, Object> prog = new LinkedHashMap<>();
                                prog.put("current", Integer.parseInt(m.group(1)));
                                prog.put("total",   Integer.parseInt(m.group(2)));
                                prog.put("pct",     Double.parseDouble(m.group(3)));
                                prog.put("name",    m.group(4).trim());
                                session.updateProgress(prog);
                            } else if (line.contains("===") || line.contains("完成")) {
                                // 阶段性完成信息，去除前导 INFO 前缀后推送
                                session.emitInfo(line.replaceFirst("^.*?INFO\\s*", "").trim());
                            } else {
                                session.addLog(line.trim());
                            }
                        }
                    }

                    // 等待子进程最多 30 分钟，超时则强制杀死
                    boolean finished = p.waitFor(30, TimeUnit.MINUTES);
                    if (!finished) { p.destroyForcibly(); session.emitError(label + " 抓取超时（30分钟），已终止"); }
                    int exitCode = finished ? p.exitValue() : -1;
                    currentProcess = null;

                    if (stopRequested) {
                        session.emitStopped(market, label + " 抓取已停止");
                        break;
                    }
                    if (exitCode == 0) {
                        session.emitDone(market, label + " 抓取完成");
                        break;
                    }

                    // 若处于暂停状态，挂起线程等待恢复信号
                    if (pauseRequested) {
                        session.emitInfo("已暂停");
                        synchronized (pauseLock) {
                            while (pauseRequested && !stopRequested) {
                                try { pauseLock.wait(1000); } catch (InterruptedException e) { break; }
                            }
                        }
                        if (stopRequested) {
                            session.emitStopped(market, label + " 抓取已停止");
                            break;
                        }
                        session.emitInfo("继续抓取...");
                        continue;
                    }

                    session.emitError("脚本退出码: " + exitCode);
                    break;
                }
            } catch (Exception e) {
                session.emitError(e.getMessage());
            } finally {
                // 无论成功/失败/中止，均重置标志位并清理会话
                stopRequested = false;
                pauseRequested = false;
                currentProcess = null;
                session.clearSession();
            }
        });

        return sub;
    }

    /**
     * 停止当前正在运行的爬虫进程。
     * 先唤醒可能处于暂停等待中的后台线程，再强制杀死 Python 子进程（含子子进程）。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return {status: "stopping"}
     */
    @PostMapping("/crawl/stop")
    public Map<String, Object> stopCrawl(HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        stopRequested = true;
        // 唤醒后台线程（若当前处于 pauseLock.wait 状态）
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        // 强制杀死子进程及其所有子孙进程
        Process p = currentProcess;
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        return Map.of("status", "stopping");
    }

    /**
     * 清空 crawl_history 表中的所有历史记录。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return {status: "ok", deleted: n}
     */
    @DeleteMapping("/crawl-history")
    public Map<String, Object> clearCrawlHistory(HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        int deleted = jdbc.update("DELETE FROM crawl_history");
        return Map.of("status", "ok", "deleted", deleted);
    }

    /**
     * 暂停当前爬虫：强制杀死 Python 子进程（触发退出码非0），
     * 后台循环检测到 pauseRequested=true 后挂起，直到 resume 被调用时从断点重新启动。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return {status: "paused"} 或 {status: "no_process"}
     */
    @PostMapping("/crawl/pause")
    public Map<String, Object> pauseCrawl(HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        if (currentProcess == null) return Map.of("status", "no_process");
        pauseRequested = true;
        // 杀死 Python 进程；外层 while 循环检测到 pauseRequested 后会挂起等待 resume
        currentProcess.destroyForcibly();
        return Map.of("status", "paused");
    }

    /**
     * 恢复已暂停的爬虫：清除 pauseRequested 标志并通过 pauseLock 唤醒挂起的后台线程。
     *
     * @param req HTTP 请求（用于管理员校验）
     * @return {status: "resumed"} 或 {status: "no_process"}
     */
    @PostMapping("/crawl/resume")
    public Map<String, Object> resumeCrawl(HttpServletRequest req) {
        if (!checkAdmin(req)) return Map.of("error", "unauthorized");
        if (currentProcess == null) return Map.of("status", "no_process");
        synchronized (pauseLock) {
            pauseRequested = false;
            pauseLock.notifyAll();
        }
        return Map.of("status", "resumed");
    }

    /**
     * 向指定 SseEmitter 发送一个 SSE 事件帧。
     * 若发送失败（连接已断开）则打印警告，不抛出异常。
     *
     * @param emitter 目标 SSE 流
     * @param event   事件名称（如 "progress"、"done"、"error"）
     * @param data    事件数据对象，会被 Jackson 序列化为 JSON 字符串
     */
    private void emit(SseEmitter emitter, String event, Object data) {
        try {
            String jsonStr = json.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event).data(jsonStr));
        } catch (Exception e) {
            System.err.println("[SSE] emit failed event=" + event + " error=" + e.getMessage());
        }
    }
}
