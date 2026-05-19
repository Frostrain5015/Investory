package com.investory.dao;

import com.investory.model.Stock;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class StockDao extends BaseDao {

    private static final StockDao INSTANCE = new StockDao();
    public static StockDao get() { return INSTANCE; }

    private Stock map(ResultSet rs) throws SQLException {
        Stock s = new Stock();
        s.setId(rs.getLong("id"));
        s.setSymbol(rs.getString("symbol"));
        s.setName(rs.getString("name"));
        s.setMarket(rs.getString("market"));
        s.setCurrency(rs.getString("currency"));
        return s;
    }

    public Stock findBySymbol(String symbol) throws SQLException {
        return queryOne("SELECT * FROM stocks WHERE symbol = ?", this::map, symbol);
    }

    public Stock findById(long id) throws SQLException {
        return queryOne("SELECT * FROM stocks WHERE id = ?", this::map, id);
    }

    public List<Stock> search(String keyword) throws SQLException {
        String q = "%" + keyword + "%";
        return query("SELECT * FROM stocks WHERE name LIKE ? OR symbol LIKE ? ORDER BY market, name LIMIT 20",
                this::map, q, q);
    }

    public List<Stock> findAll() throws SQLException {
        return query("SELECT * FROM stocks ORDER BY market, name", this::map);
    }

    /** Insert or ignore if symbol already exists. Returns the stock id. */
    public long upsert(Stock stock) throws SQLException {
        Stock existing = findBySymbol(stock.getSymbol());
        if (existing != null) return existing.getId();
        return insert("INSERT INTO stocks (symbol, name, market, currency) VALUES (?, ?, ?, ?)",
                stock.getSymbol(), stock.getName(), stock.getMarket(), stock.getCurrency());
    }
}
