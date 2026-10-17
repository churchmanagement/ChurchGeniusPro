-- ═══════════════════════════════════════════════════════════════════════════
-- V20 — ETL Import Migration Phase 3: persistent link resolver, households, batch errors
-- ═══════════════════════════════════════════════════════════════════════════
--
-- import_link: one row per "source product's id → our live id" an import learned
-- (person, household, income, expense) and one per reference row (fund, purpose,
-- payment method) an import created. Tenant-bound; deleted with the batch that
-- created it, so a rolled-back run leaves no trace.
-- staging_family.household_proposal / household_decision: a proposed household
-- (same last name + address) for rows without a household id; takes effect only
-- when the admin accepts it.
-- import_batch.error_message: why a load stopped (status FAILED).
-- Additive and idempotent; ddl-auto creates the same objects in dev.
CREATE TABLE IF NOT EXISTS import_link (
    id            BIGSERIAL PRIMARY KEY,
    client_id     VARCHAR(64)  NOT NULL,
    source_system VARCHAR(32)  NOT NULL,
    entity        VARCHAR(32)  NOT NULL,
    source_key    VARCHAR(160) NOT NULL,
    target_id     BIGINT       NOT NULL,
    run_id        BIGINT       NOT NULL,
    batch_id      BIGINT,
    created_date  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_import_link UNIQUE (client_id, source_system, entity, source_key)
);
CREATE INDEX IF NOT EXISTS ix_import_link_run    ON import_link (run_id);
CREATE INDEX IF NOT EXISTS ix_import_link_target ON import_link (client_id, entity, target_id);

ALTER TABLE staging_family ADD COLUMN IF NOT EXISTS household_proposal VARCHAR(160);
ALTER TABLE staging_family ADD COLUMN IF NOT EXISTS household_decision VARCHAR(16);
ALTER TABLE import_batch   ADD COLUMN IF NOT EXISTS error_message      VARCHAR(1000);
