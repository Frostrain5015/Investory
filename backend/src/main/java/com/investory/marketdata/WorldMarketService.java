package com.investory.marketdata;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared progressive refresh. No provider I/O under the state lock. */
@Service
public class WorldMarketService {
    static final Duration CACHE_TTL = Duration.ofSeconds(25);
    private final HithinkDataClient hithink;
    private final LegacyWorldMarketClient legacy;
    private final ExecutorService executor;
    private final Clock clock;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread=new Thread(task,"world-market-deadline"); thread.setDaemon(true); return thread;
    });
    private Snapshot cache;
    private Instant lastRefreshStarted=Instant.MIN;
    private CompletableFuture<Snapshot> refresh;
    private final Map<String,WorldMarketQuote> current=new HashMap<>(), previous=new HashMap<>();
    private final Set<String> completed=new HashSet<>();
    private final List<Future<?>> tasks=new ArrayList<>();
    private ScheduledFuture<?> deadline;
    private long generation;
    private String status, error;
    public record Snapshot(List<WorldMarketQuote> indices, List<WorldMarketQuote> indicators, Instant updatedAt,
                           List<String> sources, String hithinkStatus, String hithinkError) { }
    public record Progress(List<WorldMarketQuote> indices, List<WorldMarketQuote> indicators, Instant updatedAt,
                           List<String> sources, String hithinkStatus, String hithinkError,
                           boolean refreshing, int completedCount, long generation) { }
    @Autowired
    public WorldMarketService(HithinkDataClient hithink, LegacyWorldMarketClient legacy,
                              @Qualifier("marketIndexExecutor") ExecutorService executor) {
        this(hithink,legacy,executor,Clock.systemUTC());
    }
    WorldMarketService(HithinkDataClient hithink, LegacyWorldMarketClient legacy, ExecutorService executor, Clock clock) {
        this.hithink=hithink; this.legacy=legacy; this.executor=executor; this.clock=clock;
    }
    @PreDestroy public synchronized void close() {
        tasks.forEach(task->task.cancel(true)); timer.shutdownNow();
    }

    /** Metadata and available regions return immediately, even during a cold refresh. */
    public synchronized Progress progress() {
        ensureRefresh();
        return new Progress(cache.indices(),cache.indicators(),cache.updatedAt(),cache.sources(),
            cache.hithinkStatus(),cache.hithinkError(),!refresh.isDone(),completed.size(),generation);
    }
    /** Compatibility path for callers which still require a complete snapshot. */
    public Snapshot snapshot() {
        CompletableFuture<Snapshot> waiting;
        synchronized(this) { ensureRefresh(); waiting=refresh; }
        try { return waiting.get(16,TimeUnit.SECONDS); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
        catch(ExecutionException|TimeoutException ignored) { }
        synchronized(this) { return cache; }
    }
    private void ensureRefresh() {
        if(refresh!=null && !refresh.isDone()) return;
        if(cache!=null && Duration.between(lastRefreshStarted,clock.instant()).compareTo(CACHE_TTL)<0) return;
        lastRefreshStarted=clock.instant(); generation++;
        previous.clear(); previous.putAll(current); current.clear(); completed.clear(); tasks.clear();
        for(var spec:WorldMarketCatalog.SPECS) {
            var old=previous.get(spec.dbSymbol());
            boolean usable=old!=null && old.valid() && old.receivedAt()!=null
                && Duration.between(old.receivedAt(),clock.instant()).compareTo(Duration.ofHours(24))<0;
            current.put(spec.dbSymbol(),usable?old:WorldMarketQuote.unavailable(spec,"LOADING"));
        }
        status=!hithink.status().enabled()?"disabled":!hithink.status().configured()?"not_configured":"active";
        error=null; refresh=new CompletableFuture<>(); cache=project();
        long run=generation;
        deadline=timer.schedule(()->expire(run),14,TimeUnit.SECONDS);
        if(status.equals("active")) submit(()->loadDomestic(run));
        for(var spec:WorldMarketCatalog.SPECS)
            if(!spec.supportsHithink() || !status.equals("active")) submit(()->loadLegacy(run,spec,null));
    }
    private void submit(Runnable work) {
        tasks.add(executor.submit(work));
    }
    private void loadDomestic(long run) {
        var supported=WorldMarketCatalog.SPECS.stream().filter(WorldMarketCatalog.Spec::supportsHithink).toList();
        Map<String,WorldMarketQuote> obtained=new HashMap<>(); String failure=null;
        try {
            var batch=hithink.indexSnapshot(supported.stream().map(WorldMarketCatalog.Spec::dbSymbol).toList());
            for(var price:batch.items()) {
                var spec=supported.stream().filter(item->item.dbSymbol().equals(price.symbol())).findFirst().orElse(null);
                if(spec==null) continue;
                var quote=WorldMarketQuote.of(spec,price.price(),price.change(),price.changePct(),batch.source(),
                    batch.providerTimestamp(),batch.timestampScope(),null,batch.receivedAt());
                if(quote.valid()) obtained.put(spec.dbSymbol(),quote);
            }
            if(obtained.size()!=supported.size()) failure="DATA_MISSING_ITEMS";
        } catch(HithinkDataClient.DataException exception) { failure=exception.code(); }
        catch(Exception exception) { failure="DATA_UNAVAILABLE"; }
        synchronized(this) {
            if(run!=generation || refresh.isDone()) return;
            if(failure!=null) {status=obtained.isEmpty()?"fallback":"partial";error=failure;}
            for(var spec:supported) {
                var quote=obtained.get(spec.dbSymbol());
                if(quote!=null) publish(run,spec,quote,null);
                else { String reason=failure; submit(()->loadLegacy(run,spec,reason)); }
            }
        }
    }
    private void loadLegacy(long run, WorldMarketCatalog.Spec spec, String reason) {
        WorldMarketQuote quote=null;
        try { quote=legacy.fetch(spec); } catch(Exception ignored) { }
        publish(run,spec,quote,reason);
    }
    private synchronized void publish(long run,WorldMarketCatalog.Spec spec,WorldMarketQuote quote,String reason) {
        if(run!=generation || refresh.isDone() || !completed.add(spec.dbSymbol())) return;
        if(quote==null || !quote.valid()) {
            var old=previous.get(spec.dbSymbol());
            if(old!=null && old.valid() && old.receivedAt()!=null
                && Duration.between(old.receivedAt(),clock.instant()).compareTo(Duration.ofHours(24))<0)
                quote=old.stale(reason==null?"LIVE_REQUEST_FAILED":reason);
            else quote=WorldMarketQuote.unavailable(spec,reason==null?"DATA_UNAVAILABLE":reason);
            // Do not cascade failed live requests into up to 24 synchronous
            // historical subprocesses. The previous quote is already shared.
        } else if(reason!=null) quote=quote.withReason(reason);
        current.put(spec.dbSymbol(),quote); cache=project();
        if(completed.size()==WorldMarketCatalog.SPECS.size()) {
            refresh.complete(cache); if(deadline!=null) deadline.cancel(false);
        }
    }
    private synchronized void expire(long run) {
        if(run!=generation || refresh.isDone()) return;
        for(var spec:WorldMarketCatalog.SPECS) if(!completed.contains(spec.dbSymbol())) publish(run,spec,null,"SOURCE_TIMEOUT");
        tasks.forEach(task->task.cancel(true));
    }
    private Snapshot project() {
        var indices=new ArrayList<WorldMarketQuote>(); var indicators=new ArrayList<WorldMarketQuote>(); var sources=new LinkedHashSet<String>();
        for(var spec:WorldMarketCatalog.SPECS) {
            var quote=current.get(spec.dbSymbol());
            (spec.indicator()?indicators:indices).add(quote);
            if(quote.source()!=null) sources.add(quote.source());
        }
        return new Snapshot(List.copyOf(indices),List.copyOf(indicators),clock.instant(),List.copyOf(sources),status,error);
    }
}
