-- ═══════════════════════════════════════════════════════════════════════════
-- V19 — ETL Import Migration Phase 2: source identity on migrated records
-- ═══════════════════════════════════════════════════════════════════════════
--
-- family.source_system / family.source_id, family_member.source_system /
-- family_member.source_id: which church-management product a record was
-- migrated from (preset code) and that product's own id. Null for everything
-- created in ChurchGeniusPro. A re-import recognises the same record (Exact
-- match) and related files refer to it. Income/expense reuse import_ref (H8).
-- Additive and idempotent; ddl-auto creates the same columns in dev.
--
-- Guarded (2026-10-16, after Phase 5): on a brand-new database Flyway runs before
-- Hibernate creates family / family_member, so each statement runs only when its table
-- exists — a fresh database no-ops here and Hibernate adds the columns. Same effect as
-- before on every existing database. Editing this applied migration changes its checksum:
-- a database that already ran V19 needs its flyway_schema_history checksum updated once
-- (see scripts/sql/flyway_repair_v19_v20.sql).
DO $$
BEGIN
    IF to_regclass('public.family') IS NOT NULL THEN
        ALTER TABLE family ADD COLUMN IF NOT EXISTS source_system VARCHAR(32);
        ALTER TABLE family ADD COLUMN IF NOT EXISTS source_id     VARCHAR(128);
        CREATE INDEX IF NOT EXISTS ix_family_source ON family (app_client_id, source_system, source_id);
    END IF;
    IF to_regclass('public.family_member') IS NOT NULL THEN
        ALTER TABLE family_member ADD COLUMN IF NOT EXISTS source_system VARCHAR(32);
        ALTER TABLE family_member ADD COLUMN IF NOT EXISTS source_id     VARCHAR(128);
        CREATE INDEX IF NOT EXISTS ix_family_member_source ON family_member (app_client_id, source_system, source_id);
    END IF;
END $$;
