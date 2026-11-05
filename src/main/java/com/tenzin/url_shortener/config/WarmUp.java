package com.tenzin.url_shortener.config;

import java.time.Instant;

import com.tenzin.url_shortener.dto.CreateShortUrlRequest;
import com.tenzin.url_shortener.dto.ShortUrlResponse;
import com.tenzin.url_shortener.service.ShortCodeGenerator;
import com.tenzin.url_shortener.service.UrlShortenerService;
import com.tenzin.url_shortener.exception.ShortUrlNotFoundException;

import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the request path once at startup, so the first real visitor doesn't pay for it.
 *
 * <p>The JVM loads and initialises classes on first use: URL validation (including the TLD list),
 * the code generator, JSON, Hibernate's query for a lookup, the Redis connection. On a full CPU that
 * is under a second; on a small cloud instance (0.1 CPU) the first request after a start took over
 * 20 seconds. Runners finish before the app reports itself ready ({@code /actuator/health/readiness}),
 * so a platform that waits for readiness never routes a visitor to a cold instance.
 *
 * <p>Never fails startup: a problem here (e.g. Redis down) only means a slower first request.
 */
@Component
class WarmUp implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WarmUp.class);

    private final Validator validator;
    private final ShortCodeGenerator codes;
    private final JsonMapper json;
    private final UrlShortenerService service;

    WarmUp(Validator validator, ShortCodeGenerator codes, JsonMapper json, UrlShortenerService service) {
        this.validator = validator;
        this.codes = codes;
        this.json = json;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.nanoTime();
        try {
            validator.validate(new CreateShortUrlRequest("https://example.com/warm-up?q=1"));
            String code = codes.codeFor(1);
            json.writeValueAsString(new ShortUrlResponse(code, "https://sho.rt/" + code,
                    "https://example.com/warm-up", Instant.now()));
            try {
                service.resolve("0"); // cache miss -> Redis + PostgreSQL lookup; "0" is never issued
            } catch (ShortUrlNotFoundException expected) {
                // the normal outcome
            }
        } catch (RuntimeException e) {
            log.warn("Warm-up incomplete (first requests may be slower): {}", e.toString());
        }
        log.info("Warm-up done in {} ms", (System.nanoTime() - start) / 1_000_000);
    }
}
