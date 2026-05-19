package com.investory.crawler;

import com.investory.dao.HoldingDao;
import com.investory.dao.StockDao;
import com.investory.model.Holding;
import com.investory.model.Stock;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Scheduled crawler that:
 *  - Every 15 min during A-share trading hours (9:25–15:05 weekdays): updates realtime prices
 *  - Daily at 16:30: fetches full historical K-line for all held stocks
 */
public class CrawlerScheduler implements ServletContextListener {

    private static final Logger log = Logger.getLogger(CrawlerScheduler.class.getName());
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private ScheduledExecutorService scheduler;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        scheduler = Executors.newScheduledThreadPool(2);

        // Realtime: every 15 minutes
        scheduler.scheduleAtFixedRate(this::realtimeTask, 1, 15, TimeUnit.MINUTES);

        // History: every day at 16:30 (run once an hour, guard inside)
        scheduler.scheduleAtFixedRate(this::historyTask, 5, 60, TimeUnit.MINUTES);

        log.info("CrawlerScheduler started");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (scheduler != null) scheduler.shutdown();
    }

    private void realtimeTask() {
        try {
            if (!isTradingHours()) return;
            List<Stock> stocks = getHeldStocks();
            if (!stocks.isEmpty()) EastMoneyCrawler.get().updateRealtimePrices(stocks);
        } catch (Exception e) {
            log.warning("Realtime task error: " + e.getMessage());
        }
    }

    private void historyTask() {
        try {
            LocalTime now = LocalTime.now(SHANGHAI);
            // Run once around 16:30
            if (now.isBefore(LocalTime.of(16, 25)) || now.isAfter(LocalTime.of(16, 55))) return;
            DayOfWeek day = java.time.LocalDate.now(SHANGHAI).getDayOfWeek();
            if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return;

            for (Stock stock : getHeldStocks()) {
                EastMoneyCrawler.get().fetchHistory(stock);
            }
        } catch (Exception e) {
            log.warning("History task error: " + e.getMessage());
        }
    }

    private boolean isTradingHours() {
        DayOfWeek day = java.time.LocalDate.now(SHANGHAI).getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime now = LocalTime.now(SHANGHAI);
        return (now.isAfter(LocalTime.of(9, 24)) && now.isBefore(LocalTime.of(11, 32)))
            || (now.isAfter(LocalTime.of(12, 59)) && now.isBefore(LocalTime.of(15, 6)));
    }

    private List<Stock> getHeldStocks() throws Exception {
        // Collect distinct stock IDs from all holdings with shares > 0
        List<Holding> all = new ArrayList<>();
        // Fetch from DB by querying holdings table directly
        List<Long> stockIds = com.investory.util.DBUtil.getConnection()
                .prepareStatement("SELECT DISTINCT stock_id FROM holdings WHERE total_shares > 0")
                .executeQuery().isBeforeFirst()
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>();

        try (var con = com.investory.util.DBUtil.getConnection();
             var ps  = con.prepareStatement("SELECT DISTINCT stock_id FROM holdings WHERE total_shares > 0");
             var rs  = ps.executeQuery()) {
            while (rs.next()) stockIds.add(rs.getLong(1));
        }

        List<Stock> stocks = new ArrayList<>();
        for (Long id : stockIds) {
            Stock s = StockDao.get().findById(id);
            if (s != null) stocks.add(s);
        }
        return stocks;
    }
}
