package com.tenzin.url_shortener.exception;

import java.time.Duration;

/** A client created more links than {@code app.rate-limit.create-limit} allows in one window. */
public class RateLimitExceededException extends RuntimeException {

    private final int limit;
    private final Duration window;
    private final Duration retryAfter;

    public RateLimitExceededException(int limit, Duration window, Duration retryAfter) {
        super("Rate limit of " + limit + " links per " + window + " exceeded");
        this.limit = limit;
        this.window = window;
        this.retryAfter = retryAfter;
    }

    public int getLimit() {
        return limit;
    }

    public Duration getWindow() {
        return window;
    }

    /** Time until the client's current window ends. */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** Whole seconds, rounded up and at least 1, as the Retry-After header requires. */
    public long getRetryAfterSeconds() {
        long millis = retryAfter.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
