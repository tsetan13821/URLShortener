package com.tenzin.url_shortener.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.tenzin.url_shortener.config.AppProperties;
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
    private final AppProperties properties;
    private final Clock clock;

    public UrlShortenerService(UrlMappingRepository repository, UrlCacheService cache,
                               AppProperties properties, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reserves the next sequence value, Base62-encodes it into the short code and stores the
     * mapping in a single INSERT. Every call creates a new code, even for a URL seen before.
     */
    @Transactional
    public ShortUrlResponse shorten(String originalUrl) {
        long id = repository.nextId();
        UrlMapping mapping = new UrlMapping(
                id,
                Base62.encode(id),
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

    private ShortUrlResponse toResponse(UrlMapping mapping) {
        return new ShortUrlResponse(
                mapping.getShortCode(),
                properties.baseUrl() + "/" + mapping.getShortCode(),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt());
    }
}
