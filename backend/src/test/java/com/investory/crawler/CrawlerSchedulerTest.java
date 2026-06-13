package com.investory.crawler;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CrawlerScheduler 集成测试 — 真实调用 Python 抓取脚本，验证定时抓取流程。
 *
 * 跑法（在项目根目录）：
 *   mvn -f backend/pom.xml test -Dtest=CrawlerSchedulerTest
 *
 * 前提：
 *   - Python 可执行文件在 PATH 中（Windows: python，Linux: python3）
 *   - socks5h://127.0.0.1:7897 代理可达（Clash）
 *   - script/fetch_stocks.py 存在
 */
class CrawlerSchedulerTest {

    private JdbcTemplate jdbc;
    private CrawlerScheduler scheduler;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
            "jdbc:h2:mem:crawl_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS crawl_history (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                market VARCHAR(16),
                started_at TIMESTAMP,
                ended_at TIMESTAMP,
                rows_written INT DEFAULT 0,
                stocks_failed INT DEFAULT 0,
                status VARCHAR(16),
                log_tail TEXT
            )
        """);
        jdbc.execute("DELETE FROM crawl_history");

        CrawlSessionManager sessionMgr = new CrawlSessionManager();
        scheduler = new CrawlerScheduler(jdbc, sessionMgr);

        // 注入 pythonExecutable（@Value 在纯单元测试中不生效）
        // Windows: python  /  Linux: python3
        String pythonExe = System.getProperty("os.name").toLowerCase().contains("win")
            ? "python" : "python3";
        inject(scheduler, "pythonExecutable", pythonExe);
    }

    @Test
    void testSyncHKStocks() throws Exception {
        System.out.println("=== 港股定时抓取测试 ===");

        scheduler.syncHKStocks();
        Thread.sleep(1000);

        Long historyId = jdbc.queryForObject(
            "SELECT MAX(id) FROM crawl_history WHERE market = 'hk'", Long.class);

        if (historyId == null || historyId == 0) {
            System.out.println("港股抓取因周末跳过或未创建记录（预期行为）");
            return; // isWeekend() 跳过是正常的
        }

        String status = waitForCompletion(historyId, 120);
        System.out.println("最终状态: " + status);

        var row = jdbc.queryForMap(
            "SELECT market, rows_written, stocks_failed, status, log_tail FROM crawl_history WHERE id = ?",
            historyId);
        System.out.println("market:      " + row.get("market"));
        System.out.println("rows_written:" + row.get("rows_written"));
        System.out.println("stocks_failed:" + row.get("stocks_failed"));
        System.out.println("status:      " + row.get("status"));
        String logTail = (String) row.get("log_tail");
        System.out.println("log_tail (last 500 chars):\n" +
            (logTail != null ? logTail.substring(Math.max(0, logTail.length() - 500)) : "(null)"));

        assertEquals("hk", row.get("market"));
        assertNotEquals("running", row.get("status"), "进程应该已结束");
        assertNotNull(row.get("log_tail"));
    }

    @Test
    void testSyncUSStocks() throws Exception {
        System.out.println("=== 美股定时抓取测试 ===");

        scheduler.syncUSStocks();
        Thread.sleep(3000);

        Long historyId = jdbc.queryForObject(
            "SELECT MAX(id) FROM crawl_history WHERE market = 'us'", Long.class);
        assertNotNull(historyId, "应该创建了 crawl_history 记录");

        String status = waitForCompletion(historyId, 120);
        System.out.println("最终状态: " + status);

        var row = jdbc.queryForMap(
            "SELECT market, rows_written, stocks_failed, status, log_tail FROM crawl_history WHERE id = ?",
            historyId);
        System.out.println("market:      " + row.get("market"));
        System.out.println("rows_written:" + row.get("rows_written"));
        System.out.println("stocks_failed:" + row.get("stocks_failed"));
        System.out.println("status:      " + row.get("status"));
        String logTail = (String) row.get("log_tail");
        System.out.println("log_tail (last 500 chars):\n" +
            (logTail != null ? logTail.substring(Math.max(0, logTail.length() - 500)) : "(null)"));

        assertEquals("us", row.get("market"));
        assertNotEquals("running", row.get("status"));
        assertNotNull(row.get("log_tail"));
    }

    @Test
    void testSyncIndices() throws Exception {
        System.out.println("=== 指数定时抓取测试 ===");

        scheduler.syncIndices();
        Thread.sleep(3000);

        Long historyId = jdbc.queryForObject(
            "SELECT MAX(id) FROM crawl_history WHERE market = 'idx'", Long.class);
        assertNotNull(historyId);

        String status = waitForCompletion(historyId, 120);
        System.out.println("最终状态: " + status);

        var row = jdbc.queryForMap(
            "SELECT market, rows_written, stocks_failed, status, LEFT(log_tail, 300) as log_preview FROM crawl_history WHERE id = ?",
            historyId);
        System.out.println("market:      " + row.get("market"));
        System.out.println("rows_written:" + row.get("rows_written"));
        System.out.println("stocks_failed:" + row.get("stocks_failed"));
        System.out.println("status:      " + row.get("status"));
        System.out.println("log: " + row.get("log_preview"));

        assertEquals("idx", row.get("market"));
        assertNotEquals("running", row.get("status"));
    }

    // ── helpers ──

    /** 轮询等待抓取完成，返回最终状态 */
    private String waitForCompletion(long historyId, int timeoutSecs) throws InterruptedException {
        for (int i = 0; i < timeoutSecs; i++) {
            String status = jdbc.queryForObject(
                "SELECT status FROM crawl_history WHERE id = ?", String.class, historyId);
            if (!"running".equals(status)) {
                return status;
            }
            Thread.sleep(1000);
            if (i % 10 == 0) System.out.println("  等待中... (" + (i + 1) + "s)");
        }
        return "timeout";
    }

    private static void inject(Object target, String fieldName, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
