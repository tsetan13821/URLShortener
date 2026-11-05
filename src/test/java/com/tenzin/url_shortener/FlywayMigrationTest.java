package com.tenzin.url_shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Flyway ran at startup and the database is at the newest migration, whether it was created
 * fresh (CI: V1 executed) or existed before Flyway (local: baselined at version 1).
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allMigrationsAreAppliedAndNonePending() {
        assertThat(flyway.info().pending()).as("pending migrations").isEmpty();

        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        String latestAvailable = flyway.info().all()[flyway.info().all().length - 1].getVersion().getVersion();
        assertThat(current.getVersion().getVersion()).isEqualTo(latestAvailable);
    }

    @Test
    void historyTableRecordsSuccessfulSteps() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        // Version 1 is either the executed V1 script (fresh database) or the baseline marker
        // (database that predates Flyway).
        assertThat(rows.getFirst().get("version")).isEqualTo("1");
        assertThat(rows.getFirst().get("type")).isIn("SQL", "BASELINE");
    }
}
