package com.investory.crawler;

import com.investory.dao.StockDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

@Component
public class StockDataInitializer implements CommandLineRunner {

    private static final Logger log = Logger.getLogger(StockDataInitializer.class.getName());

    @Autowired private EastMoneyCrawler crawler;
    @Autowired private StockDao stockDao;

    @Override
    public void run(String... args) {
        if (!stockDao.findAll().isEmpty()) {
            log.info("Stocks table already populated, skipping seed");
            return;
        }
        log.info("Seeding stocks table...");
        int count = crawler.fetchAllStocks();
        log.info("Stock seed complete: " + count + " stocks inserted");
    }
}
