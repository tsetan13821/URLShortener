package com.tenzin.url_shortener.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tenzin.url_shortener.config.AppProperties;
import com.tenzin.url_shortener.repository.ClickStatsRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Counts redirects without making redirects write to PostgreSQL.
 *
 * <ol>
 *   <li><b>Record:</b> each click is {@code HINCRBY <pending-key> <code> 1} in Redis: atomic,
 *       sub-millisecond, safe across any number of app instances.</li>
 *   <li><b>Flush:</b> every {@code app.clicks.flush-interval} the whole hash is read <em>and</em>
 *       deleted in one atomic Lua script, then written to PostgreSQL in one JDBC batch. Clicks
 *       arriving during a flush land in a fresh hash for the next flush, and two instances can
 *       never flush the same clicks twice.</li>
 * </ol>
 *
 * <p>While Redis is unavailable, clicks are written to PostgreSQL directly (slower, but nothing is
 * lost). Known limit: Redis runs without persistence, so if Redis itself crashes, clicks recorded
 * since the last flush (at most one interval's worth) are lost.
 */
@Service
public class ClickCounter {

    private static final Logger log = LoggerFactory.getLogger(ClickCounter.class);

    /** Atomically return every field/value of the hash and delete it. */
    private static final RedisScript<List> TAKE_ALL = new DefaultRedisScript<>("""
            local all = redis.call('HGETALL', KEYS[1])
            redis.call('DEL', KEYS[1])
            return all""", List.class);

    private final StringRedisTemplate redis;
    private final RedisAvailability availability;
    private final ClickStatsRepository stats;
    private final String pendingKey;
    private final Clock clock;

    public ClickCounter(StringRedisTemplate redis, RedisAvailability availability,
                        ClickStatsRepository stats, AppProperties properties, Clock clock) {
        this.redis = redis;
        this.availability = availability;
        this.stats = stats;
        this.pendingKey = properties.clicks().pendingKey();
        this.clock = clock;
    }

    /** Records one click. Never throws: a counting problem must never break a redirect. */
    public void record(String shortCode) {
        if (availability.isAvailable()) {
            try {
                redis.opsForHash().increment(pendingKey, shortCode, 1);
                return;
            } catch (RuntimeException e) {
                availability.reportFailure("click count", e);
            }
        }
        try {
            stats.addClick(shortCode, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Could not record click for '{}': {}", shortCode, e.toString());
        }
    }

    /** Clicks recorded in Redis but not yet written to PostgreSQL (0 if Redis is unavailable). */
    public long pending(String shortCode) {
        if (!availability.isAvailable()) {
            return 0;
        }
        try {
            Object value = redis.opsForHash().get(pendingKey, shortCode);
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (RuntimeException e) {
            availability.reportFailure("click read", e);
            return 0;
        }
    }

    /** Moves all pending clicks from Redis to PostgreSQL. Runs on a schedule; safe to call directly. */
    @Scheduled(fixedDelayString = "${app.clicks.flush-interval}", initialDelayString = "${app.clicks.flush-interval}")
    public void flush() {
        if (!availability.isAvailable()) {
            return; // nothing can be pending in Redis that we could read right now
        }
        Map<String, Long> batch;
        try {
            batch = takeAll();
        } catch (RuntimeException e) {
            availability.reportFailure("click flush", e);
            return;
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            stats.addClicks(batch, clock.instant());
            log.debug("Flushed {} clicks for {} links", batch.values().stream().mapToLong(Long::longValue).sum(), batch.size());
        } catch (RuntimeException e) {
            // PostgreSQL failed: put the counts back so the next flush retries them.
            log.warn("Writing {} links' clicks to PostgreSQL failed, will retry: {}", batch.size(), e.toString());
            restore(batch);
        }
    }

    private Map<String, Long> takeAll() {
        List<?> flat = redis.execute(TAKE_ALL, List.of(pendingKey));
        Map<String, Long> batch = new HashMap<>();
        if (flat != null) {
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                batch.put(flat.get(i).toString(), Long.parseLong(flat.get(i + 1).toString()));
            }
        }
        return batch;
    }

    private void restore(Map<String, Long> batch) {
        try {
            batch.forEach((code, count) -> redis.opsForHash().increment(pendingKey, code, count));
        } catch (RuntimeException e) {
            availability.reportFailure("click restore", e);
            log.warn("Lost {} links' pending clicks: Redis and PostgreSQL both failed", batch.size());
        }
    }
}
