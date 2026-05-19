package com.investory.dao;

import com.investory.model.Stock;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class StockDao extends BaseDao {

    private Stock map(ResultSet rs) throws SQLException {
        Stock s = new Stock();
        s.setId(rs.getLong("id"));
        s.setSymbol(rs.getString("symbol"));
        s.setName(rs.getString("name"));
        s.setMarket(rs.getString("market"));
        s.setCurrency(rs.getString("currency"));
        return s;
    }

    public Stock findBySymbol(String symbol) {
        return queryOne("SELECT * FROM stocks WHERE symbol = ?", this::map, symbol);
    }

    public Stock findById(long id) {
        return queryOne("SELECT * FROM stocks WHERE id = ?", this::map, id);
    }

    public List<Stock> search(String keyword) {
        String q = "%" + keyword + "%";
        return query("SELECT * FROM stocks WHERE name LIKE ? OR symbol LIKE ? ORDER BY market, name LIMIT 20",
                this::map, q, q);
    }

    public List<Stock> findAll() {
        return query("SELECT * FROM stocks ORDER BY market, name", this::map);
    }

    public long upsert(Stock stock) {
        Stock existing = findBySymbol(stock.getSymbol());
        if (existing != null) return existing.getId();
        return insert("INSERT INTO stocks (symbol, name, market, currency) VALUES (?, ?, ?, ?)",
                stock.getSymbol(), stock.getName(), stock.getMarket(), stock.getCurrency());
    }
}
