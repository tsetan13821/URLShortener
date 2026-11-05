package com.tenzin.url_shortener.controller;

import java.net.URI;

import com.tenzin.url_shortener.dto.CreateShortUrlRequest;
import com.tenzin.url_shortener.dto.ShortUrlResponse;
import com.tenzin.url_shortener.service.UrlShortenerService;

import jakarta.validation.Valid;

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

    public UrlController(UrlShortenerService service) {
        this.service = service;
    }

    /** Creates a short link. 201 Created, with the short URL in both the body and the Location header. */
    @PostMapping(path = "/api/urls",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrlResponse response = service.shorten(request.url());
        return ResponseEntity.created(URI.create(response.shortUrl())).body(response);
    }

    /** Redirects to the original URL with 302 Found; unknown codes become 404 via the exception handler. */
    @GetMapping("/{shortCode:" + SHORT_CODE_PATTERN + "}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String originalUrl = service.resolve(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .build();
    }
}
