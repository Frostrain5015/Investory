package com.investory.controller.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/market")
public class MarketIndexController {


    private final JdbcTemplate jdbc;
    private final com.investory.marketdata.WorldMarketService market;

    public MarketIndexController(JdbcTemplate jdbc, com.investory.marketdata.WorldMarketService market) {
        this.jdbc = jdbc;
        this.market = market;
    }

    public record ExchangeRatesResponse(Map<String, BigDecimal> rates, String base, String updatedAt) {}

    /** Rates are stored as foreign-currency units per CNY, matching display conversion. */
    @GetMapping("/exchange-rates")
    public ExchangeRatesResponse exchangeRates() {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT currency, rate FROM exchange_rates")) {
            if (row.get("currency") instanceof String currency && row.get("rate") instanceof Number rate) {
                BigDecimal value = new BigDecimal(rate.toString());
                if (value.signum() > 0) rates.put(currency, value);
            }
        }
        rates.put("CNY", BigDecimal.ONE);
        // The legacy table has no freshness timestamp; do not fabricate one.
        return new ExchangeRatesResponse(Collections.unmodifiableMap(rates), "CNY", null);
    }

    @GetMapping("/indices")
    public com.investory.marketdata.WorldMarketService.Snapshot getIndices() {
        return market.snapshot();
    }

    @GetMapping("/indices/progress")
    public com.investory.marketdata.WorldMarketService.Progress getProgress() {
        return market.progress();
    }

    @GetMapping("/news")
    public List<Map<String, Object>> getNews() {
        try {
            return jdbc.queryForList(
                "SELECT title, source, url, summary, category, score, country_code, published_at FROM world_news ORDER BY score DESC, published_at DESC LIMIT 25");
        } catch (Exception e) { return List.of(); }
    }

    @GetMapping("/world-data")
    public Map<String, Object> getWorldData() {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("static/world.json");
            if (is != null) {
                data.put("world", new String(is.readAllBytes()));
                is.close();
            }
        } catch (Exception ignored) {}
        return data;
    }
}
