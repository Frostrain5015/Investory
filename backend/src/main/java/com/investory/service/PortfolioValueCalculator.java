package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.model.DailyValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Logger;

@Service
public class PortfolioValueCalculator {

    private static final Logger log = Logger.getLogger(PortfolioValueCalculator.class.getName());

    @Autowired private DailyPortfolioValueDao dailyDao;
    @Autowired private PnlLedgerService pnlLedgerService;

    @Transactional
    public void backfillFrom(long portfolioId, LocalDate fromDate) {
        backfillFrom(portfolioId, fromDate, 0, null, null);
    }

    @Transactional
    public void backfillFrom(long portfolioId, LocalDate fromDate,
                              long tradedStockId, BigDecimal tradePrice, BigDecimal tradeShares) {
        LocalDate toDate = LocalDate.now();
        if (fromDate == null || fromDate.isAfter(toDate)) return;

        List<DailyValue> values = pnlLedgerService.calculateDailyValues(portfolioId, fromDate, toDate);
        dailyDao.deleteFrom(portfolioId, fromDate);
        for (DailyValue value : values) {
            dailyDao.upsert(value);
        }
        log.info("Backfilled daily values for portfolio " + portfolioId + " from " + fromDate + " to " + toDate);
    }
}
