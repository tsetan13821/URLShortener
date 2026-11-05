package com.tenzin.url_shortener.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.tenzin.url_shortener.exception.RateLimitExceededException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Who counts as "one client". Counting itself needs Redis and is covered by RateLimitIntegrationTest. */
class RateLimiterTest {

    @ParameterizedTest
    @CsvSource({
            "203.0.113.7,                              203.0.113.7",
            // Every address in one /64 is the same client...
            "2001:db8:1:2:aaaa:bbbb:cccc:dddd,         2001:db8:1:2:0:0:0:0/64",
            "2001:db8:1:2::1,                          2001:db8:1:2:0:0:0:0/64",
            "2001:DB8:1:2:FFFF::,                      2001:db8:1:2:0:0:0:0/64",
            // ...but the next /64 is another.
            "2001:db8:1:3::1,                          2001:db8:1:3:0:0:0:0/64",
            "::1,                                      0:0:0:0:0:0:0:0/64",
            // IPv4 arriving over an IPv6 socket is still the IPv4 client.
            "::ffff:203.0.113.7,                       203.0.113.7",
    })
    void clientKey(String ip, String expected) {
        assertThat(RateLimiter.clientKey(ip)).isEqualTo(expected);
    }

    @Test
    void retryAfterRoundsUpToWholeSecondsAndIsAtLeastOne() {
        assertThat(retryAfterSeconds(Duration.ofMillis(41_001))).isEqualTo(42);
        assertThat(retryAfterSeconds(Duration.ofSeconds(42))).isEqualTo(42);
        assertThat(retryAfterSeconds(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(retryAfterSeconds(Duration.ZERO)).isEqualTo(1);
    }

    private static long retryAfterSeconds(Duration remaining) {
        return new RateLimitExceededException(20, Duration.ofMinutes(1), remaining).getRetryAfterSeconds();
    }
}
