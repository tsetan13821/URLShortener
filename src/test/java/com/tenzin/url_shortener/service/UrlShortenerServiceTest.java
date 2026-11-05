package com.tenzin.url_shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.tenzin.url_shortener.config.AppProperties;
import com.tenzin.url_shortener.dto.ShortUrlResponse;
import com.tenzin.url_shortener.entity.UrlMapping;
import com.tenzin.url_shortener.exception.ShortUrlNotFoundException;
import com.tenzin.url_shortener.repository.UrlMappingRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:15:30.123456789Z");
    private static final String SECRET = "unit-test-code-secret";

    /** Real generator (pure function): lets the tests assert on actual codes. */
    private final ShortCodeGenerator codes = new ShortCodeGenerator(SECRET);

    @Mock
    private UrlMappingRepository repository;

    @Mock
    private UrlCacheService cache;

    @Mock
    private ClickCounter clicks;

    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties("https://sho.rt/",
                new AppProperties.Cache(Duration.ofHours(1), "url:", Duration.ofSeconds(10)),
                new AppProperties.Clicks("clicks:pending", Duration.ofSeconds(30)),
                new AppProperties.RateLimit(true, 20, Duration.ofMinutes(1), "rl:create:", ""),
                new AppProperties.Codes(SECRET));
        service = new UrlShortenerService(repository, cache, clicks, codes, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordClickDelegatesToClickCounter() {
        service.recordClick("21");

        verify(clicks).record("21");
    }

    @Test
    void statsAddPendingRedisClicksToStoredCount() {
        when(repository.findByShortCode("21"))
                .thenReturn(Optional.of(new UrlMapping(125L, "21", "https://example.com/page", NOW)));
        when(clicks.pending("21")).thenReturn(4L);

        var stats = service.stats("21");

        assertThat(stats.shortUrl()).isEqualTo("https://sho.rt/21");
        assertThat(stats.clicks()).isEqualTo(4L); // 0 stored + 4 pending
        assertThat(stats.lastClickedAt()).isNull();
    }

    @Test
    void statsForUnknownCodeThrowsNotFound() {
        when(repository.findByShortCode("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stats("nope")).isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void shortenDerivesTheCodeFromTheSequenceIdAndSavesOnce() {
        String expected = codes.codeFor(125L);
        when(repository.nextId()).thenReturn(125L);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortUrlResponse response = service.shorten("https://example.com/page");

        ArgumentCaptor<UrlMapping> saved = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(125L);
        assertThat(saved.getValue().getShortCode()).isEqualTo(expected).hasSize(6);
        assertThat(saved.getValue().isNew()).isTrue();

        assertThat(response.shortCode()).isEqualTo(expected);
        // Trailing slash in base-url is normalised away.
        assertThat(response.shortUrl()).isEqualTo("https://sho.rt/" + expected);
        assertThat(response.originalUrl()).isEqualTo("https://example.com/page");
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-11-05T10:15:30.123456Z"));
    }

    @Test
    void shortenSkipsAnIdWhoseCodeIsAlreadyTaken() {
        // e.g. a link created before CODE_SECRET was changed already holds the code for ID 7
        when(repository.nextId()).thenReturn(7L, 8L);
        when(repository.existsByShortCode(codes.codeFor(7L))).thenReturn(true);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortUrlResponse response = service.shorten("https://example.com/page");

        assertThat(response.shortCode()).isEqualTo(codes.codeFor(8L));
        verify(repository).save(any(UrlMapping.class));
    }

    @Test
    void shortenDoesNotTouchTheCache() {
        when(repository.nextId()).thenReturn(1L);

        service.shorten("https://example.com");

        verifyNoInteractions(cache);
    }

    @Test
    void resolveCacheHitSkipsTheDatabase() {
        when(cache.get("21")).thenReturn(Optional.of("https://example.com/page"));

        assertThat(service.resolve("21")).isEqualTo("https://example.com/page");

        verifyNoInteractions(repository);
        verify(cache, never()).put(anyString(), anyString());
    }

    @Test
    void resolveCacheMissReadsDatabaseAndPopulatesCache() {
        when(cache.get("21")).thenReturn(Optional.empty());
        when(repository.findByShortCode("21"))
                .thenReturn(Optional.of(new UrlMapping(125L, "21", "https://example.com/page", NOW)));

        assertThat(service.resolve("21")).isEqualTo("https://example.com/page");

        verify(cache).put("21", "https://example.com/page");
    }

    @Test
    void resolveUnknownCodeThrowsNotFoundAndCachesNothing() {
        when(cache.get("nope")).thenReturn(Optional.empty());
        when(repository.findByShortCode("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("nope"))
                .isInstanceOf(ShortUrlNotFoundException.class)
                .hasMessageContaining("nope");

        verify(cache, never()).put(anyString(), anyString());
    }
}
