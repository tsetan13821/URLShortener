package com.tenzin.url_shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.tenzin.url_shortener.config.AppProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class UrlCacheServiceTest {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration BACKOFF = Duration.ofSeconds(10);

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));

    private UrlCacheService cache;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        AppProperties properties = new AppProperties("http://sho.rt",
                new AppProperties.Cache(TTL, "url:", BACKOFF));
        cache = new UrlCacheService(redis, properties, clock);
    }

    @Test
    void getReturnsCachedValueUsingPrefixedKey() {
        when(values.get("url:abc")).thenReturn("https://example.com");

        assertThat(cache.get("abc")).contains("https://example.com");
    }

    @Test
    void getReturnsEmptyOnMiss() {
        assertThat(cache.get("abc")).isEmpty();
    }

    @Test
    void putUsesConfiguredTtl() {
        cache.put("abc", "https://example.com");

        verify(values).set("url:abc", "https://example.com", TTL);
    }

    @Test
    void redisFailureOnGetIsTreatedAsMiss() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get("abc")).isEmpty();
    }

    @Test
    void redisFailureOnPutIsSwallowed() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(values).set(anyString(), anyString(), any(Duration.class));

        assertThatNoException().isThrownBy(() -> cache.put("abc", "https://example.com"));
    }

    @Test
    void afterFailureRedisIsSkippedUntilBackoffExpires() {
        when(values.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn("https://example.com");

        assertThat(cache.get("abc")).isEmpty();          // fails -> backoff starts
        verify(values, times(1)).get(anyString());

        clock.advance(BACKOFF.minusSeconds(1));
        assertThat(cache.get("abc")).isEmpty();          // still backing off: Redis not called
        cache.put("abc", "https://example.com");          // writes skipped too
        verify(values, times(1)).get(anyString());
        verify(values, times(0)).set(anyString(), anyString(), any(Duration.class));

        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.get("abc")).contains("https://example.com"); // backoff over: recovered
        verify(values, times(2)).get(anyString());
    }

    /** Minimal controllable clock for testing time-based behaviour. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
