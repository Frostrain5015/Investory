package com.investory.marketdata;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** One shared bounded refresh; no trading, database writes, or continuous poller. */
@Service
public class WorldMarketService {
    static final Duration CACHE_TTL = Duration.ofSeconds(90);
    private final HithinkDataClient hithink;
    private final LegacyWorldMarketClient legacy;
    private final ExecutorService executor;
    private final Clock clock;
    private Snapshot cache;
    public record Snapshot(List<WorldMarketQuote> indices, List<WorldMarketQuote> indicators, Instant updatedAt,
                           List<String> sources, String hithinkStatus, String hithinkError) { }
    @Autowired
    public WorldMarketService(HithinkDataClient hithink, LegacyWorldMarketClient legacy,
                              @Qualifier("marketIndexExecutor") ExecutorService executor) {
        this(hithink, legacy, executor, Clock.systemUTC());
    }
    WorldMarketService(HithinkDataClient hithink, LegacyWorldMarketClient legacy, ExecutorService executor, Clock clock) {
        this.hithink = hithink; this.legacy = legacy; this.executor = executor; this.clock = clock;
    }
    public synchronized Snapshot snapshot() {
        if (cache != null && Duration.between(cache.updatedAt(), clock.instant()).compareTo(CACHE_TTL) < 0) return cache;
        Map<String, WorldMarketQuote> previous = new HashMap<>();
        if (cache != null) {
            cache.indices().forEach(quote -> previous.put(quote.symbol(), quote));
            cache.indicators().forEach(quote -> previous.put(quote.symbol(), quote));
        }
        Map<String, WorldMarketQuote> fresh = new HashMap<>();
        String status = !hithink.status().enabled() ? "disabled" : !hithink.status().configured() ? "not_configured" : "active";
        String error = null;
        if (status.equals("active")) {
            var supported = WorldMarketCatalog.SPECS.stream().filter(WorldMarketCatalog.Spec::supportsHithink).toList();
            try {
                var result = hithink.indexSnapshot(supported.stream().map(WorldMarketCatalog.Spec::dbSymbol).toList());
                for (var price : result.items()) {
                    var spec = supported.stream().filter(item -> item.dbSymbol().equals(price.symbol())).findFirst().orElse(null);
                    if (spec == null) continue;
                    var quote = WorldMarketQuote.of(spec, price.price(), price.change(), price.changePct(), result.source(),
                        result.providerTimestamp(), result.timestampScope(), null, result.receivedAt());
                    if (quote.valid()) fresh.put(spec.dbSymbol(), quote);
                }
                if (fresh.size() != supported.size()) { status = "partial"; error = "DATA_MISSING_ITEMS"; }
            } catch (HithinkDataClient.DataException failure) { status = "fallback"; error = failure.code(); }
        }
        var futures = new LinkedHashMap<WorldMarketCatalog.Spec, Future<WorldMarketQuote>>();
        for (var spec : WorldMarketCatalog.SPECS) {
            if (!fresh.containsKey(spec.dbSymbol())) futures.put(spec, executor.submit(() -> legacy.fetch(spec)));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(13);
        for (var entry : futures.entrySet()) {
            var spec = entry.getKey();
            WorldMarketQuote quote = null;
            try { quote = entry.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); entry.getValue().cancel(true); }
            catch (Exception failure) { entry.getValue().cancel(true); }
            String reason = spec.supportsHithink() ? error : null;
            if (quote == null || !quote.valid()) {
                var old = previous.get(spec.dbSymbol());
                if (old != null && old.valid() && old.receivedAt() != null
                    && Duration.between(old.receivedAt(), clock.instant()).compareTo(Duration.ofHours(24)) < 0)
                    quote = old.stale(reason == null ? "LIVE_REQUEST_FAILED" : reason);
                else quote = legacy.lastClose(spec);
            } else if (reason != null) quote = quote.withReason(reason);
            fresh.put(spec.dbSymbol(), quote != null && quote.valid() ? quote : WorldMarketQuote.unavailable(spec, reason == null ? "DATA_UNAVAILABLE" : reason));
        }
        var indices = new ArrayList<WorldMarketQuote>();
        var indicators = new ArrayList<WorldMarketQuote>();
        var sources = new LinkedHashSet<String>();
        for (var spec : WorldMarketCatalog.SPECS) {
            var quote = fresh.get(spec.dbSymbol());
            (spec.indicator() ? indicators : indices).add(quote);
            if (quote.source() != null) sources.add(quote.source());
        }
        cache = new Snapshot(List.copyOf(indices), List.copyOf(indicators), clock.instant(), List.copyOf(sources), status, error);
        return cache;
    }
}
