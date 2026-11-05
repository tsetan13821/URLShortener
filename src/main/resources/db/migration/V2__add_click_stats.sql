-- V2: click analytics.
-- click_count is updated in batches from Redis (see ClickCounter), so existing rows start at 0.
-- last_clicked_at is set when those batches are written, so it is accurate to the flush interval.
ALTER TABLE url_mappings
    ADD COLUMN click_count     BIGINT                      NOT NULL DEFAULT 0,
    ADD COLUMN last_clicked_at TIMESTAMP(6) WITH TIME ZONE NULL;
