package com.tenzin.url_shortener.exception;

/** Thrown when a short code has no mapping. Translated to HTTP 404 by {@link GlobalExceptionHandler}. */
public class ShortUrlNotFoundException extends RuntimeException {

    private final String shortCode;

    public ShortUrlNotFoundException(String shortCode) {
        super("No URL found for short code '" + shortCode + "'");
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
