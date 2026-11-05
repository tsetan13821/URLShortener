package com.tenzin.url_shortener.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.tenzin.url_shortener.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Best-effort Redis cache of short code -> original URL.
 *
 * <p><b>Never throws.</b> Redis is an accelerator, not the source of truth: any Redis failure is
 * logged and reported as a cache miss, so callers fall back to PostgreSQL.
 *
 * <p>After a failure, Redis is skipped entirely for {@code app.cache.failure-backoff}. Without
 * this, an outage where connections hang (rather than being refused) would add a timeout to
 * every single request. Once the backoff expires, the next request tries Redis again, so the
 * cache recovers by itself.
 */
@Service
public class UrlCacheService {

    private static final Logger log = LoggerFactory.getLogger(UrlCacheService.class);

    private final StringRedisTemplate redis;
    private final AppProperties.Cache config;
    private final Clock clock;

    /** Redis is skipped until this instant. Volatile: read and written by all request threads. */
    private volatile Instant skipRedisUntil = Instant.MIN;

    public UrlCacheService(StringRedisTemplate redis, AppProperties properties, Clock clock) {
        this.redis = redis;
        this.config = properties.cache();
        this.clock = clock;
    }

    /** @return the cached URL, or empty on a cache miss <em>or</em> if Redis is unavailable */
    public Optional<String> get(String shortCode) {
        if (redisIsBackingOff()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redis.opsForValue().get(key(shortCode)));
        } catch (RuntimeException e) {
            onRedisFailure("read", e);
            return Optional.empty();
        }
    }

    /** Stores the mapping with the configured TTL. Silently skipped if Redis is unavailable. */
    public void put(String shortCode, String originalUrl) {
        if (redisIsBackingOff()) {
            return;
        }
        try {
            redis.opsForValue().set(key(shortCode), originalUrl, config.ttl());
        } catch (RuntimeException e) {
            onRedisFailure("write", e);
        }
    }

    private boolean redisIsBackingOff() {
        return clock.instant().isBefore(skipRedisUntil);
    }

    private void onRedisFailure(String operation, RuntimeException e) {
        skipRedisUntil = clock.instant().plus(config.failureBackoff());
        // One line, no stack trace: logged at most once per backoff window, so an outage
        // doesn't flood the logs.
        log.warn("Redis cache {} failed, falling back to PostgreSQL and skipping Redis for {}: {}",
                operation, config.failureBackoff(), e.toString());
    }

    private String key(String shortCode) {
        return config.keyPrefix() + shortCode;
    }
}
