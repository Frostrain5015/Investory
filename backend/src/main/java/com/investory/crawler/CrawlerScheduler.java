package com.investory.crawler;

import com.investory.dao.StockDao;
import com.investory.model.Stock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Deprecated scheduler. Realtime prices are now handled by RealtimeQuoteService.
 * Daily close syncs are handled by external Python scripts via yfinance + baostock.
 * Kept for potential future use.
 */
@Component
public class CrawlerScheduler {

    private static final Logger log = Logger.getLogger(CrawlerScheduler.class.getName());

    @Autowired private StockDao stockDao;
    @Autowired private JdbcTemplate jdbc;

    /** Convenience: get all stocks currently held across all portfolios. */
    public List<Stock> getHeldStocks() {
        List<Long> ids = jdbc.queryForList(
            "SELECT DISTINCT stock_id FROM holdings WHERE total_shares > 0", Long.class);
        return ids.stream()
            .map(id -> stockDao.findById(id))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}
