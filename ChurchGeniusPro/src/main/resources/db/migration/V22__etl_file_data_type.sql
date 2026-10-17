-- ═══════════════════════════════════════════════════════════════════════════
-- V22 — ETL Import Migration Phase 5: the data type of each uploaded file
-- ═══════════════════════════════════════════════════════════════════════════
--
-- import_file.target_table:    the data type the file holds (family, income, pledge, …),
--                              chosen in the wizard's Files step or detected from headers.
-- import_file.suggested_table: what header detection suggested.
-- Lets a resumed run show each file with its type. Additive and re-runnable; on a fresh
-- database Flyway runs before Hibernate creates import_file, so the ALTERs are guarded
-- (ddl-auto adds the columns there).
DO $$
BEGIN
    IF to_regclass('public.import_file') IS NOT NULL THEN
        ALTER TABLE import_file ADD COLUMN IF NOT EXISTS target_table    VARCHAR(32);
        ALTER TABLE import_file ADD COLUMN IF NOT EXISTS suggested_table VARCHAR(32);
    END IF;
END $$;
