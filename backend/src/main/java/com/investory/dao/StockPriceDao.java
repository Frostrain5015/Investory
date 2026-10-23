package com.investory.dao;

import com.investory.model.StockPrice;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;

/** Opt-in archival writer only. All application reads use HistoricalPriceService. */
@Repository
public class StockPriceDao extends BaseDao {
    @Value("${investory.history.legacy-writes-enabled:false}")
    private boolean legacyHistoryWritesEnabled;
    private void requireLegacyWriter() {
        if (!legacyHistoryWritesEnabled) throw new IllegalStateException("旧行情写入已关闭");
    }

    public void upsert(StockPrice p) {
        requireLegacyWriter();
        update("""
            INSERT INTO stock_prices (stock_id, trade_date, open, close, high, low, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              open = VALUES(open), close = VALUES(close),
              high = VALUES(high), low  = VALUES(low),
              volume = VALUES(volume)
            """,
            p.getStockId(), Date.valueOf(p.getTradeDate()),
            p.getOpen(), p.getClose(), p.getHigh(), p.getLow(), p.getVolume());
    }

    public void upsertCloseOnly(long stockId, LocalDate date, BigDecimal close) {
        requireLegacyWriter();
        update("""
            INSERT INTO stock_prices (stock_id, trade_date, open, close, high, low, volume)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            ON DUPLICATE KEY UPDATE
              close = VALUES(close),
              volume = IF(VALUES(volume) > volume, VALUES(volume), volume)
            """,
            // open / high / low 均用 close 填充，volume 固定传 0（INSERT 时占位）
            stockId, Date.valueOf(date), close, close, close, close);
    }
}
