package com.tenzin.url_shortener.config;

import io.lettuce.core.ClientOptions;

import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis client tuning for "cache, not source of truth" usage.
 *
 * <p>The connection itself (host, port, password, timeouts) comes from {@code spring.data.redis.*}
 * and Boot's auto-configured {@code StringRedisTemplate}; this class only adjusts behaviour that
 * isn't exposed as a property.
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /**
     * By default Lettuce <em>queues</em> commands while disconnected and replays them after
     * reconnecting, so during an outage every request would block for the full command timeout.
     * Rejecting instead makes cache calls fail immediately so the request falls straight back to
     * PostgreSQL. Auto-reconnect stays on, so the cache recovers by itself once Redis is back.
     */
    @Bean
    LettuceClientOptionsBuilderCustomizer failFastWhenRedisIsDisconnected() {
        return builder -> builder
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
