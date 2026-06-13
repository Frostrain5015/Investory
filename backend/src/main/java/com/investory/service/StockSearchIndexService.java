package com.investory.service;

import com.investory.server.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.logging.Logger;

/**
 * 股票搜索索引服务
 *
 * <p>负责为股票名称生成拼音首字母缩写（如"贵州茅台"→"gzmt"），
 * 并将结果写入 {@code stocks} 表的 {@code name_pinyin} 字段。
 */
public class StockSearchIndexService {

    private static final Logger log = Logger.getLogger(StockSearchIndexService.class.getName());

    public StockSearchIndexService() {}

    /**
     * 手动调用以构建拼音索引。
     */
    public void init() {
        buildIndex();
    }

    private void buildIndex() {
        try {
            long lastId = 0;
            int total = 0;
            while (true) {
                String sql = "SELECT id, name FROM stocks WHERE name_pinyin IS NULL AND id > ? ORDER BY id LIMIT 500";
                java.util.List<java.util.Map<String, Object>> rows = query(sql, lastId);
                if (rows.isEmpty()) break;

                for (java.util.Map<String, Object> row : rows) {
                    long id = ((Number) row.get("id")).longValue();
                    String name = (String) row.get("name");
                    String abbr = com.investory.util.PinyinUtil.toAbbr(name);
                    if (!abbr.isEmpty()) {
                        update("UPDATE stocks SET name_pinyin=? WHERE id=?", abbr, id);
                        total++;
                    }
                    lastId = id;
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

    private java.util.List<java.util.Map<String, Object>> query(String sql, Object... params) {
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    result.add(row);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Query failed", e);
        }
        return result;
    }

    private void update(String sql, Object... params) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Update failed", e);
        }
    }
}
