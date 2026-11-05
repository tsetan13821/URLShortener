package com.tenzin.url_shortener.dto;

import java.time.Instant;

/** Response body for {@code GET /api/urls/{shortCode}/stats}. */
public record LinkStatsResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        Instant createdAt,
        /** Includes clicks not yet written to PostgreSQL, so it's current. */
        long clicks,
        /** Accurate to the click flush interval; null if the link was never clicked (or not flushed yet). */
        Instant lastClickedAt) {
}
