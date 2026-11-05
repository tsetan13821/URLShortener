package com.tenzin.url_shortener.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
        @Valid @NotNull Cache cache) {

    public AppProperties {
        // Normalise so we can always build "<baseUrl>/<code>".
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    public record Cache(
            @NotNull Duration ttl,
            @NotNull String keyPrefix,
            @NotNull Duration failureBackoff) {
    }
}
