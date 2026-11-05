-- Idempotent schema, applied at startup (spring.sql.init.mode=always) before Hibernate
-- validates the entity mappings against it (spring.jpa.hibernate.ddl-auto=validate).
-- For schema *changes* over time, move this into a migration tool such as Flyway.

-- Auto-incrementing ID source. The application calls nextval() itself so it knows the ID
-- (and therefore the Base62 short code) before the row is inserted.
CREATE SEQUENCE IF NOT EXISTS url_mappings_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS url_mappings (
    id           BIGINT                   PRIMARY KEY DEFAULT nextval('url_mappings_id_seq'),
    short_code   VARCHAR(11)              NOT NULL,
    original_url VARCHAR(2048)            NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

ALTER SEQUENCE url_mappings_id_seq OWNED BY url_mappings.id;

-- Redirect lookups are by short code; the unique index makes them an index scan
-- and guarantees no two rows can ever share a code.
CREATE UNIQUE INDEX IF NOT EXISTS ux_url_mappings_short_code ON url_mappings (short_code);
