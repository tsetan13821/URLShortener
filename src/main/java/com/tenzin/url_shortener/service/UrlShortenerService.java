package com.tenzin.url_shortener.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.tenzin.url_shortener.config.AppProperties;
import com.tenzin.url_shortener.dto.LinkStatsResponse;
import com.tenzin.url_shortener.dto.ShortUrlResponse;
import com.tenzin.url_shortener.entity.UrlMapping;
import com.tenzin.url_shortener.exception.ShortUrlNotFoundException;
import com.tenzin.url_shortener.repository.UrlMappingRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlShortenerService {

    private final UrlMappingRepository repository;
    private final UrlCacheService cache;
    private final ClickCounter clicks;
    private final ShortCodeGenerator codes;
    private final AppProperties properties;
    private final Clock clock;

    public UrlShortenerService(UrlMappingRepository repository, UrlCacheService cache, ClickCounter clicks,
                               ShortCodeGenerator codes, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.clicks = clicks;
        this.codes = codes;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reserves the next sequence value, turns it into a short code ({@link ShortCodeGenerator}) and
     * stores the mapping in a single INSERT. Every call creates a new code, even for a URL seen before.
     *
     * <p>Codes from one secret never collide (the generator is a permutation), but a code may already
     * be taken by a link created under an earlier secret or the old sequential scheme. Such an ID is
     * skipped. With one secret this check never fails, so it costs one indexed lookup per create.
     */
    @Transactional
    public ShortUrlResponse shorten(String originalUrl) {
        long id;
        String shortCode;
        do {
            id = repository.nextId();
            shortCode = codes.codeFor(id);
        } while (repository.existsByShortCode(shortCode));
        UrlMapping mapping = new UrlMapping(
                id,
                shortCode,
                originalUrl,
                // PostgreSQL stores microseconds; truncate so the response matches what's persisted.
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        repository.save(mapping);
        return toResponse(mapping);
    }

    /**
     * Cache-aside lookup: Redis first; on a miss (or if Redis is down) read PostgreSQL and
     * populate Redis for next time.
     *
     * <p>Deliberately <em>not</em> {@code @Transactional}: a transaction here would check out a
     * database connection even for cache hits. The repository call runs in its own short
     * read-only transaction only when PostgreSQL is actually needed.
     *
     * @throws ShortUrlNotFoundException if the code has no mapping
     */
    public String resolve(String shortCode) {
        Optional<String> cached = cache.get(shortCode);
        if (cached.isPresent()) {
            return cached.get();
        }
        String originalUrl = repository.findByShortCode(shortCode)
                .map(UrlMapping::getOriginalUrl)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
        cache.put(shortCode, originalUrl);
        return originalUrl;
    }

    /** Counts one visit of an existing link (cheap: a Redis increment, see {@link ClickCounter}). */
    public void recordClick(String shortCode) {
        clicks.record(shortCode);
    }

    /**
     * Link details plus click count. The count adds clicks still pending in Redis to those already
     * written to PostgreSQL, so it's current even between flushes.
     *
     * @throws ShortUrlNotFoundException if the code has no mapping
     */
    @Transactional(readOnly = true)
    public LinkStatsResponse stats(String shortCode) {
        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
        return new LinkStatsResponse(
                mapping.getShortCode(),
                shortUrl(mapping),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt(),
                mapping.getClickCount() + clicks.pending(shortCode),
                mapping.getLastClickedAt());
    }

    private ShortUrlResponse toResponse(UrlMapping mapping) {
        return new ShortUrlResponse(
                mapping.getShortCode(),
                shortUrl(mapping),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt());
    }

    private String shortUrl(UrlMapping mapping) {
        return properties.baseUrl() + "/" + mapping.getShortCode();
    }
}
