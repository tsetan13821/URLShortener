package com.tenzin.url_shortener.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.tenzin.url_shortener.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Shared "is Redis usable right now?" state for everything that uses Redis (cache, click counting).
 *
 * <p>After any Redis failure, Redis is skipped for {@code app.cache.failure-backoff}. Without this,
 * an outage where connections hang (rather than being refused) would add a timeout to every single
 * request. Sharing the state means that once one feature notices Redis is down, the others stop
 * trying too. When the backoff expires, the next caller tries Redis again, so recovery is automatic.
 */
@Component
public class RedisAvailability {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailability.class);

    private final Duration backoff;
    private final Clock clock;

    /** Redis is skipped until this instant. Volatile: read and written by all request threads. */
    private volatile Instant skipRedisUntil = Instant.MIN;

    @Autowired
    public RedisAvailability(AppProperties properties, Clock clock) {
        this(properties.cache().failureBackoff(), clock);
    }

    RedisAvailability(Duration backoff, Clock clock) {
        this.backoff = backoff;
        this.clock = clock;
    }

    public boolean isAvailable() {
        return !clock.instant().isBefore(skipRedisUntil);
    }

    /** Records a failure and starts the backoff window. */
    public void reportFailure(String operation, RuntimeException e) {
        skipRedisUntil = clock.instant().plus(backoff);
        // One line, no stack trace: logged at most about once per backoff window, so an outage
        // doesn't flood the logs.
        log.warn("Redis {} failed, falling back to PostgreSQL and skipping Redis for {}: {}",
                operation, backoff, e.toString());
    }
}
