package com.investory.service;

import com.investory.util.PinyinUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Adds name_pinyin column to stocks table on first run and keeps it populated
 * as new stocks are inserted. Runs in a background thread to avoid delaying startup.
 */
@Component
public class StockSearchIndexService {

    private static final Logger log = Logger.getLogger(StockSearchIndexService.class.getName());

    @Autowired private JdbcTemplate jdbc;

    @PostConstruct
    public void init() {
        new Thread(this::buildIndex, "pinyin-index").start();
    }

    private void buildIndex() {
        try {
            // Add column if not already present (MySQL 8.0+ IF NOT EXISTS)
            try {
                jdbc.execute("ALTER TABLE stocks ADD COLUMN IF NOT EXISTS name_pinyin VARCHAR(50) DEFAULT NULL");
            } catch (Exception e) {
                log.fine("name_pinyin column already exists or cannot be added: " + e.getMessage());
            }

            // Populate rows where name_pinyin is still null, in batches
            long lastId = 0;
            int total = 0;
            while (true) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, name FROM stocks WHERE name_pinyin IS NULL AND id > ? ORDER BY id LIMIT 500",
                    lastId);
                if (rows.isEmpty()) break;
                for (Map<String, Object> row : rows) {
                    long id = ((Number) row.get("id")).longValue();
                    String name = (String) row.get("name");
                    String abbr = PinyinUtil.toAbbr(name);
                    if (!abbr.isEmpty()) {
                        jdbc.update("UPDATE stocks SET name_pinyin=? WHERE id=?", abbr, id);
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
}
