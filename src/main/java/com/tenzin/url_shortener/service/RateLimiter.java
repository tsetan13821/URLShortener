package com.tenzin.url_shortener.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.tenzin.url_shortener.config.AppProperties;
import com.tenzin.url_shortener.exception.RateLimitExceededException;

import org.apache.commons.validator.routines.InetAddressValidator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Caps how many links one client can create per window ({@code app.rate-limit.*}).
 *
 * <p>Fixed window per client in Redis: the first request creates a counter that expires after one
 * window, later requests increment it, and once it passes the limit the client is refused until the
 * counter expires. INCR and PEXPIRE run together in one Lua script, so the counter can never be left
 * without an expiry (which would block that client forever), and the limit holds across any number
 * of app instances sharing the Redis.
 *
 * <p>Fails open: while Redis is unavailable, nothing is limited. Link creation is the product's core
 * function, and refusing everyone because an accelerator is down would be worse than a short window
 * without a limit.
 */
@Service
public class RateLimiter {

    /** Returns {count, milliseconds until the window ends}. Repairs a counter that lost its expiry. */
    private static final RedisScript<List> HIT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {count, ttl}""", List.class);

    private final StringRedisTemplate redis;
    private final RedisAvailability availability;
    private final AppProperties.RateLimit config;

    public RateLimiter(StringRedisTemplate redis, RedisAvailability availability, AppProperties properties) {
        this.redis = redis;
        this.availability = availability;
        this.config = properties.rateLimit();
    }

    /**
     * Counts one link creation for this client.
     *
     * @param clientIp the client's IP address (as seen by the servlet container)
     * @throws RateLimitExceededException if the client is over the limit for the current window
     */
    public void checkCreate(String clientIp) {
        if (!config.enabled() || !availability.isAvailable()) {
            return;
        }
        List<?> result;
        try {
            result = redis.execute(HIT, List.of(config.keyPrefix() + clientKey(clientIp)),
                    String.valueOf(config.window().toMillis()));
        } catch (RuntimeException e) {
            availability.reportFailure("rate limit", e);
            return;
        }
        if (result == null || result.size() < 2) {
            return;
        }
        long count = ((Number) result.get(0)).longValue();
        long ttlMillis = ((Number) result.get(1)).longValue();
        if (count > config.createLimit()) {
            throw new RateLimitExceededException(config.createLimit(), config.window(), Duration.ofMillis(ttlMillis));
        }
    }

    /**
     * The identity a limit applies to. IPv4: the address itself. IPv6: its /64 network, because a
     * single home or server connection usually gets a whole /64 and can pick a fresh address from it
     * for every request, which would make a per-address limit useless.
     */
    static String clientKey(String clientIp) {
        if (!InetAddressValidator.getInstance().isValidInet6Address(clientIp)) {
            return clientIp; // IPv4, or something unexpected: use as-is
        }
        try {
            // Safe: the string was just validated as an IPv6 literal, so this never does a DNS lookup.
            InetAddress address = InetAddress.getByName(clientIp);
            if (!(address instanceof Inet6Address)) {
                return address.getHostAddress(); // IPv4-mapped IPv6 (::ffff:1.2.3.4) -> 1.2.3.4
            }
            byte[] bytes = address.getAddress();
            Arrays.fill(bytes, 8, 16, (byte) 0);
            return InetAddress.getByAddress(bytes).getHostAddress() + "/64";
        } catch (java.net.UnknownHostException e) {
            return clientIp;
        }
    }
}
