-- V1: initial schema. Never edit this file once applied; add V2__..., V3__... for changes.
--
-- Databases created before Flyway was introduced already contain exactly this schema; Flyway
-- marks them as version 1 ("baseline") instead of running this script on them.

-- Auto-incrementing ID source. The application calls nextval() itself so it knows the ID
-- (and therefore the Base62 short code) before the row is inserted.
CREATE SEQUENCE url_mappings_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE url_mappings (
    id           BIGINT                      PRIMARY KEY DEFAULT nextval('url_mappings_id_seq'),
    short_code   VARCHAR(11)                 NOT NULL,
    original_url VARCHAR(2048)               NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

ALTER SEQUENCE url_mappings_id_seq OWNED BY url_mappings.id;

-- Redirect lookups are by short code; the unique index makes them an index scan
-- and guarantees no two rows can ever share a code.
CREATE UNIQUE INDEX ux_url_mappings_short_code ON url_mappings (short_code);
