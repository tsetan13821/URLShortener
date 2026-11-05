package com.tenzin.url_shortener.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.tenzin.url_shortener.entity.UrlMapping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs against the real PostgreSQL test database (not an embedded substitute), so it also
 * proves schema.sql and the entity mapping agree (ddl-auto=validate) and that the
 * sequence/unique index behave as intended. Each test rolls back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UrlMappingRepositoryTest {

    @Autowired
    private UrlMappingRepository repository;

    @Test
    void nextIdReturnsStrictlyIncreasingValues() {
        long first = repository.nextId();
        long second = repository.nextId();

        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void savesAndFindsByShortCode() {
        long id = repository.nextId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.saveAndFlush(new UrlMapping(id, "code" + id, "https://example.com/a", now));

        assertThat(repository.findByShortCode("code" + id))
                .hasValueSatisfying(found -> {
                    assertThat(found.getId()).isEqualTo(id);
                    assertThat(found.getOriginalUrl()).isEqualTo("https://example.com/a");
                    assertThat(found.getCreatedAt()).isEqualTo(now);
                });
    }

    @Test
    void findByUnknownShortCodeIsEmpty() {
        assertThat(repository.findByShortCode("doesNotExist")).isEmpty();
    }

    @Test
    void uniqueIndexRejectsDuplicateShortCode() {
        long id1 = repository.nextId();
        long id2 = repository.nextId();
        String code = "dup" + id1;
        repository.saveAndFlush(new UrlMapping(id1, code, "https://example.com/1", Instant.now()));

        assertThatThrownBy(() ->
                repository.saveAndFlush(new UrlMapping(id2, code, "https://example.com/2", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
