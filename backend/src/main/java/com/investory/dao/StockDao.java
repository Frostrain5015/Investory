package com.investory.dao;

import com.investory.model.Stock;
import com.investory.util.PinyinUtil;
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
        String k = keyword.trim();
        String contains = "%" + k + "%";
        String starts   = k + "%";

        // Pure ASCII letters → also search pinyin abbreviation column
        if (k.matches("[a-zA-Z]+")) {
            String py = k.toLowerCase();
            String pyStarts   = py + "%";
            String pyContains = "%" + py + "%";
            return query("""
                SELECT * FROM stocks
                WHERE symbol LIKE ? OR name LIKE ?
                   OR (name_pinyin IS NOT NULL AND name_pinyin LIKE ?)
                ORDER BY CASE
                  WHEN symbol = ?      THEN 1
                  WHEN symbol LIKE ?   THEN 2
                  WHEN name   LIKE ?   THEN 3
                  WHEN name_pinyin LIKE ? THEN 4
                  WHEN name   LIKE ?   THEN 5
                  WHEN name_pinyin LIKE ? THEN 6
                  ELSE 7 END, name LIMIT 8
                """, this::map,
                contains, contains, pyContains,
                k, starts, starts, pyStarts, contains, pyContains);
        }
        return query("""
            SELECT * FROM stocks
            WHERE symbol LIKE ? OR name LIKE ?
            ORDER BY CASE
              WHEN symbol = ?    THEN 1
              WHEN symbol LIKE ? THEN 2
              WHEN name   LIKE ? THEN 3
              WHEN name   LIKE ? THEN 4
              ELSE 5 END, name LIMIT 8
            """, this::map,
            contains, contains,
            k, starts, starts, contains);
    }

    public List<Stock> findAll() {
        return query("SELECT * FROM stocks ORDER BY market, name", this::map);
    }

    public long upsert(Stock stock) {
        Stock existing = findBySymbol(stock.getSymbol());
        if (existing != null) return existing.getId();
        String pinyin = PinyinUtil.toAbbr(stock.getName());
        return insert("INSERT INTO stocks (symbol, name, market, currency, name_pinyin) VALUES (?, ?, ?, ?, ?)",
                stock.getSymbol(), stock.getName(), stock.getMarket(), stock.getCurrency(),
                pinyin.isEmpty() ? null : pinyin);
    }
}
