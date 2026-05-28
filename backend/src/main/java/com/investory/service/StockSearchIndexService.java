package com.investory.service;

import com.investory.util.PinyinUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * 股票搜索索引服务
 *
 * <p>负责在应用启动完成后，为股票名称生成拼音首字母缩写（如"贵州茅台"→"gzmt"），
 * 并将结果写入 {@code stocks} 表的 {@code name_pinyin} 字段，
 * 以支持前端按拼音首字母搜索股票的功能。
 *
 * <p>索引构建策略：
 * <ul>
 *   <li>仅处理 {@code name_pinyin} 字段为 {@code NULL} 的行（增量更新，不重复计算）</li>
 *   <li>分批查询（每批 500 条），批次间休眠 20ms，避免启动时对数据库造成瞬时压力</li>
 *   <li>异常时静默记录日志，不影响应用正常启动</li>
 * </ul>
 */
@Component
public class StockSearchIndexService {

    private static final Logger log = Logger.getLogger(StockSearchIndexService.class.getName());

    @Autowired private JdbcTemplate jdbc; // JDBC 模板，用于查询和更新股票表

    /**
     * 应用启动完成后自动触发索引构建。
     *
     * <p>监听 {@link ApplicationReadyEvent} 事件，确保在所有 Bean 初始化完毕、
     * 数据库连接就绪之后再执行索引逻辑，避免启动期间资源竞争。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        buildIndex();
    }

    /**
     * 批量为缺失拼音索引的股票生成并写入拼音首字母缩写。
     *
     * <p>算法思路：
     * <ol>
     *   <li>使用游标 {@code lastId} 分批查询 {@code name_pinyin IS NULL} 的记录
     *       （每批最多 500 条，按 id 升序，从上次最大 id 之后开始），
     *       直到没有更多待处理行为止</li>
     *   <li>对每条记录调用 {@link PinyinUtil#toAbbr(String)} 将中文名称转为拼音首字母</li>
     *   <li>仅当生成的缩写非空时才执行 UPDATE，避免无效写入</li>
     *   <li>每批处理完毕后休眠 20ms，降低对数据库的突发写入压力</li>
     * </ol>
     *
     * <p>若线程被中断，会正确恢复中断标志并退出；其他异常以 WARNING 级别记录后退出。
     */
    private void buildIndex() {
        try {
            // Populate rows where name_pinyin is still null, in batches
            long lastId = 0; // 游标：记录上一批次已处理的最大 id，用于分页查询
            int total = 0;   // 本次启动累计更新的股票数量，用于日志统计
            while (true) {
                // 第1步：分批查询尚未生成拼音索引的股票（每批 500 条，id > lastId）
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, name FROM stocks WHERE name_pinyin IS NULL AND id > ? ORDER BY id LIMIT 500",
                    lastId);
                if (rows.isEmpty()) break; // 无更多待处理行，索引已完整，退出循环

                // 第2步：逐条生成拼音首字母并写入数据库
                for (Map<String, Object> row : rows) {
                    long id = ((Number) row.get("id")).longValue();
                    String name = (String) row.get("name");
                    String abbr = PinyinUtil.toAbbr(name); // 将中文股票名称转为拼音首字母缩写
                    if (!abbr.isEmpty()) {
                        jdbc.update("UPDATE stocks SET name_pinyin=? WHERE id=?", abbr, id);
                        total++;
                    }
                    lastId = id; // 更新游标为当前已处理的 id
                }

                // 第3步：批次间适当休眠，避免高并发写入对数据库造成压力
                Thread.sleep(20);
            }
            if (total > 0) log.info("Pinyin index built/updated: " + total + " stocks");
        } catch (InterruptedException e) {
            // 恢复中断标志，允许调用方感知线程被中断
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warning("Pinyin index error: " + e.getMessage());
        }
    }
}
