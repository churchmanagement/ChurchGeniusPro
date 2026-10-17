-- ═══════════════════════════════════════════════════════════════════════════
-- V21 — ETL Import Migration Phase 4: new data types
-- ═══════════════════════════════════════════════════════════════════════════
--
-- staging_record: one staged row of pledge campaigns, pledges, events, event
-- registrations, attendance, groups, group memberships, Kids Ministry children and
-- check-ins (typed values as JSON). Tenant-bound like the other staging tables.
-- group_member.family_member_id: the member an imported membership belongs to
-- (approved decision 6) — nullable, informational, not used by the Groups feature.
-- Source identity for these types lives in import_link (V20); no other live table changes.
--
-- Additive and re-runnable. On a fresh database Flyway runs before Hibernate creates
-- the entity tables, so the group_member ALTER is guarded (ddl-auto adds the column
-- there); staging_record is created outright, as V20 creates import_link.
CREATE TABLE IF NOT EXISTS staging_record (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT       NOT NULL,
    client_id       VARCHAR(64)  NOT NULL,
    target_table    VARCHAR(32)  NOT NULL,
    source_row_id   VARCHAR(128),
    source_payload  TEXT         NOT NULL,
    values_json     TEXT,
    links_json      TEXT,
    row_status      VARCHAR(16)  NOT NULL,
    validation_msgs TEXT,
    dedupe_match_id BIGINT,
    dedupe_action   VARCHAR(16),
    match_tier      VARCHAR(16),
    match_detail    VARCHAR(255),
    target_id       BIGINT,
    before_image    TEXT,
    batch_id        BIGINT,
    created_date    TIMESTAMP(6) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_staging_record_run   ON staging_record (run_id, target_table);
CREATE INDEX IF NOT EXISTS ix_staging_record_batch ON staging_record (batch_id);

DO $$
BEGIN
    IF to_regclass('public.group_member') IS NOT NULL THEN
        ALTER TABLE group_member ADD COLUMN IF NOT EXISTS family_member_id INTEGER;
    END IF;
END $$;
