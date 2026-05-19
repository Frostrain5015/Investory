package com.investory.dao;

import com.investory.model.StockPrice;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

@Repository
public class StockPriceDao extends BaseDao {

    private StockPrice map(ResultSet rs) throws SQLException {
        StockPrice p = new StockPrice();
        p.setId(rs.getLong("id"));
        p.setStockId(rs.getLong("stock_id"));
        Date d = rs.getDate("trade_date");
        if (d != null) p.setTradeDate(d.toLocalDate());
        p.setOpen(rs.getBigDecimal("open"));
        p.setClose(rs.getBigDecimal("close"));
        p.setHigh(rs.getBigDecimal("high"));
        p.setLow(rs.getBigDecimal("low"));
        p.setVolume(rs.getLong("volume"));
        return p;
    }

    public StockPrice findLatest(long stockId) {
        return queryOne(
            "SELECT * FROM stock_prices WHERE stock_id = ? ORDER BY trade_date DESC LIMIT 1",
            this::map, stockId);
    }

    public List<StockPrice> findRange(long stockId, LocalDate from, LocalDate to) {
        return query(
            "SELECT * FROM stock_prices WHERE stock_id = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
            this::map, stockId, Date.valueOf(from), Date.valueOf(to));
    }

    public void upsert(StockPrice p) {
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

    public BigDecimal findLatestClose(long stockId) {
        StockPrice sp = findLatest(stockId);
        return sp != null ? sp.getClose() : null;
    }
}
