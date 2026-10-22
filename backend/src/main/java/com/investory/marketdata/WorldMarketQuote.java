package com.investory.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Missing fields stay null. Collection time is never a replacement for quote time. */
public record WorldMarketQuote(String symbol, String name, String flag, Double lat, Double lng,
                               BigDecimal price, BigDecimal change, BigDecimal changePct,
                               String source, String state, Instant quoteTime, String timestampScope,
                               LocalDate asOfDate, Instant receivedAt, String fallbackReason) {
    public boolean valid() { return price != null && price.signum() > 0; }
    public WorldMarketQuote stale(String reason) {
        return new WorldMarketQuote(symbol, name, flag, lat, lng, price, change, changePct,
            source, "stale", quoteTime, timestampScope, asOfDate, receivedAt, reason);
    }
    public WorldMarketQuote withReason(String reason) {
        return new WorldMarketQuote(symbol, name, flag, lat, lng, price, change, changePct,
            source, state, quoteTime, timestampScope, asOfDate, receivedAt, reason);
    }
    public static WorldMarketQuote of(WorldMarketCatalog.Spec spec, BigDecimal price, BigDecimal change,
                                      BigDecimal pct, String source, Instant quoteTime, String scope,
                                      LocalDate asOfDate, Instant receivedAt) {
        return new WorldMarketQuote(spec.dbSymbol(), spec.name(), spec.flag(), spec.indicator() ? null : spec.lat(),
            spec.indicator() ? null : spec.lng(), price, change, pct, source, "available", quoteTime, scope, asOfDate, receivedAt, null);
    }
    public static WorldMarketQuote unavailable(WorldMarketCatalog.Spec spec, String reason) {
        return of(spec, null, null, null, null, null, "UNKNOWN", null, null).withState("unavailable", reason);
    }
    private WorldMarketQuote withState(String state, String reason) {
        return new WorldMarketQuote(symbol, name, flag, lat, lng, price, change, changePct,
            source, state, quoteTime, timestampScope, asOfDate, receivedAt, reason);
    }
}
