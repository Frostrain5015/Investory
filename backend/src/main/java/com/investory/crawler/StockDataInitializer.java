package com.investory.crawler;

import com.investory.dao.StockDao;
import com.investory.server.AppContext;

import java.util.logging.Logger;

public class StockDataInitializer {

    private static final Logger log = Logger.getLogger(StockDataInitializer.class.getName());

    private final EastMoneyCrawler crawler;
    private final StockDao stockDao;

    public StockDataInitializer() {
        this.crawler = AppContext.get(EastMoneyCrawler.class);
        this.stockDao = AppContext.get(StockDao.class);
    }

    public void init() {
        if (!stockDao.findAll().isEmpty()) {
            log.info("Stocks table already populated, skipping seed");
            return;
        }
        log.info("Seeding stocks table...");
        int count = crawler.fetchAllStocks();
        log.info("Stock seed complete: " + count + " stocks inserted");
    }
}
