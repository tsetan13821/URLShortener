package com.tenzin.url_shortener.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe binding of the {@code app.*} properties from application.yml.
 * Validated at startup so a misconfiguration fails fast instead of at the first request.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String baseUrl,
        @Valid @NotNull Cache cache,
        @Valid @NotNull Clicks clicks,
        @Valid @NotNull RateLimit rateLimit) {

    public AppProperties {
        // Normalise so we can always build "<baseUrl>/<code>".
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    public record Cache(
            @NotNull Duration ttl,
            @NotNull String keyPrefix,
            /** After a Redis error, skip Redis (cache and click counting) for this long. */
            @NotNull Duration failureBackoff) {
    }

    public record Clicks(
            /** Redis hash holding clicks not yet written to PostgreSQL (field = short code). */
            @NotBlank String pendingKey,
            /** How often pending clicks are written to PostgreSQL. */
            @NotNull Duration flushInterval) {
    }

    public record RateLimit(
            boolean enabled,
            /** Links one client may create per window. */
            @Positive int createLimit,
            /** Length of the counting window. */
            @NotNull Duration window,
            /** Redis key prefix for the per-client counters. */
            @NotBlank String keyPrefix) {
    }
}
