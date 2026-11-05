package com.tenzin.url_shortener.repository;

import java.util.Optional;

import com.tenzin.url_shortener.entity.UrlMapping;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    /** Served by the unique index on {@code short_code}. */
    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /**
     * Reserves the next auto-incremented ID. Sequences are non-transactional and never hand out
     * the same value twice, so concurrent creators can't collide (a rolled-back create just
     * leaves a gap, which is harmless).
     *
     * <p>Explicitly read-write: Spring Data defaults query methods to read-only transactions,
     * and PostgreSQL rejects {@code nextval()} inside a read-only transaction.
     */
    @Transactional
    @Query(value = "SELECT nextval('url_mappings_id_seq')", nativeQuery = true)
    long nextId();
}
