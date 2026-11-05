package com.tenzin.url_shortener.dto;

import java.time.Instant;

/** Response body for {@code POST /api/urls}. */
public record ShortUrlResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        Instant createdAt) {
}
