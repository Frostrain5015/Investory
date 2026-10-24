package com.investory.model;
import java.math.BigDecimal;
import java.time.Instant;
/** Provider time and receipt time are separate; receipt alone never proves freshness. */
public record Quote(BigDecimal price, Instant fetchedAt, BigDecimal previousClose, BigDecimal change,
                    BigDecimal changePct, String source, Instant quoteTime, boolean live, SessionMetrics metrics) {
    /** Snapshot volume is shares; turnover is the instrument's original currency. */
    public record SessionMetrics(BigDecimal open, BigDecimal high, BigDecimal low,
                                 BigDecimal previousClose, BigDecimal volume, BigDecimal turnover) { }
    public Quote(BigDecimal price, Instant fetchedAt, BigDecimal previousClose, BigDecimal change,
                 BigDecimal changePct, String source, Instant quoteTime, boolean live) {
        this(price,fetchedAt,previousClose,change,changePct,source,quoteTime,live,null);
    }
    public Quote(BigDecimal price, Instant fetchedAt) { this(price,fetchedAt,null,null,null,null,null,false); }
    public Quote withLive(boolean value) { return new Quote(price,fetchedAt,previousClose,change,changePct,source,quoteTime,value,metrics); }
}
