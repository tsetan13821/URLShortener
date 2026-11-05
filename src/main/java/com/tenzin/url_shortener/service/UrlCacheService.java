package com.tenzin.url_shortener.service;

import java.util.Optional;

import com.tenzin.url_shortener.config.AppProperties;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Best-effort Redis cache of short code -> original URL.
 *
 * <p><b>Never throws.</b> Redis is an accelerator, not the source of truth: any Redis failure is
 * reported to {@link RedisAvailability} and treated as a cache miss, so callers fall back to
 * PostgreSQL. While Redis is backing off, the cache isn't called at all.
 */
@Service
public class UrlCacheService {

    private final StringRedisTemplate redis;
    private final AppProperties.Cache config;
    private final RedisAvailability availability;

    public UrlCacheService(StringRedisTemplate redis, AppProperties properties, RedisAvailability availability) {
        this.redis = redis;
        this.config = properties.cache();
        this.availability = availability;
    }

    /** @return the cached URL, or empty on a cache miss <em>or</em> if Redis is unavailable */
    public Optional<String> get(String shortCode) {
        if (!availability.isAvailable()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redis.opsForValue().get(key(shortCode)));
        } catch (RuntimeException e) {
            availability.reportFailure("cache read", e);
            return Optional.empty();
        }
    }

    /** Stores the mapping with the configured TTL. Silently skipped if Redis is unavailable. */
    public void put(String shortCode, String originalUrl) {
        if (!availability.isAvailable()) {
            return;
        }
        try {
            redis.opsForValue().set(key(shortCode), originalUrl, config.ttl());
        } catch (RuntimeException e) {
            availability.reportFailure("cache write", e);
        }
    }

    private String key(String shortCode) {
        return config.keyPrefix() + shortCode;
    }
}
