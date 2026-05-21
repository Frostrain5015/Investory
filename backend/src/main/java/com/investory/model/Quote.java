package com.investory.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Real-time price with the instant it was fetched from the source. */
public record Quote(BigDecimal price, Instant fetchedAt) {}
