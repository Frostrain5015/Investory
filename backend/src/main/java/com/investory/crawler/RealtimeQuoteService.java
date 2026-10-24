package com.investory.crawler;

import com.investory.marketdata.HithinkDataClient;
import com.investory.model.Quote;
import com.investory.model.Stock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared read-only quote path for holdings, watchlists and individual reads. */
@Service
public class RealtimeQuoteService {
    private static final Duration TTL = Duration.ofSeconds(25);
    private final HithinkDataClient provider;
    private final LegacyEquityQuoteClient legacy;
    private final ExecutorService workers;
    private final Clock clock;
    private final Map<String, Entry> cache = new LinkedHashMap<>(128, .75f, true);
    private Instant retryAfter = Instant.MIN;
    private long lastRequestNanos;
    private record Entry(Quote quote, Instant checkedAt) { }

    @Autowired
    public RealtimeQuoteService(HithinkDataClient provider, LegacyEquityQuoteClient legacy,
                                @Qualifier("marketIndexExecutor") ExecutorService workers) {
        this(provider,legacy,workers,Clock.systemUTC());
    }
    RealtimeQuoteService(HithinkDataClient provider, LegacyEquityQuoteClient legacy, ExecutorService workers, Clock clock) {
        this.provider=provider; this.legacy=legacy; this.workers=workers; this.clock=clock;
    }
    public Quote getQuote(Stock stock) { return getQuotes(List.of(stock)).get(stock.getSymbol()); }
    public BigDecimal getPrice(Stock stock) { Quote quote=getQuote(stock); return quote==null ? null : quote.price(); }

    /** Background metadata reads may reuse memory without triggering providers. */
    public synchronized Map<String, Quote> cachedQuotes(List<Stock> stocks) {
        var result = new HashMap<String, Quote>();
        for (Stock stock : stocks) {
            if (stock == null || stock.getSymbol() == null) continue;
            Entry entry = cache.get(key(stock));
            Quote quote = entry == null ? null : entry.quote();
            if (quote != null && quote.fetchedAt() != null
                    && Duration.between(quote.fetchedAt(), clock.instant()).compareTo(Duration.ofHours(24)) < 0) {
                result.put(stock.getSymbol(), quote.withLive(quote.live() && isLive(quote, stock.getMarket())));
            }
        }
        return result;
    }

    /** Batches of 50; aliases and concurrent readers share cached successes and failures. */
    public synchronized Map<String, Quote> getQuotes(List<Stock> stocks) {
        Instant startedAt=clock.instant();
        var missing=new LinkedHashMap<String, Stock>();
        for (Stock stock:stocks) {
            if (stock==null || stock.getSymbol()==null) continue;
            String key=key(stock);
            Entry entry=cache.get(key);
            if (entry==null || Duration.between(entry.checkedAt(),clock.instant()).compareTo(TTL)>=0) missing.putIfAbsent(key,stock);
        }
        var obtained=new HashMap<String, Quote>();
        var supported=missing.values().stream().filter(s -> aShareCode(s)!=null).toList();
        var status=provider.status();
        if (status.enabled() && status.configured() && !clock.instant().isBefore(retryAfter)) {
            for (int start=0;start<supported.size();start+=50) {
                var group=supported.subList(start,Math.min(start+50,supported.size()));
                try {
                    long wait=TimeUnit.MILLISECONDS.toNanos(250)-(System.nanoTime()-lastRequestNanos);
                    if (wait>0) TimeUnit.NANOSECONDS.sleep(wait);
                    lastRequestNanos=System.nanoTime();
                    var batch=provider.snapshot(group.stream().map(RealtimeQuoteService::aShareCode).distinct().toList());
                    for (var item:batch.items()) {
                        if (item.price()==null || item.price().signum()<=0) continue;
                        Quote quote=new Quote(item.price(),batch.receivedAt(),item.previousClose(),item.change(),item.changePct(),
                            batch.source(),batch.providerTimestamp(),false,
                            new Quote.SessionMetrics(item.open(),item.high(),item.low(),item.previousClose(),item.volume(),item.turnover()));
                        obtained.put(item.symbol(),quote.withLive(isLive(quote,"SH")));
                    }
                } catch (HithinkDataClient.DataException error) {
                    retryAfter=clock.instant().plusSeconds(60);
                    break;
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            }
        }
        var fallbacks=new LinkedHashMap<String, Future<Quote>>();
        for (var entry:missing.entrySet()) if (!obtained.containsKey(entry.getKey()))
            fallbacks.put(entry.getKey(),workers.submit(() -> legacy.getQuote(entry.getValue())));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        for (var entry:fallbacks.entrySet()) {
            Quote quote=null;
            try { quote=entry.getValue().get(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); entry.getValue().cancel(true); }
            catch (Exception error) { entry.getValue().cancel(true); }
            if (quote==null || quote.price()==null || quote.price().signum()<=0) {
                Entry old=cache.get(entry.getKey());
                if (old!=null && old.quote()!=null && old.quote().fetchedAt()!=null
                    && Duration.between(old.quote().fetchedAt(),clock.instant()).compareTo(Duration.ofHours(24))<0)
                    quote=old.quote().withLive(false);
                else quote=null;
            }
            obtained.put(entry.getKey(),quote);
        }
        for (String key:missing.keySet()) cache.put(key,new Entry(obtained.get(key),startedAt));
        while (cache.size()>4096) cache.remove(cache.keySet().iterator().next());
        var result=new HashMap<String,Quote>();
        for (Stock stock:stocks) {
            if (stock==null || stock.getSymbol()==null) continue;
            Entry entry=cache.get(key(stock));
            if (entry!=null && entry.quote()!=null) {
                Quote quote=entry.quote();
                result.put(stock.getSymbol(),quote.withLive(quote.live() && isLive(quote,stock.getMarket())));
            }
        }
        return result;
    }
    private boolean isLive(Quote quote,String market) {
        if (quote.quoteTime()==null || market==null || !List.of("SH","SZ","BJ").contains(market)) return false;
        var now=clock.instant().atZone(ZoneId.of("Asia/Shanghai"));
        if (now.getDayOfWeek().getValue()>5) return false;
        int minute=now.getHour()*60+now.getMinute();
        Duration age=Duration.between(quote.quoteTime(),clock.instant());
        return ((minute>=570 && minute<690)||(minute>=780 && minute<900)) && !age.isNegative() && age.compareTo(Duration.ofMinutes(2))<=0;
    }
    static String aShareCode(Stock stock) {
        if (stock.getMarket()==null || !List.of("SH","SZ","BJ").contains(stock.getMarket())) return null;
        try {
            String code=HithinkDataClient.normalizeSymbol(stock.getSymbol());
            // Only equities: do not send indices or ETFs to the stock endpoint.
            if (!code.endsWith("."+stock.getMarket())) return null;
            return code.matches("(6\\d{5}\\.SH|[03]\\d{5}\\.SZ|[489]\\d{5}\\.BJ)") ? code : null;
        } catch (IllegalArgumentException error) { return null; }
    }
    private static String key(Stock stock) { String code=aShareCode(stock); return code==null ? stock.getMarket()+"|"+stock.getSymbol() : code; }
}
