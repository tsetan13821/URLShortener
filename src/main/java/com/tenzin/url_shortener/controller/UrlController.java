package com.tenzin.url_shortener.controller;

import java.net.URI;

import com.tenzin.url_shortener.dto.CreateShortUrlRequest;
import com.tenzin.url_shortener.dto.LinkStatsResponse;
import com.tenzin.url_shortener.dto.ShortUrlResponse;
import com.tenzin.url_shortener.service.RateLimiter;
import com.tenzin.url_shortener.service.UrlShortenerService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UrlController {

    /**
     * Only 1-11 Base62 characters can be a short code (11 = length of Long.MAX_VALUE in Base62).
     * Anything else (e.g. /favicon.ico) never reaches the database; Spring answers 404 and the
     * global exception handler renders it as a problem detail.
     */
    static final String SHORT_CODE_PATTERN = "[0-9A-Za-z]{1,11}";

    private final UrlShortenerService service;
    private final RateLimiter rateLimiter;

    public UrlController(UrlShortenerService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Creates a short link. 201 Created, with the short URL in both the body and the Location header.
     *
     * <p>Rate limited per client IP (429 Too Many Requests when over). The check runs after
     * validation, so rejected URLs don't use up the client's allowance. The IP is the TCP peer, or
     * the X-Forwarded-For client when {@code server.forward-headers-strategy} trusts a proxy.
     */
    @PostMapping(path = "/api/urls",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request,
                                                   HttpServletRequest servletRequest) {
        rateLimiter.checkCreate(servletRequest.getRemoteAddr());
        ShortUrlResponse response = service.shorten(request.url());
        return ResponseEntity.created(URI.create(response.shortUrl())).body(response);
    }

    /**
     * Redirects to the original URL with 302 Found; unknown codes become 404 via the exception handler.
     * A trailing slash (a common copy/paste slip, e.g. {@code /3/}) is accepted too.
     *
     * <p>Counts a click for GET only. HEAD (also routed here by Spring) is what link-preview bots and
     * uptime checkers mostly send, so it is not counted.
     */
    @GetMapping({"/{shortCode:" + SHORT_CODE_PATTERN + "}", "/{shortCode:" + SHORT_CODE_PATTERN + "}/"})
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpMethod method) {
        String originalUrl = service.resolve(shortCode);
        if (HttpMethod.GET.equals(method)) {
            service.recordClick(shortCode);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .build();
    }

    /** Click statistics for one link. Unknown codes become 404 via the exception handler. */
    @GetMapping(path = "/api/urls/{shortCode:" + SHORT_CODE_PATTERN + "}/stats",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public LinkStatsResponse stats(@PathVariable String shortCode) {
        return service.stats(shortCode);
    }
}
