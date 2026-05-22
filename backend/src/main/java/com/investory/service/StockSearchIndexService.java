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

@Component
public class StockSearchIndexService {

    private static final Logger log = Logger.getLogger(StockSearchIndexService.class.getName());

    @Autowired private JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        buildIndex();
    }

    private void buildIndex() {
        try {
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
