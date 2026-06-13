package com.investory.service;

import com.investory.server.DatabaseManager;
import com.investory.util.PinyinUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
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
public class StockSearchIndexService {

    private static final Logger log = Logger.getLogger(StockSearchIndexService.class.getName());

    /**
     * 为缺失拼音索引的股票生成并写入拼音首字母缩写。
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
    public void init() {
        buildIndex();
    }

    private void buildIndex() {
        try {
            long lastId = 0;
            int total = 0;
            while (true) {
                try (Connection conn = DatabaseManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, name FROM stocks WHERE name_pinyin IS NULL AND id > ? ORDER BY id LIMIT 500")) {
                    ps.setLong(1, lastId);
                    try (ResultSet rs = ps.executeQuery()) {
                        boolean hasData = false;
                        while (rs.next()) {
                            hasData = true;
                            long id = rs.getLong("id");
                            String name = rs.getString("name");
                            String abbr = PinyinUtil.toAbbr(name);
                            if (!abbr.isEmpty()) {
                                try (PreparedStatement updatePs = conn.prepareStatement("UPDATE stocks SET name_pinyin=? WHERE id=?")) {
                                    updatePs.setString(1, abbr);
                                    updatePs.setLong(2, id);
                                    updatePs.executeUpdate();
                                    total++;
                                }
                            }
                            lastId = id;
                        }
                        if (!hasData) break;
                    }
                }
                Thread.sleep(20);
            }
            if (total > 0) log.info("Pinyin index built/updated: " + total + " stocks");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warning("Pinyin index error: " + e.getMessage());
        }
    }
}
