package com.investory.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investory.dao.StockDao;
import com.investory.model.StockPrice;
import com.investory.util.ProcessRunner;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** SQL-free history gateway. Python owns routing, adjustment and the bounded disk cache. */
@Service
public class HistoricalPriceService {
    public record Series(String symbol, String source, String adjustment, List<StockPrice> prices, boolean finalized) {
        public Series(String symbol, String source, String adjustment, List<StockPrice> prices) {
            this(symbol,source,adjustment,prices,true);
        }
    }
    public static class HistoryUnavailable extends RuntimeException {
        public HistoryUnavailable() { super("历史行情暂不可用，请稍后重试"); }
    }
    private record Cached(Series series, Instant fetchedAt) { }
    private final StockDao stocks;
    private final ObjectMapper json;
    private final String python;
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, .75f, true);
    private final ConcurrentHashMap<String, CompletableFuture<Series>> pending = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), task -> {
        Thread thread = new Thread(task, "market-history"); thread.setDaemon(true); return thread;
    });
    private long nextStart;

    public HistoricalPriceService(StockDao stocks, ObjectMapper json, @Value("${python.executable:python3}") String python) {
        this.stocks = stocks; this.json = json; this.python = python;
    }

    @PreDestroy public void close() { workers.shutdownNow(); }

    public List<StockPrice> range(long stockId, LocalDate from, LocalDate to) {
        return range(stockId, from, to, "none");
    }

    public List<StockPrice> range(long stockId, LocalDate from, LocalDate to, String adjustment) {
        var stock = stocks.findById(stockId);
        if (stock == null) throw new IllegalArgumentException("未找到股票基础信息");
        List<StockPrice> result = new ArrayList<>();
        for (StockPrice bar : series(stock.getSymbol(), from, to, adjustment).prices()) {
            StockPrice copy = new StockPrice(); copy.setStockId(stockId); copy.setTradeDate(bar.getTradeDate());
            copy.setOpen(bar.getOpen()); copy.setHigh(bar.getHigh()); copy.setLow(bar.getLow()); copy.setClose(bar.getClose()); copy.setVolume(bar.getVolume());
            result.add(copy);
        }
        return result;
    }

    public Series series(String symbol, LocalDate from, LocalDate to, String adjustment) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9.^-]{1,40}") || from == null || to == null
                || from.isBefore(LocalDate.of(1990,1,1)) || from.isAfter(to)
                || !List.of("none","forward","backward").contains(adjustment))
            throw new IllegalArgumentException("历史行情参数无效");
        symbol = canonical(symbol);
        final String instrument = symbol;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        to = to.isAfter(today) ? today : to;
        if (from.isAfter(to)) return new Series(symbol, "unavailable", adjustment, List.of());
        String key = symbol + "|" + from + "|" + to + "|" + adjustment + ("none".equals(adjustment) ? "" : "|" + today);
        synchronized (cache) {
            Cached hit = cache.get(key);
            if (hit != null && Duration.between(hit.fetchedAt(), Instant.now()).getSeconds() < (to.isAfter(today.minusDays(2)) ? 25 : "none".equals(adjustment) && hit.series().finalized() && !hit.series().prices().isEmpty() ? Long.MAX_VALUE : 86400)) return hit.series();
        }
        var candidate = new CompletableFuture<Series>();
        var existing = pending.putIfAbsent(key, candidate);
        if (existing == null) {
            LocalDate end = to;
            try { workers.submit(() -> {
                try {
                    Series result = fetch(instrument, from, end, adjustment);
                    synchronized (cache) {
                        cache.put(key, new Cached(result, Instant.now()));
                        while (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
                    }
                    candidate.complete(result);
                } catch (Exception error) { candidate.completeExceptionally(error); }
                finally { pending.remove(key, candidate); }
            }); } catch (RejectedExecutionException error) { pending.remove(key, candidate); candidate.completeExceptionally(error); }
            existing = candidate;
        }
        try { return existing.get(30, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new HistoryUnavailable(); }
        catch (ExecutionException | TimeoutException error) { throw new HistoryUnavailable(); }
    }

    /** Rate gate reserves a start slot; no network I/O is performed under a global lock. */
    private void rateGate() throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long start = Math.max(now, nextStart); nextStart = start + TimeUnit.MILLISECONDS.toNanos(250);
            wait = start - now;
        }
        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
    }

    protected Series fetch(String symbol, LocalDate from, LocalDate to, String adjustment) throws Exception {
        rateGate();
        File script = new File("script/market_history.py");
        if (!script.isFile()) script = new File("../script/market_history.py").getCanonicalFile();
        if (!script.isFile()) throw new HistoryUnavailable();
        var builder = new ProcessBuilder(python, "-u", script.getAbsolutePath(), "--symbol", symbol,
            "--start", from.toString(), "--end", to.toString(), "--adjust", adjustment);
        builder.directory(script.getParentFile());
        StringBuilder output = new StringBuilder();
        int exit = ProcessRunner.run(builder, Duration.ofSeconds(25), line -> {
            if (line.startsWith("RESULT:")) {
                if (line.length() > 4 * 1024 * 1024) throw new HistoryUnavailable();
                output.append(line.substring(7));
            }
        });
        if (exit != 0 || output.isEmpty()) throw new HistoryUnavailable();
        JsonNode root = json.readTree(output.toString());
        if (!canonical(root.path("symbol").asText()).equals(canonical(symbol))) throw new HistoryUnavailable();
        Series result = parse(symbol, from, to, root);
        if (!result.adjustment().equals(adjustment) && !(result.adjustment().equals("none") && isIndex(symbol))) throw new HistoryUnavailable();
        return result;
    }

    private static boolean isIndex(String symbol) {
        return symbol.matches("000[0-9]{3}\\.SH|399[0-9]{3}\\.SZ") || symbol.endsWith(".TI")
            || WorldMarketCatalog.SPECS.stream().anyMatch(spec -> spec.dbSymbol().equals(symbol));
    }

    private static String canonical(String symbol) {
        String value = symbol.strip().toUpperCase(Locale.ROOT);
        if (value.matches("[01]\\.\\d{6}")) {
            String digits = value.substring(2);
            return digits + (digits.startsWith("4") || digits.startsWith("8") || digits.startsWith("92")
                ? ".BJ" : value.startsWith("1.") ? ".SH" : ".SZ");
        }
        if (value.matches("116\\.\\d{1,6}")) return String.format(Locale.ROOT,"%05d.HK",Long.parseLong(value.substring(4)));
        if (value.matches("10[0567]\\.[A-Z0-9-]+")) return value.substring(4) + ".US";
        return value;
    }

    Series parse(String symbol, LocalDate from, LocalDate to, JsonNode root) {
        if (root.has("error") || !root.path("points").isArray() || !root.path("source").isTextual()
                || !List.of("none","forward","backward").contains(root.path("adjust").asText())) throw new HistoryUnavailable();
        List<StockPrice> bars = new ArrayList<>(); LocalDate previous = null;
        for (JsonNode row : root.path("points")) {
            LocalDate day = LocalDate.parse(row.path("date").asText());
            if (day.isBefore(from) || day.isAfter(to) || previous != null && !day.isAfter(previous)) throw new HistoryUnavailable();
            previous = day;
            for (String field : List.of("open","high","low","close"))
                if (!row.path(field).isNumber() || row.path(field).decimalValue().signum() <= 0) throw new HistoryUnavailable();
            StockPrice bar = new StockPrice(); bar.setTradeDate(day);
            bar.setOpen(row.path("open").decimalValue()); bar.setHigh(row.path("high").decimalValue());
            bar.setLow(row.path("low").decimalValue()); bar.setClose(row.path("close").decimalValue());
            if (bar.getLow().compareTo(bar.getOpen().min(bar.getClose())) > 0 || bar.getHigh().compareTo(bar.getOpen().max(bar.getClose())) < 0) throw new HistoryUnavailable();
            if (row.path("volume").isNumber()) {
                if (row.path("volume").decimalValue().signum() < 0) throw new HistoryUnavailable();
                bar.setVolume(row.path("volume").decimalValue().setScale(0, java.math.RoundingMode.HALF_UP).longValueExact());
            }
            bars.add(bar);
        }
        return new Series(symbol, root.path("source").asText(), root.path("adjust").asText(), List.copyOf(bars), !root.path("transientEmpty").asBoolean(false));
    }
}
