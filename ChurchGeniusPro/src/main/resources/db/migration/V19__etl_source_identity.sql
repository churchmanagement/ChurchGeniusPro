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
ALTER TABLE family        ADD COLUMN IF NOT EXISTS source_system VARCHAR(32);
ALTER TABLE family        ADD COLUMN IF NOT EXISTS source_id     VARCHAR(128);
ALTER TABLE family_member ADD COLUMN IF NOT EXISTS source_system VARCHAR(32);
ALTER TABLE family_member ADD COLUMN IF NOT EXISTS source_id     VARCHAR(128);
CREATE INDEX IF NOT EXISTS ix_family_source        ON family        (app_client_id, source_system, source_id);
CREATE INDEX IF NOT EXISTS ix_family_member_source ON family_member (app_client_id, source_system, source_id);
