package com.investory.crawler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.investory.dao.StockDao;
import com.investory.model.Stock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Seeds security identities only; never stores market prices. */
@Component
public class StockCatalogSeeder {

    private static final Logger log = Logger.getLogger(StockCatalogSeeder.class.getName());

    @Autowired private StockDao stockDao;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
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
