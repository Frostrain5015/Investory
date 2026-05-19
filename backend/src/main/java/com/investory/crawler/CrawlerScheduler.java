package com.investory.crawler;

import com.investory.dao.StockDao;
import com.investory.model.Stock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Component
public class CrawlerScheduler {

    private static final Logger log = Logger.getLogger(CrawlerScheduler.class.getName());
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Autowired private EastMoneyCrawler crawler;
    @Autowired private StockDao stockDao;
    @Autowired private JdbcTemplate jdbc;

    @Scheduled(fixedRate = 15 * 60 * 1000, initialDelay = 60 * 1000)
    public void realtimeTask() {
        try {
            if (!isTradingHours()) return;
            List<Stock> stocks = getHeldStocks();
            if (!stocks.isEmpty()) crawler.updateRealtimePrices(stocks);
        } catch (Exception e) {
            log.warning("Realtime task error: " + e.getMessage());
        }
    }

    @Scheduled(fixedRate = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void historyTask() {
        try {
            LocalTime now = LocalTime.now(SHANGHAI);
            if (now.isBefore(LocalTime.of(16, 25)) || now.isAfter(LocalTime.of(16, 55))) return;
            DayOfWeek day = LocalDate.now(SHANGHAI).getDayOfWeek();
            if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return;
            for (Stock stock : getHeldStocks()) {
                crawler.fetchHistory(stock);
            }
        } catch (Exception e) {
            log.warning("History task error: " + e.getMessage());
        }
    }

    private boolean isTradingHours() {
        DayOfWeek day = LocalDate.now(SHANGHAI).getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime now = LocalTime.now(SHANGHAI);
        return (now.isAfter(LocalTime.of(9, 24)) && now.isBefore(LocalTime.of(11, 32)))
            || (now.isAfter(LocalTime.of(12, 59)) && now.isBefore(LocalTime.of(15, 6)));
    }

    private List<Stock> getHeldStocks() {
        List<Long> ids = jdbc.queryForList(
            "SELECT DISTINCT stock_id FROM holdings WHERE total_shares > 0", Long.class);
        return ids.stream()
            .map(id -> stockDao.findById(id))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}
