package com.tenzin.url_shortener.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes click counts with plain SQL. Increments are relative ({@code click_count + n}), so they're
 * correct no matter how many app instances flush concurrently or in what order.
 */
@Repository
public class ClickStatsRepository {

    // GREATEST ignores NULL in PostgreSQL, so a link's first click simply sets last_clicked_at,
    // and an older timestamp can never overwrite a newer one.
    private static final String ADD_CLICKS = """
            UPDATE url_mappings
               SET click_count = click_count + ?,
                   last_clicked_at = GREATEST(last_clicked_at, ?)
             WHERE short_code = ?""";

    private final JdbcTemplate jdbc;

    public ClickStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds many links' clicks in one JDBC batch (one round trip), in a single transaction. */
    @Transactional
    public void addClicks(Map<String, Long> clicksByShortCode, Instant clickedAt) {
        Timestamp at = Timestamp.from(clickedAt);
        List<Object[]> rows = new ArrayList<>(clicksByShortCode.size());
        clicksByShortCode.forEach((code, count) -> rows.add(new Object[] {count, at, code}));
        jdbc.batchUpdate(ADD_CLICKS, rows);
    }

    /** Adds a single click immediately (used only while Redis is unavailable). */
    @Transactional
    public void addClick(String shortCode, Instant clickedAt) {
        jdbc.update(ADD_CLICKS, 1L, Timestamp.from(clickedAt), shortCode);
    }
}
