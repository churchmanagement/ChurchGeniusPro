-- 2026-11-16 — Funds, purposes and transaction types: who deleted an entry and when, and which sub-categories
-- were deleted together with their main category (so "Restore" brings back exactly those).
-- Additive only: four nullable columns, no existing row is changed. Guarded so it is a no-op on a brand-new
-- database, where Hibernate creates the tables (with these columns) after Flyway has run.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['main_source', 'sub_source', 'purpose', 'transaction_type'] LOOP
    IF to_regclass('public.' || t) IS NOT NULL THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP', t);
      EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(100)', t);
    END IF;
  END LOOP;
  IF to_regclass('public.sub_source') IS NOT NULL THEN
    ALTER TABLE sub_source ADD COLUMN IF NOT EXISTS deleted_with_parent BOOLEAN;
  END IF;
END $$;
