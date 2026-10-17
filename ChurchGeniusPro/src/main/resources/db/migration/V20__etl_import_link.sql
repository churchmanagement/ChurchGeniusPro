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

-- Guarded (2026-10-16, after Phase 5): on a brand-new database Flyway runs before
-- Hibernate creates staging_family / import_batch, so these ALTERs run only when the table
-- exists (Hibernate adds the columns otherwise). import_link above is created outright, as
-- before. Checksum changed: see scripts/sql/flyway_repair_v19_v20.sql.
DO $$
BEGIN
    IF to_regclass('public.staging_family') IS NOT NULL THEN
        ALTER TABLE staging_family ADD COLUMN IF NOT EXISTS household_proposal VARCHAR(160);
        ALTER TABLE staging_family ADD COLUMN IF NOT EXISTS household_decision VARCHAR(16);
    END IF;
    IF to_regclass('public.import_batch') IS NOT NULL THEN
        ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000);
    END IF;
END $$;
