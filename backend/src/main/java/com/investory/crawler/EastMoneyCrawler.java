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
import java.util.concurrent.CompletableFuture;
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
                .header("Accept", "application/json")
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** Fetch with retry and delay between pages. */
    private String getWithRetry(String url) throws Exception {
        for (int i = 0; i < 3; i++) {
            try {
                return get(url);
            } catch (Exception e) {
                if (i == 2) throw e;
                Thread.sleep(2000 * (i + 1));
            }
        }
        throw new RuntimeException("unreachable");
    }

    // ── Full stock list ─────────────────────────────────────────────────────────

    /** Fetch all stocks (A-shares from EastMoney + Sina in parallel, EastMoney优先). */
    public int fetchAllStocks() {
        // A-shares: EastMoney and Sina run in parallel
        var emFuture = CompletableFuture.supplyAsync(() ->
            fetchMarket("A股(东方财富)", "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23", "CNY", "SH"));
        var sinaFuture = CompletableFuture.supplyAsync(this::fetchSinaAStocks);

        int emA = emFuture.join();
        int sinaA = sinaFuture.join();
        int total = emA > 0 ? emA : sinaA;
        log.info("A股: 东方财富=" + emA + " 新浪=" + sinaA + " → 采用" + (emA > 0 ? "东方财富" : "新浪") + " " + total + "只");

        total += fetchMarket("港股", "m:128+t:3,m:128+t:4,m:128+t:1,m:128+t:2", "HKD", "HK");
        total += fetchMarket("美股", "m:105+t:3,m:105+t:4,m:105+t:1,m:105+t:2", "USD", "US");
        log.info("Fetched " + total + " total stocks into database");
        return total;
    }

    /** Fallback: fetch A-shares from Sina Finance. */
    private int fetchSinaAStocks() {
        int total = 0;
        String[] nodes = {"sh_a", "sz_a"};
        for (String node : nodes) {
            int page = 1;
            while (true) {
                String url = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                        + "Market_Center.getHQNodeData?page=" + page + "&num=500&sort=symbol&asc=1&node=" + node;
                try {
                    String body = getWithRetry(url);
                    JsonArray items = JsonParser.parseString(body).getAsJsonArray();
                    if (items.isEmpty()) break;
                    for (JsonElement el : items) {
                        JsonObject item = el.getAsJsonObject();
                        String code = item.get("code").getAsString();
                        String name = item.get("name").getAsString();
                        String market = node.equals("sh_a") ? "SH" : "SZ";
                        String symbol = marketPrefix(market) + "." + code;

                        Stock stock = new Stock();
                        stock.setSymbol(symbol);
                        stock.setName(name);
                        stock.setMarket(market);
                        stock.setCurrency("CNY");
                        stockDao.upsert(stock);
                        total++;
                    }
                    Thread.sleep(500);
                    page++;
                } catch (Exception e) {
                    log.warning("Sina " + node + " fetch failed at page " + page + ": " + e.getMessage());
                    break;
                }
            }
        }
        log.info("A股(Sina): " + total + " stocks");
        return total;
    }

    private int fetchMarket(String label, String fs, String currency, String market) {
        int total = 0;
        int page = 1;
        while (true) {
            String url = "https://push2.eastmoney.com/api/qt/clist/get"
                    + "?pn=" + page + "&pz=500&fs=" + java.net.URLEncoder.encode(fs, java.nio.charset.StandardCharsets.UTF_8)
                    + "&fields=f12,f14&po=1&np=1&fltt=2&invt=2";
            try {
                String body = getWithRetry(url);
                // Polite delay between pages
                if (page > 1) Thread.sleep(1500);
                JsonElement root = JsonParser.parseString(body);
                JsonArray items;
                // Handle both array and object response formats
                if (root.isJsonArray()) {
                    items = root.getAsJsonArray();
                } else {
                    JsonObject obj = root.getAsJsonObject();
                    JsonObject data = obj.getAsJsonObject("data");
                    if (data == null) break;
                    JsonElement diffEl = data.get("diff");
                    if (diffEl == null || diffEl.isJsonNull()) break;
                    JsonObject diff = diffEl.getAsJsonObject();
                    if (diff.isEmpty()) break;
                    items = new JsonArray();
                    for (var entry : diff.entrySet()) items.add(entry.getValue());
                }

                if (items.isEmpty()) break;
                for (JsonElement el : items) {
                    JsonObject item = el.getAsJsonObject();
                    String code = item.get("f12").getAsString();
                    String name = item.get("f14").getAsString();
                    String mkt = "SH".equals(market) ? guessMarket(code) : market;
                    String symbol = marketPrefix(mkt) + "." + code;

                    Stock stock = new Stock();
                    stock.setSymbol(symbol);
                    stock.setName(name);
                    stock.setMarket(mkt);
                    stock.setCurrency(currency);

                    stockDao.upsert(stock);
                    total++;
                }
                // Array format returns all in one page; object format may paginate
                if (root.isJsonArray() || items.size() < 500) break;
                page++;
            } catch (Exception e) {
                log.warning(label + " fetch failed at page " + page + ": " + e.getMessage());
                break;
            }
        }
        log.info(label + ": " + total + " stocks");
        return total;
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
