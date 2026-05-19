package com.investory.crawler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.Stock;
import com.investory.model.StockPrice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

@Component
public class EastMoneyCrawler {

    private static final Logger log = Logger.getLogger(EastMoneyCrawler.class.getName());

    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;

    private final HttpClient http = HttpClient.newHttpClient();

    public void updateRealtimePrices(List<Stock> stocks) {
        if (stocks.isEmpty()) return;
        StringBuilder secids = new StringBuilder();
        for (int i = 0; i < stocks.size(); i++) {
            if (i > 0) secids.append(",");
            secids.append(stocks.get(i).getSymbol());
        }
        String url = "https://push2.eastmoney.com/api/qt/ulist.np/get"
                + "?fltt=2&invt=2&secids=" + secids
                + "&fields=f2,f3,f4,f12,f14,f51";
        try {
            String body = get(url);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray diff = root.getAsJsonObject("data").getAsJsonArray("diff");
            if (diff == null) return;

            LocalDate today = LocalDate.now();
            for (JsonElement el : diff) {
                JsonObject item = el.getAsJsonObject();
                String code   = item.get("f12").getAsString();
                String market = guessMarket(code);
                String secid  = marketPrefix(market) + "." + code;

                Stock stock = stockDao.findBySymbol(secid);
                if (stock == null) continue;

                BigDecimal price = safeDecimal(item, "f2");
                if (price == null) continue;

                StockPrice sp = new StockPrice();
                sp.setStockId(stock.getId());
                sp.setTradeDate(today);
                sp.setClose(price);
                sp.setOpen(price);
                sp.setHigh(price);
                sp.setLow(price);
                stockPriceDao.upsert(sp);
            }
        } catch (Exception e) {
            log.warning("Realtime price update failed: " + e.getMessage());
        }
    }

    public void fetchHistory(Stock stock) {
        String endDate   = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String startDate = LocalDate.now().minusYears(2).format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
                + "?secid=" + stock.getSymbol()
                + "&fields1=f1,f2,f3,f4&fields2=f51,f52,f53,f54,f55,f56"
                + "&klt=101&fqt=1&beg=" + startDate + "&end=" + endDate + "&lmt=730";
        try {
            String body = get(url);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("data");
            if (data == null || data.get("klines") == null) return;

            JsonArray klines = data.getAsJsonArray("klines");
            List<StockPrice> prices = new ArrayList<>();
            for (JsonElement el : klines) {
                String[] parts = el.getAsString().split(",");
                if (parts.length < 6) continue;
                StockPrice sp = new StockPrice();
                sp.setStockId(stock.getId());
                sp.setTradeDate(LocalDate.parse(parts[0]));
                sp.setOpen(new BigDecimal(parts[1]));
                sp.setClose(new BigDecimal(parts[2]));
                sp.setHigh(new BigDecimal(parts[3]));
                sp.setLow(new BigDecimal(parts[4]));
                try { sp.setVolume(Long.parseLong(parts[5])); } catch (NumberFormatException ignored) {}
                prices.add(sp);
            }
            for (StockPrice sp : prices) stockPriceDao.upsert(sp);
            log.info("Fetched " + prices.size() + " K-lines for " + stock.getSymbol());
        } catch (Exception e) {
            log.warning("History fetch failed for " + stock.getSymbol() + ": " + e.getMessage());
        }
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://www.eastmoney.com/")
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private BigDecimal safeDecimal(JsonObject obj, String key) {
        try {
            JsonElement el = obj.get(key);
            if (el == null || el.isJsonNull()) return null;
            return new BigDecimal(el.getAsString());
        } catch (Exception e) {
            return null;
        }
    }

    private String guessMarket(String code) {
        if (code.startsWith("6")) return "SH";
        if (code.startsWith("0") || code.startsWith("3")) return "SZ";
        return "HK";
    }

    private String marketPrefix(String market) {
        return switch (market) {
            case "SH" -> "1";
            case "SZ" -> "0";
            case "HK" -> "116";
            default   -> "105";
        };
    }
}
